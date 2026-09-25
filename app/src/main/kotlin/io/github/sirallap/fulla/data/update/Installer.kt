// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.SignatureCheck
import java.io.File
import java.security.MessageDigest

/**
 * Installs a downloaded APK through Android's own installer: a
 * [PackageInstaller] session, committed with no confirmation screen where the
 * platform allows it (API 31+, [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED]),
 * and [InstallStatusReceiver] carries the result back.
 *
 * A matching checksum (see [io.github.sirallap.fulla.client.remote.UpdateCheck])
 * only proves the download was not corrupted or swapped in transit; it says
 * nothing about who signed the APK. Before handing it to the installer,
 * [install] also checks that its signing certificate is the same one already
 * on the phone — [SignatureCheck] holds the (testable) comparison, this class
 * only reads the certificates, which needs [PackageManager] and cannot be
 * unit-tested outside Android.
 */
class Installer(private val context: Context) {

    /** False the first time: the phone has to be told this app may install others. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Sends the person to grant that, for this app specifically. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Starts installing [apk]. The result arrives later, as a broadcast to
     * [InstallStatusReceiver]. Throws [FullaError] with
     * [FullaError.SIGNATURE_MISMATCH] and never opens an install session when
     * [apk]'s signing certificate is not the one already installed.
     */
    fun install(apk: File) {
        if (!SignatureCheck.matches(installedSigners(), apkSigners(apk))) {
            throw FullaError(FullaError.SIGNATURE_MISMATCH, "The downloaded update is not signed by the same key as this app.")
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("fulla_update", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val statusIntent = Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
            session.commit(pending.intentSender)
        }
    }

    // GET_SIGNING_CERTIFICATES needs API 28+; minSdk is 26, so older phones
    // fall back to the deprecated GET_SIGNATURES, which every API level
    // supports. Fulla's own key is never rotated (a single self-signed
    // certificate), so the older, chain-unaware API is exact for it.
    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** SHA-256 digests (lowercase hex) of the certificates this app is currently installed with. */
    private fun installedSigners(): Set<String> =
        signerDigests(context.packageManager.getPackageInfo(context.packageName, signingFlag()))

    /** SHA-256 digests (lowercase hex) of the certificates the not-yet-installed [apk] is signed with. */
    private fun apkSigners(apk: File): Set<String> {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, signingFlag()) ?: return emptySet()
        return signerDigests(info)
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val md = MessageDigest.getInstance("SHA-256")
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        }
        return certs.orEmpty().map { cert -> md.digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "io.github.sirallap.fulla.INSTALL_STATUS"
    }
}
