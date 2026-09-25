// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A one-time value that ties a Google ID token to this sign-in: its SHA-256
 * goes into the token request, the raw value to the server, which checks
 * they match. A token taken from somewhere else cannot be replayed.
 */
class Nonce private constructor(val raw: String) {
    val hashed: String
        get() = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        fun create(random: SecureRandom = SecureRandom()): Nonce {
            val bytes = ByteArray(24).also(random::nextBytes)
            return Nonce(bytes.joinToString("") { "%02x".format(it) })
        }
    }
}
