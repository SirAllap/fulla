// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class InstallOutcome {
    data object Success : InstallOutcome()
    data class Failure(val message: String?) : InstallOutcome()
}

/**
 * Where [InstallStatusReceiver] puts the result of a session it did not start
 * itself (the receiver is a plain manifest component with nowhere else to put
 * it), for whichever screen is open to pick up.
 */
object InstallStatus {
    private val _events = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 1)
    val events: SharedFlow<InstallOutcome> = _events.asSharedFlow()

    internal fun emit(outcome: InstallOutcome) {
        _events.tryEmit(outcome)
    }
}

/** Registered in the manifest; receives the [PendingIntent] callback a session's commit() fires. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallStatus.emit(InstallOutcome.Success)
            else -> InstallStatus.emit(InstallOutcome.Failure(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }
}
