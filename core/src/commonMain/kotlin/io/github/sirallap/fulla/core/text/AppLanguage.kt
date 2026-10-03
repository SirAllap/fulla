// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.text

/**
 * The languages Fulla ships a translation for, and which one a system locale
 * should preselect. Pure logic so it can be tested on the JVM; the app and
 * the client side of onboarding both read [ALL] and [preselectFor].
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    ITALIAN("it", "Italiano"),
    PORTUGUESE("pt", "Português");

    companion object {
        val ALL: List<AppLanguage> = entries

        /**
         * Which of [ALL] a system locale tag (e.g. "pt-BR", "es_ES", "fr")
         * should preselect. Falls back to English when nothing matches.
         */
        fun preselectFor(systemLocaleTag: String?): AppLanguage {
            val language = systemLocaleTag?.substringBefore('-')?.substringBefore('_')?.lowercase()
            return ALL.firstOrNull { it.tag == language } ?: ENGLISH
        }
    }
}
