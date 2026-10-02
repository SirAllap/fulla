// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

/**
 * When the app interrupts what the person is doing to say a new version is
 * out. Once per version, and again a day later while it is still not
 * installed: often enough that nobody misses new features, rarely enough
 * that nobody is nagged. Between those, the update stays one tap away in
 * Settings (the gear's dot and the banner at the top).
 */
object UpdateAnnouncement {
    const val REMIND_AFTER_MS: Long = 24L * 60 * 60 * 1000

    /**
     * @param pending the version waiting to be installed, if any.
     * @param announcedVersion the version the person was last told about, and [announcedAt] when (epoch millis).
     */
    fun due(pending: String?, announcedVersion: String?, announcedAt: Long, now: Long): Boolean =
        pending != null && (announcedVersion != pending || now - announcedAt >= REMIND_AFTER_MS)
}
