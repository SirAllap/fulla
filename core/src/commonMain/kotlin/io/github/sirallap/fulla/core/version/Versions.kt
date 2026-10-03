// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.version

/**
 * Semantic-version parsing and comparison for the GitHub-release updater.
 *
 * Tags look like `v1.2.3`; a leading `v` is optional. A trailing pre-release
 * or build tag (`-check`, `-rc1`, `+deadbeef`) is ignored for comparison,
 * since releases carry none and only ad-hoc builds might. `0.0.0` (with or
 * without such a tag) means "no real version" and never counts as an update,
 * so a debug build or a broken tag never looks newer than anything, and
 * nothing ever looks newer than it either.
 */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {

    val isNone: Boolean get() = major == 0 && minor == 0 && patch == 0

    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

        fun parse(raw: String): Version? {
            val m = PATTERN.matchEntire(raw.trim()) ?: return null
            val (major, minor, patch) = m.destructured
            return runCatching { Version(major.toInt(), minor.toInt(), patch.toInt()) }.getOrNull()
        }
    }
}

object Versions {
    /** True when [candidate] is a real, parseable version strictly newer than [current]. */
    fun isNewer(current: String, candidate: String): Boolean {
        val currentVersion = Version.parse(current) ?: return false
        val candidateVersion = Version.parse(candidate) ?: return false
        if (currentVersion.isNone || candidateVersion.isNone) return false
        return candidateVersion > currentVersion
    }
}
