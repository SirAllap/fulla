// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

/**
 * Whether a downloaded APK is signed by the same key as the app already
 * installed, given each side's signing certificates as SHA-256 hex digests.
 *
 * Reading the certificates out of an APK and out of the installed package is
 * Android's `PackageManager`, so it lives in the app module; this is the pure
 * part, the one worth testing without a device: an APK downloaded from
 * GitHub, even one whose checksum matched, must never install over Fulla
 * unless it carries the exact signer(s) already on the phone.
 */
object SignatureCheck {
    /**
     * True only when both sides have at least one signer and the sets are
     * identical. An empty set on either side (a certificate Android could
     * not read) is never a match — that is a reason to refuse, not to fall
     * back to "no signers to disagree on".
     */
    fun matches(installed: Set<String>, downloaded: Set<String>): Boolean =
        installed.isNotEmpty() && downloaded.isNotEmpty() && installed == downloaded
}
