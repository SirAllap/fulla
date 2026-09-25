// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * An invitation as one link (and one QR code): the project, its public anon
 * key and the invite code. `fulla://join?u=<project url>&k=<anon key>&c=<code>`.
 *
 * The anon key is public by design; what protects the household is the code,
 * which expires, works once, and is throttled on the server.
 */
data class InviteLink(val endpoint: Endpoint, val code: String) {

    fun toUri(): String = "fulla://join?u=${enc(endpoint.url)}&k=${enc(endpoint.anonKey)}&c=${enc(code)}"

    companion object {
        fun parse(text: String): InviteLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "fulla" || uri.host != "join") return null
            val q = (uri.rawQuery ?: return null).split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), Charsets.UTF_8)
            }.toMap()
            val endpoint = Endpoint.parse(q["u"] ?: return null, q["k"] ?: return null) ?: return null
            val code = q["c"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return InviteLink(endpoint, code)
        }

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    }
}
