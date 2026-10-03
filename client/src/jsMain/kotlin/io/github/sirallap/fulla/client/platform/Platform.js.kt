// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.platform

import org.khronos.webgl.Uint8Array

actual object Random {
    actual fun bytes(count: Int): ByteArray {
        val array = Uint8Array(count)
        js("globalThis.crypto.getRandomValues(array)")
        return ByteArray(count) { array.asDynamic()[it] as Byte }
    }
}
