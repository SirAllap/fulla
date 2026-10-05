// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.version.ReleaseHistory
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseHistoryTest {
    @Test
    fun `versions come newest first and by number, not by text`() {
        assertEquals(listOf("0.1.34", "0.1.10", "0.1.9", "0.1.2"),
            ReleaseHistory.versions(listOf("0.1.2.md", "0.1.10.md", "0.1.34.md", "0.1.9.md")))
    }

    @Test
    fun `what is not a version's notes is left out`() {
        assertEquals(listOf("0.1.3"), ReleaseHistory.versions(listOf("README.md", "0.1.3.md", "0.0.0.md", "0.1.4.txt", "notes.md")))
    }
}
