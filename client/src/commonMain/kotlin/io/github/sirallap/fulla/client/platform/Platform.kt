// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.platform

/** What a platform alone can provide: unpredictable bytes. Everything else is common code. */
expect object Random {
    /** [count] bytes from the platform's cryptographic generator. */
    fun bytes(count: Int): ByteArray
}

/** A random (version 4) UUID, lower case. */
fun randomUuid(): String {
    val b = Random.bytes(16)
    b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = io.github.sirallap.fulla.core.text.Hashes.hex(b)
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}

/** A uniformly chosen index below [bound], without the bias of a plain remainder. */
fun randomBelow(bound: Int): Int {
    require(bound in 1..256)
    val limit = 256 - 256 % bound
    while (true) {
        val v = Random.bytes(1)[0].toInt() and 0xff
        if (v < limit) return v % bound
    }
}
