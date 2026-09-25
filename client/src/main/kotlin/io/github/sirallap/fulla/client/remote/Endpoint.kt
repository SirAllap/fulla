// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

import java.net.URI

/**
 * The one server this phone talks to: a household's own Supabase project.
 *
 * Only `https://<project>.supabase.co` is accepted. The app's promise is that
 * it contacts exactly one host and that host belongs to the household; a URL
 * anywhere else, or over plain HTTP, is refused before a byte is sent.
 */
data class Endpoint(val url: String, val anonKey: String) {
    val host: String get() = URI(url).host

    companion object {
        private val PROJECT = Regex("^[a-z0-9]{20}$")

        /** A project URL as a person pastes it, or null with the reason in [problem]. */
        fun parse(rawUrl: String, anonKey: String): Endpoint? =
            if (problem(rawUrl, anonKey) != null) null else Endpoint(normalise(rawUrl), anonKey.trim())

        fun problem(rawUrl: String, anonKey: String): Problem? {
            val uri = runCatching { URI(normalise(rawUrl)) }.getOrNull() ?: return Problem.NOT_A_URL
            if (uri.scheme != "https") return Problem.NOT_HTTPS
            val host = uri.host ?: return Problem.NOT_A_URL
            if (!host.endsWith(".supabase.co") || !PROJECT.matches(host.removeSuffix(".supabase.co"))) return Problem.NOT_SUPABASE
            if (uri.port != -1 && uri.port != 443) return Problem.NOT_SUPABASE
            if (anonKey.isBlank() || anonKey.trim().any { it.isWhitespace() }) return Problem.BAD_KEY
            return null
        }

        private fun normalise(raw: String): String {
            val t = raw.trim().trimEnd('/')
            val withScheme = if ("://" in t) t else "https://$t"
            // Keep only scheme and host: a pasted dashboard or REST URL still names the project.
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return withScheme
            if (uri.host == null) return withScheme
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return "${uri.scheme}://${uri.host.lowercase()}$port"
        }
    }

    enum class Problem { NOT_A_URL, NOT_HTTPS, NOT_SUPABASE, BAD_KEY }
}
