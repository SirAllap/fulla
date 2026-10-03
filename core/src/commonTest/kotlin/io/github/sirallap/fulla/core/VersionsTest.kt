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
    fun parses_with_and_without_a_leading_v() {
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
        assertEquals(Version(0, 1, 0), Version.parse("v0.1.0"))
    }

    @Test
    fun ignores_a_pre_release_or_build_tag() {
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3-check"))
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3-rc1"))
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3+deadbeef"))
    }

    @Test
    fun rejects_what_is_not_a_version() {
        assertNull(Version.parse(""))
        assertNull(Version.parse("latest-debug"))
        assertNull(Version.parse("v1.2"))
        assertNull(Version.parse("1.2.3.4"))
    }

    @Test
    fun compares_by_major_then_minor_then_patch() {
        assertTrue(Version(2, 0, 0) > Version(1, 9, 9))
        assertTrue(Version(1, 2, 0) > Version(1, 1, 9))
        assertTrue(Version(1, 1, 2) > Version(1, 1, 1))
        assertEquals(Version(1, 2, 3), Version(1, 2, 3))
    }

    @Test
    fun t0_0_0_is_no_version_and_never_counts_as_an_update() {
        assertTrue(Version(0, 0, 0).isNone)
        assertTrue(Version.parse("v0.0.0-debug")!!.isNone)
        assertFalse(Versions.isNewer("0.0.0", "v1.0.0"))
        assertFalse(Versions.isNewer("0.0.0-debug", "v1.0.0"))
        assertFalse(Versions.isNewer("v1.0.0", "0.0.0"))
    }

    @Test
    fun isNewer_compares_real_versions_and_rejects_unparseable_ones() {
        assertTrue(Versions.isNewer("v0.1.0", "v0.2.0"))
        assertFalse(Versions.isNewer("v0.2.0", "v0.1.0"))
        assertFalse(Versions.isNewer("v1.0.0", "v1.0.0"))
        assertFalse(Versions.isNewer("v1.0.0", "not-a-version"))
        assertFalse(Versions.isNewer("not-a-version", "v1.0.0"))
        assertTrue(Versions.isNewer("v0.1.0-debug", "v0.2.0-check"))
    }
}
