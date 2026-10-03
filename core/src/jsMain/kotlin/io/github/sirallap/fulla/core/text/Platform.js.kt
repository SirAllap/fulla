// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.text

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

internal actual fun String.decomposed(): String = this.asDynamic().normalize("NFD") as String

private fun view(bytes: ByteArray): Uint8Array {
    val i8 = bytes.unsafeCast<Int8Array>()
    return Uint8Array(i8.buffer, i8.byteOffset, i8.length)
}

internal actual fun decodeUtf8StrictImpl(bytes: ByteArray): String? = try {
    val options = js("({ fatal: true, ignoreBOM: true })")
    js("new TextDecoder('utf-8', options)").unsafeCast<dynamic>().decode(view(bytes)) as String
} catch (e: Throwable) {
    null
}

internal actual fun decodeImpl(bytes: ByteArray, encoding: String): String =
    js("new TextDecoder(encoding)").unsafeCast<dynamic>().decode(view(bytes)) as String
