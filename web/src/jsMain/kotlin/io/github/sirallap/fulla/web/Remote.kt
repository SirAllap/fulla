// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.FullaApi
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.InviteProblem
import io.github.sirallap.fulla.client.remote.InviteRefused
import io.github.sirallap.fulla.client.remote.Session
import io.github.sirallap.fulla.client.remote.SessionStore
import io.github.sirallap.fulla.client.remote.Supabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Where the signed-in person's tokens are kept: this browser's storage, for this site only. */
object IdbSessionStore : SessionStore {
    private const val KEY = "session"

    override suspend fun load(): Session? {
        val text = Idb.get(KEY) ?: return null
        return runCatching {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            Session(s("a")!!, s("r")!!, (o["e"] as JsonPrimitive).longOrNull!!, s("u")!!, s("m"))
        }.getOrNull()
    }

    override suspend fun save(session: Session?) {
        if (session == null) { Idb.remove(KEY); return }
        Idb.put(KEY, buildJsonObject {
            put("a", session.accessToken); put("r", session.refreshToken); put("e", session.expiresAt)
            put("u", session.userId); put("m", session.email)
        }.toString())
    }
}

/**
 * The one server this browser talks to: a household's own Supabase project.
 * The transport (`client`'s Supabase) refuses any other host, and so does the
 * page's Content Security Policy.
 */
object Remote {
    private val http: HttpClient by lazy { HttpClient(Js) }

    var endpoint: Endpoint? = null
        private set
    private var supabase: Supabase? = null
    var api: FullaApi? = null
        private set

    fun use(endpoint: Endpoint) {
        if (this.endpoint == endpoint) return
        this.endpoint = endpoint
        val s = Supabase(endpoint, http, IdbSessionStore)
        supabase = s
        api = FullaApi(s)
    }

    suspend fun hasSession(): Boolean = supabase?.currentSession() != null

    /** The person signed in here, or null. */
    suspend fun session(): Session? = supabase?.currentSession()

    /** Signs in, or creates the account first. Null from sign-up means the project wants the email confirmed. */
    suspend fun signIn(email: String, password: String, create: Boolean): Boolean {
        val s = supabase ?: error("No project chosen")
        if (create) {
            if (s.signUp(email, password) == null) {
                // The project wants the email confirmed. Fulla's script confirms accounts itself, so signing in works.
                try { s.signIn(email, password) } catch (e: Exception) { return false }
            }
        } else s.signIn(email, password)
        return true
    }

    suspend fun signOut() {
        supabase?.signOut()
    }

    /** A person's words for what went wrong, in the current language. */
    fun message(e: Throwable): String = when (e) {
        is InviteRefused -> when (e.problem) {
            InviteProblem.EXPIRED -> t("invite_expired")
            InviteProblem.USED -> t("invite_used")
            InviteProblem.ALREADY_MEMBER -> t("invite_already_member")
            InviteProblem.HOUSEHOLD_FULL -> t("invite_full")
            InviteProblem.INVALID -> t("invite_invalid")
        }
        is FullaError -> when (e.code) {
            FullaError.INVALID_CREDENTIALS -> t("wrong_credentials")
            FullaError.EMAIL_NOT_CONFIRMED -> t("confirm_email")
            FullaError.NETWORK -> t("project_unreachable")
            FullaError.RATE_LIMITED -> t("too_many_attempts")
            FullaError.NOT_AUTHENTICATED, FullaError.SESSION_EXPIRED -> t("sync_sign_in_again")
            "invite_expired" -> t("invite_expired")
            "invite_used" -> t("invite_used")
            "invalid_invite" -> t("invite_invalid")
            else -> e.message.ifBlank { t("something_failed") }
        }
        else -> t("something_failed")
    }
}
