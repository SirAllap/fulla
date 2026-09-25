// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.text

import java.text.Normalizer

// The same combining-mark blocks the database strips in fulla.normalize_name.
// Listed explicitly, rather than \p{M}, so both sides remove exactly the same
// characters. testdata/vectors/normalize_name.json holds them together.
private val COMBINING = Regex("[̀-ͯ᪰-᫿᷀-᷿⃐-⃿︠-︯]")

/**
 * A name as it is compared: accents removed, lower case, trimmed. "Café" typed
 * on a keyboard that composes accents and one that does not are one name.
 */
fun String.normalizeName(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING, "")
        .lowercase()
        .trim()
