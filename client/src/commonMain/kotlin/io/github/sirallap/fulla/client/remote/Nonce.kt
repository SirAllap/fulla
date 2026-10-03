// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

import io.github.sirallap.fulla.client.platform.Random
import io.github.sirallap.fulla.core.text.Hashes

/**
 * A one-time value that ties a Google ID token to this sign-in: its SHA-256
 * goes into the token request, the raw value to the server, which checks
 * they match. A token taken from somewhere else cannot be replayed.
 */
class Nonce private constructor(val raw: String) {
    val hashed: String
        get() = Hashes.hex(Hashes.sha256(raw.encodeToByteArray()))

    companion object {
        fun create(): Nonce {
            return Nonce(Hashes.hex(Random.bytes(24)))
        }
    }
}
