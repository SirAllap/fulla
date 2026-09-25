// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla

import android.app.Application
import androidx.work.Configuration
import io.github.sirallap.fulla.data.sync.SyncScheduler

class FullaApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedulePeriodic(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
