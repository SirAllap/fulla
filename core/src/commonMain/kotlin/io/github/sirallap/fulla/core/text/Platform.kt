// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.text

/** The letters of [this] with accents split off as separate marks (Unicode NFD). */
internal expect fun String.decomposed(): String

/** What each platform decides for itself about text in files and in locales. */
object PlatformText {
    /** The bytes as UTF-8, or null if they are not valid UTF-8. */
    fun decodeUtf8Strict(bytes: ByteArray): String? = decodeUtf8StrictImpl(bytes)

    /** The bytes in a named encoding (`windows-1252`, `ISO-8859-1`…). */
    fun decode(bytes: ByteArray, encoding: String): String = decodeImpl(bytes, encoding)
}

internal expect fun decodeUtf8StrictImpl(bytes: ByteArray): String?
internal expect fun decodeImpl(bytes: ByteArray, encoding: String): String
