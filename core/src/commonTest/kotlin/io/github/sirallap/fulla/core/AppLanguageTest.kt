// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.text.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test
    fun six_languages_in_the_order_they_are_shown() {
        assertEquals(
            listOf("en", "es", "fr", "de", "it", "pt"),
            AppLanguage.ALL.map { it.tag },
        )
    }

    @Test
    fun a_system_locale_preselects_its_matching_language() {
        assertEquals(AppLanguage.SPANISH, AppLanguage.preselectFor("es-ES"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.preselectFor("es_MX"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.preselectFor("fr"))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.preselectFor("pt-BR"))
    }

    @Test
    fun an_unsupported_or_missing_locale_falls_back_to_English() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.preselectFor("ja-JP"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.preselectFor(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.preselectFor("en-US"))
    }
}
