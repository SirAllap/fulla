// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sirallap.fulla.FullaApp
import java.util.concurrent.TimeUnit

/**
 * Background sync. A local write asks for one soon; a periodic one catches
 * whatever the other phones did while this one was only reading. Both wait
 * for a network, and neither ever blocks a screen.
 */
object SyncScheduler {
    private const val NOW = "sync-now"
    private const val PERIODIC = "sync-periodic"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun requestSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setInitialDelay(2, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FullaApp).container
        return when (container.syncAll()) {
            SyncOutcome.DONE, SyncOutcome.NOTHING_TO_DO, SyncOutcome.NEEDS_SIGN_IN -> Result.success()
            SyncOutcome.RETRY -> if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }
}

enum class SyncOutcome { DONE, NOTHING_TO_DO, NEEDS_SIGN_IN, RETRY }
