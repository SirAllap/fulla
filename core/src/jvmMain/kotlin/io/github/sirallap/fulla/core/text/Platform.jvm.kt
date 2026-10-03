// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

internal actual fun String.decomposed(): String = Normalizer.normalize(this, Normalizer.Form.NFD)

internal actual fun decodeUtf8StrictImpl(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (e: CharacterCodingException) {
    null
}

internal actual fun decodeImpl(bytes: ByteArray, encoding: String): String = String(bytes, Charset.forName(encoding))
