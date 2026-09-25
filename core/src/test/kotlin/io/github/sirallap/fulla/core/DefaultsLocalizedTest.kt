// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.defaults.Defaults
import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.Category
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultsLocalizedTest {
    @Test
    fun `untouched defaults read in the app's language, chosen names stay`() {
        val config = Fixtures.config().copy(
            categories = listOf(Category("c1", "Groceries"), Category("c2", "Pets"), Category("c3", "Uncategorized")),
            accounts = listOf(Account("a1", "Cash"), Account("a2", "Joint account")),
        )
        val es = Defaults.localized(config, "es-ES")
        assertEquals(listOf("Supermercado", "Pets", "Sin categoría"), es.categories.map { it.name })
        assertEquals(listOf("Efectivo", "Joint account"), es.accounts.map { it.name })
        // Created in Spanish, read in English; ids and everything else unchanged.
        val back = Defaults.localized(es, "en")
        assertEquals(config.categories, back.categories)
        assertEquals("Groceries", Defaults.localized(config, "xx").categories[0].name)
    }
}
