// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui

import io.github.sirallap.fulla.ui.settings.SettingsGroup
import io.github.sirallap.fulla.ui.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsGroupsTest {
    @Test
    fun `every section but the index is in exactly one block of Settings`() {
        val listed = SettingsGroup.entries.flatMap { it.sections }
        assertEquals("no section twice", listed.size, listed.toSet().size)
        assertEquals("none left out", SettingsSection.entries.toSet() - SettingsSection.INDEX, listed.toSet())
    }
}
