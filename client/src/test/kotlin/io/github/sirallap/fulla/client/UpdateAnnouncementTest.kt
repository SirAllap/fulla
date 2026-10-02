// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.remote.UpdateAnnouncement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateAnnouncementTest {
    private val hour = 60 * 60 * 1000L
    private val t0 = 1_900_000_000_000L

    @Test
    fun `nothing is announced when nothing is waiting`() {
        assertFalse(UpdateAnnouncement.due(null, null, 0, t0))
        assertFalse(UpdateAnnouncement.due(null, "0.1.30", t0 - 100 * hour, t0))
    }

    @Test
    fun `a version nobody was told about is announced at once`() {
        assertTrue(UpdateAnnouncement.due("0.1.30", null, 0, t0))
        assertTrue(UpdateAnnouncement.due("0.1.31", "0.1.30", t0 - hour, t0), "a newer one is news even a minute after the last")
    }

    @Test
    fun `the same version is announced again only after a day`() {
        assertFalse(UpdateAnnouncement.due("0.1.30", "0.1.30", t0 - 5 * hour, t0))
        assertFalse(UpdateAnnouncement.due("0.1.30", "0.1.30", t0 - 23 * hour, t0))
        assertTrue(UpdateAnnouncement.due("0.1.30", "0.1.30", t0 - 24 * hour, t0))
        assertTrue(UpdateAnnouncement.due("0.1.30", "0.1.30", t0 - 72 * hour, t0))
    }

    @Test
    fun `a clock set back does not make it nag`() {
        assertFalse(UpdateAnnouncement.due("0.1.30", "0.1.30", t0 + 5 * hour, t0))
    }
}
