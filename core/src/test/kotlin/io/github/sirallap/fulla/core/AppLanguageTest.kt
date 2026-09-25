// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.text.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test
    fun `six languages, in the order they are shown`() {
        assertEquals(
            listOf("en", "es", "fr", "de", "it", "pt"),
            AppLanguage.ALL.map { it.tag },
        )
    }

    @Test
    fun `a system locale preselects its matching language`() {
        assertEquals(AppLanguage.SPANISH, AppLanguage.preselectFor("es-ES"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.preselectFor("es_MX"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.preselectFor("fr"))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.preselectFor("pt-BR"))
    }

    @Test
    fun `an unsupported or missing locale falls back to English`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.preselectFor("ja-JP"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.preselectFor(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.preselectFor("en-US"))
    }
}
