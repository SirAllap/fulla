// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

/**
 * The parts of a link Fulla looks at: scheme, host, port and query. Not a
 * general URI parser, and deliberately strict: no credentials in the address,
 * no spaces, nothing it does not understand.
 */
internal class SimpleUri private constructor(val scheme: String, val host: String?, val port: Int, val rawQuery: String?) {

    companion object {
        private val SHAPE = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#:@\\s]*)(?::(\\d+))?([^?#\\s]*)(?:\\?([^#\\s]*))?(?:#\\S*)?$")

        fun parse(text: String): SimpleUri? {
            val m = SHAPE.matchEntire(text) ?: return null
            val port = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: -1
            return SimpleUri(
                scheme = m.groupValues[1].lowercase(),
                host = m.groupValues[2].takeIf { it.isNotEmpty() },
                port = port,
                rawQuery = m.groups[5]?.value,
            )
        }
    }
}

/** Percent-encoding for the query of an invitation link: everything but letters, digits and `-._~`. */
internal fun encodeQueryValue(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xff
        val plain = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
        if (plain) append(c.toChar()) else { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
    }
}

/** The inverse, with `+` as a space; null when a `%` is not followed by two hex digits. */
internal fun decodeQueryValue(text: String): String? {
    val out = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '%' -> {
                val hex = text.substring(i + 1, minOf(i + 3, text.length))
                if (hex.length != 2) return null
                out += (hex.toIntOrNull(16) ?: return null).toByte()
                i += 3
            }
            c == '+' -> { out += ' '.code.toByte(); i++ }
            else -> { out.addAll(c.toString().encodeToByteArray().toList()); i++ }
        }
    }
    return out.toByteArray().decodeToString()
}
