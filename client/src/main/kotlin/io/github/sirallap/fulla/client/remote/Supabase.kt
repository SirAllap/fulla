// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

import io.github.sirallap.fulla.client.wire.Wire
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The transport: Supabase Auth and PostgREST's `rpc`, by hand.
 *
 * No Supabase SDK. These few calls are all the app needs, and written out
 * they can be read in one sitting by anyone checking what the app sends and
 * where. Every request is checked against [endpoint]'s host before it leaves:
 * a redirect or a bug that pointed anywhere else fails instead.
 */
class Supabase(
    val endpoint: Endpoint,
    httpClient: HttpClient,
    private val sessions: SessionStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val http = httpClient.config { followRedirects = false }.also { client ->
        client.plugin(HttpSend).intercept { request ->
            if (request.url.host != endpoint.host || request.url.protocol.name != "https") {
                throw FullaError(FullaError.FOREIGN_HOST, "Refused a request to a host other than the household's project.")
            }
            execute(request)
        }
    }

    private val refreshing = Mutex()

    // ── auth ─────────────────────────────────────────────────────────────────

    suspend fun signIn(email: String, password: String): Session = authenticate(
        "/auth/v1/token?grant_type=password",
        buildJsonObject { put("email", email.trim()); put("password", password) },
    ) ?: throw FullaError(FullaError.BAD_RESPONSE, "The server did not return a session.")

    /**
     * Signs in with an ID token from a platform provider (Google, through
     * Android's Credential Manager). The account is created on first use.
     * [nonce] is the raw value whose SHA-256 was put in the token request.
     */
    suspend fun signInWithIdToken(provider: String, idToken: String, nonce: String?): Session = authenticate(
        "/auth/v1/token?grant_type=id_token",
        buildJsonObject {
            put("provider", provider)
            put("id_token", idToken)
            nonce?.let { put("nonce", it) }
        },
    ) ?: throw FullaError(FullaError.BAD_RESPONSE, "The server did not return a session.")

    /**
     * Creates an account. Returns the session, or null when the project asks
     * people to confirm their email first (Supabase's default).
     */
    suspend fun signUp(email: String, password: String): Session? = authenticate(
        "/auth/v1/signup",
        buildJsonObject { put("email", email.trim()); put("password", password) },
    )

    suspend fun sendPasswordReset(email: String) {
        val response = send("/auth/v1/recover", buildJsonObject { put("email", email.trim()) }, bearer = null)
        if (response.status.value !in 200..299) throw authError(response.status.value, response.bodyAsText())
    }

    suspend fun signOut() {
        val session = sessions.load()
        sessions.save(null)
        if (session != null) runCatching { send("/auth/v1/logout", JsonObject(emptyMap()), session.accessToken) }
    }

    suspend fun currentSession(): Session? = sessions.load()

    private suspend fun authenticate(path: String, body: JsonObject): Session? {
        val response = send(path, body, bearer = null)
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) throw authError(response.status.value, text)
        val session = session(parse(text) as? JsonObject ?: throw badResponse()) ?: return null
        sessions.save(session)
        return session
    }

    private fun session(o: JsonObject): Session? {
        val access = o.string("access_token") ?: return null
        val refresh = o.string("refresh_token") ?: return null
        val user = o["user"] as? JsonObject ?: return null
        val expiresAt = (o["expires_at"] as? JsonPrimitive)?.longOrNull
            ?: ((o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600) + clock()
        return Session(access, refresh, expiresAt, user.string("id") ?: return null, user.string("email"))
    }

    /** A fresh session, refreshing at most once however many callers find the old one stale. */
    private suspend fun refreshed(stale: Session): Session = refreshing.withLock {
        val current = sessions.load() ?: throw FullaError(FullaError.NOT_AUTHENTICATED, "Not signed in.", 401)
        if (current.accessToken != stale.accessToken) return current // somebody else already refreshed
        val response = send("/auth/v1/token?grant_type=refresh_token",
            buildJsonObject { put("refresh_token", current.refreshToken) }, bearer = null)
        val text = response.bodyAsText()
        if (response.status.value in 400..499) {
            sessions.save(null)
            throw FullaError(FullaError.SESSION_EXPIRED, "The session has ended. Sign in again.", response.status.value)
        }
        if (response.status.value !in 200..299) throw FullaError(FullaError.SERVER, "The server could not refresh the session.", response.status.value)
        val next = session(parse(text) as? JsonObject ?: throw badResponse()) ?: throw badResponse()
        sessions.save(next)
        next
    }

    // ── rpc ──────────────────────────────────────────────────────────────────

    /**
     * Calls `public.<function>` with named arguments. A 401 refreshes the
     * session once and repeats the call; a second 401 means signing in again.
     */
    suspend fun rpc(function: String, args: JsonObject = JsonObject(emptyMap())): JsonElement {
        var session = sessions.load() ?: throw FullaError(FullaError.NOT_AUTHENTICATED, "Not signed in.", 401)
        if (session.expiresAt - 30 <= clock()) session = refreshed(session)
        var response = send("/rest/v1/rpc/$function", args, session.accessToken)
        if (response.status.value == 401) {
            session = refreshed(session)
            response = send("/rest/v1/rpc/$function", args, session.accessToken)
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) throw rpcError(response.status.value, text)
        return if (text.isBlank()) JsonNull else parse(text)
    }

    private suspend fun send(path: String, body: JsonObject, bearer: String?): HttpResponse = try {
        http.post(endpoint.url + path) {
            header("apikey", endpoint.anonKey)
            header(HttpHeaders.Authorization, "Bearer ${bearer ?: endpoint.anonKey}")
            header(HttpHeaders.Accept, "application/json")
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: FullaError) {
        throw e
    } catch (e: Exception) {
        throw FullaError(FullaError.NETWORK, "Could not reach the server.")
    }

    // ── errors ───────────────────────────────────────────────────────────────

    private fun parse(text: String): JsonElement = runCatching { Wire.json.parseToJsonElement(text) }.getOrElse { throw badResponse() }

    private fun badResponse() = FullaError(FullaError.BAD_RESPONSE, "The server's answer could not be read.")

    /** PostgREST's `{code, message, details, hint}`; the backend's own code is in `details`. */
    internal fun rpcError(status: Int, text: String): FullaError {
        val o = runCatching { Wire.json.parseToJsonElement(text).jsonObject }.getOrNull()
        val message = o?.string("message") ?: "The server refused the request."
        val details = o?.string("details")?.takeIf { it.matches(Regex("^[a-z_]+$")) }
        val code = details ?: when (status) {
            401 -> FullaError.NOT_AUTHENTICATED
            403 -> FullaError.NOT_MEMBER
            429 -> FullaError.RATE_LIMITED
            in 500..599 -> FullaError.SERVER
            else -> FullaError.VALIDATION_FAILED
        }
        return FullaError(code, message, status)
    }

    /** Supabase Auth answers `{code, error_code, msg}` or `{error, error_description}`. */
    internal fun authError(status: Int, text: String): FullaError {
        val o = runCatching { Wire.json.parseToJsonElement(text).jsonObject }.getOrNull()
        val raw = o?.string("error_code") ?: o?.string("error") ?: ""
        val message = o?.string("msg") ?: o?.string("error_description") ?: o?.string("message") ?: "Sign-in failed."
        val code = when {
            raw == "invalid_grant" || raw == "invalid_credentials" -> FullaError.INVALID_CREDENTIALS
            raw == "email_not_confirmed" -> FullaError.EMAIL_NOT_CONFIRMED
            status == 429 || raw == "over_request_rate_limit" || raw == "over_email_send_rate_limit" -> FullaError.RATE_LIMITED
            status >= 500 -> FullaError.SERVER
            raw.matches(Regex("^[a-z_]+$")) -> raw
            else -> FullaError.VALIDATION_FAILED
        }
        return FullaError(code, message, status)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}
