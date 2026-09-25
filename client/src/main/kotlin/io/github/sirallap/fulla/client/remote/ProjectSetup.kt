// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

import io.github.sirallap.fulla.client.wire.Wire
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * Sets up a household's own Supabase project from the phone, so nobody has to
 * find a SQL editor: with a personal access token the person creates once on
 * supabase.com, it creates a project in their account, waits for it to start,
 * installs Fulla's database, lets people sign up without an email round trip,
 * and hands back the project's address and public key.
 *
 * This is the only code that talks to Supabase's Management API
 * (api.supabase.com), and only during setup. The token is used for these calls
 * and never stored; the project's database password is random and never
 * shown, since nothing in Fulla needs it.
 */
class ProjectSetup(
    httpClient: HttpClient,
    private val token: String,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    data class Organization(val slug: String, val name: String)

    /** A project in the person's Supabase account. [paused] projects must be resumed on supabase.com first. */
    data class Project(val ref: String, val name: String, val organization: String?, val region: String?, val paused: Boolean)

    enum class Step { CREATING, STARTING, INSTALLING, CONFIGURING, DONE }

    private val http = httpClient.config { followRedirects = false }.also { client ->
        client.plugin(HttpSend).intercept { request ->
            if (request.url.host != HOST || request.url.protocol.name != "https") {
                throw FullaError(FullaError.FOREIGN_HOST, "Refused a request to a host other than Supabase's.")
            }
            execute(request)
        }
    }

    suspend fun organizations(): List<Organization> =
        call(HttpMethod.Get, "/v1/organizations").jsonArray.map { it.jsonObject }
            .mapNotNull { o -> o.text("slug")?.let { Organization(it, o.text("name") ?: it) } }

    /** The account's projects, newest names first as Supabase lists them; removed ones left out. */
    suspend fun projects(): List<Project> =
        call(HttpMethod.Get, "/v1/projects").jsonArray.map { it.jsonObject }
            .filter { it.text("status") !in setOf("REMOVED", "INIT_FAILED", "GOING_DOWN") }
            .mapNotNull { o ->
                o.text("ref")?.let { ref ->
                    Project(ref, o.text("name") ?: ref, o.text("organization_slug"), o.text("region"),
                        paused = o.text("status") in setOf("INACTIVE", "PAUSING"))
                }
            }

    /**
     * Connects to a project that already exists: waits for it, installs or
     * upgrades Fulla's database (the script is safe to run again), and hands
     * back its address and public key. A project Fulla did not create keeps
     * its own sign-in settings: [ownProject] false leaves email confirmation
     * as the person set it, since other apps may share that project.
     */
    suspend fun connect(ref: String, sql: String, ownProject: Boolean, onStep: (Step) -> Unit = {}): Endpoint {
        onStep(Step.STARTING)
        waitUntilHealthy(ref)
        onStep(Step.INSTALLING)
        call(HttpMethod.Post, "/v1/projects/$ref/database/query", buildJsonObject { put("query", sql) })
        onStep(Step.CONFIGURING)
        if (ownProject) call(HttpMethod.Patch, "/v1/projects/$ref/config/auth", buildJsonObject { put("mailer_autoconfirm", true) })
        val key = anonKey(ref)
        onStep(Step.DONE)
        return Endpoint.parse("https://$ref.supabase.co", key)
            ?: throw FullaError(FullaError.BAD_RESPONSE, "Supabase answered with an address Fulla cannot use.")
    }

    /**
     * The whole setup. [sql] is the bundled setup script; [region] one of
     * Supabase's smart region groups ("emea", "americas", "apac"). Returns
     * the new project's endpoint.
     */
    suspend fun run(organization: String, sql: String, region: String, name: String = PROJECT_NAME, onStep: (Step) -> Unit = {}): Endpoint {
        onStep(Step.CREATING)
        // A second attempt (the first gave up waiting, or the phone lost its
        // connection) carries on with the project the first one made.
        val ref = existing(organization, name) ?: create(organization, name, region)
        // Supabase's built-in email only reaches the account's own team, so a
        // confirmation email would never reach anyone else in the household.
        // Signing up is not joining: a household is only reachable by invite.
        return connect(ref, sql, ownProject = true, onStep = onStep)
    }

    internal suspend fun create(organization: String, name: String, region: String): String {
        val body = buildJsonObject {
            put("name", name)
            put("organization_slug", organization)
            put("db_pass", password())
            put("region_selection", buildJsonObject { put("type", "smartGroup"); put("code", region) })
        }
        return call(HttpMethod.Post, "/v1/projects", body).jsonObject.text("ref")
            ?: throw FullaError(FullaError.BAD_RESPONSE, "Supabase did not say which project it created.")
    }

    internal suspend fun existing(organization: String, name: String): String? =
        call(HttpMethod.Get, "/v1/projects").jsonArray.map { it.jsonObject }.firstOrNull {
            it.text("name") == name && it.text("organization_slug") == organization &&
                it.text("status") !in setOf("REMOVED", "INIT_FAILED", "GOING_DOWN")
        }?.text("ref")

    /** A new project takes a minute or two. Gives up after about eight. */
    internal suspend fun waitUntilHealthy(ref: String) {
        repeat(POLLS) {
            val status = runCatching { call(HttpMethod.Get, "/v1/projects/$ref").jsonObject.text("status") }.getOrNull()
            if (status == "ACTIVE_HEALTHY") {
                val services = runCatching {
                    call(HttpMethod.Get, "/v1/projects/$ref/health?services=db,rest,auth").jsonArray
                }.getOrNull()
                if (services != null && services.isNotEmpty() &&
                    services.all { it.jsonObject.text("status") == "ACTIVE_HEALTHY" }) return
            }
            if (status == "INIT_FAILED") throw FullaError(SETUP_FAILED, "Supabase could not start the project.")
            if (status == "INACTIVE") throw FullaError(PROJECT_PAUSED, "The project is paused.")
            pause(POLL_MS)
        }
        throw FullaError(SETUP_SLOW, "The project is taking longer than usual to start.")
    }

    /** The legacy anon key if the project has one, else the publishable key. */
    internal suspend fun anonKey(ref: String): String {
        val keys = call(HttpMethod.Get, "/v1/projects/$ref/api-keys?reveal=true").jsonArray.map { it.jsonObject }
        return (keys.firstOrNull { it.text("name") == "anon" } ?: keys.firstOrNull { it.text("type") == "publishable" })
            ?.text("api_key") ?: throw FullaError(FullaError.BAD_RESPONSE, "Supabase did not return the project's public key.")
    }

    private suspend fun call(method: HttpMethod, path: String, body: JsonObject? = null): JsonElement {
        val response: HttpResponse = try {
            val url = "https://$HOST$path"
            val setup: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/json")
                if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
            when (method) {
                HttpMethod.Get -> http.get(url, setup)
                HttpMethod.Patch -> http.patch(url, setup)
                else -> http.post(url, setup)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FullaError) {
            throw e
        } catch (e: Exception) {
            throw FullaError(FullaError.NETWORK, "Could not reach Supabase.")
        }
        val text = response.bodyAsText()
        val status = response.status.value
        if (status !in 200..299) throw error(status, text)
        return if (text.isBlank()) JsonArray(emptyList()) else
            runCatching { Wire.json.parseToJsonElement(text) }.getOrElse { throw FullaError(FullaError.BAD_RESPONSE, "Supabase's answer could not be read.") }
    }

    internal fun error(status: Int, text: String): FullaError {
        val message = runCatching { Wire.json.parseToJsonElement(text).jsonObject.text("message") }.getOrNull() ?: text.take(200)
        val code = when {
            status == 401 || status == 403 -> TOKEN_REFUSED
            status == 429 -> FullaError.RATE_LIMITED
            Regex("limit|maximum|max(imum)? number", RegexOption.IGNORE_CASE).containsMatchIn(message) -> PROJECT_LIMIT
            status >= 500 -> FullaError.SERVER
            else -> SETUP_FAILED
        }
        return FullaError(code, message, status)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun password(): String {
        val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return (1..32).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    companion object {
        const val HOST = "api.supabase.com"
        const val TOKEN_PAGE = "https://supabase.com/dashboard/account/tokens"
        const val SIGN_UP_PAGE = "https://supabase.com/dashboard/sign-up"
        const val TOKEN_REFUSED = "setup_token_refused"
        const val PROJECT_LIMIT = "setup_project_limit"
        const val SETUP_FAILED = "setup_failed"
        const val SETUP_SLOW = "setup_slow"
        const val PROJECT_PAUSED = "setup_project_paused"
        /** The name Fulla gives a project it creates. */
        const val PROJECT_NAME = "fulla"
        private const val POLLS = 96
        private const val POLL_MS = 5_000L

        /** Supabase's smart region group for a time zone like "Europe/Lisbon". */
        fun regionFor(timeZone: String): String = when (timeZone.substringBefore('/')) {
            "America", "US", "Canada", "Brazil", "Mexico", "Chile" -> "americas"
            "Asia", "Australia", "Pacific", "Indian" -> "apac"
            else -> "emea"
        }
    }
}
