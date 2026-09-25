// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.version.Version
import io.github.sirallap.fulla.core.version.Versions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionsTest {

    @Test
    fun `parses with and without a leading v`() {
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
        assertEquals(Version(0, 1, 0), Version.parse("v0.1.0"))
    }

    @Test
    fun `ignores a pre-release or build tag`() {
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3-check"))
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3-rc1"))
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3+deadbeef"))
    }

    @Test
    fun `rejects what is not a version`() {
        assertNull(Version.parse(""))
        assertNull(Version.parse("latest-debug"))
        assertNull(Version.parse("v1.2"))
        assertNull(Version.parse("1.2.3.4"))
    }

    @Test
    fun `compares by major, then minor, then patch`() {
        assertTrue(Version(2, 0, 0) > Version(1, 9, 9))
        assertTrue(Version(1, 2, 0) > Version(1, 1, 9))
        assertTrue(Version(1, 1, 2) > Version(1, 1, 1))
        assertEquals(Version(1, 2, 3), Version(1, 2, 3))
    }

    @Test
    fun `0-0-0 is no version, and never counts as an update`() {
        assertTrue(Version(0, 0, 0).isNone)
        assertTrue(Version.parse("v0.0.0-debug")!!.isNone)
        assertFalse(Versions.isNewer("0.0.0", "v1.0.0"))
        assertFalse(Versions.isNewer("0.0.0-debug", "v1.0.0"))
        assertFalse(Versions.isNewer("v1.0.0", "0.0.0"))
    }

    @Test
    fun `isNewer compares real versions and rejects unparseable ones`() {
        assertTrue(Versions.isNewer("v0.1.0", "v0.2.0"))
        assertFalse(Versions.isNewer("v0.2.0", "v0.1.0"))
        assertFalse(Versions.isNewer("v1.0.0", "v1.0.0"))
        assertFalse(Versions.isNewer("v1.0.0", "not-a-version"))
        assertFalse(Versions.isNewer("not-a-version", "v1.0.0"))
        assertTrue(Versions.isNewer("v0.1.0-debug", "v0.2.0-check"))
    }
}
