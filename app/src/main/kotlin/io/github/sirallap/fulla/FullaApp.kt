// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla

import android.app.Application
import androidx.work.Configuration
import io.github.sirallap.fulla.data.sync.RecurringScheduler
import io.github.sirallap.fulla.data.sync.SyncScheduler
import io.github.sirallap.fulla.widget.FullaWidget

class FullaApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedulePeriodic(this)
        RecurringScheduler.schedulePeriodic(this)
        // The widget follows the household's rows wherever they change: a save, a sync, a fixed cost written by the worker.
        if (FullaWidget.placed(this)) FullaWidget.observe(this, container)
        publishShortcuts()
    }

    /** Long-pressing the app's icon: add an expense or an income straight away. Set on every start, so they follow the language. */
    private fun publishShortcuts() {
        val manager = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
        fun shortcut(id: String, label: Int, icon: Int, kind: String?) = android.content.pm.ShortcutInfo.Builder(this, id)
            .setShortLabel(getString(label))
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, icon))
            .setIntent(FullaWidget.intent(this, FullaWidget.ACTION_ADD, kind))
            .build()
        runCatching {
            manager.dynamicShortcuts = listOf(
                shortcut("add_expense", R.string.shortcut_add_expense, R.drawable.ic_shortcut_add, null),
                shortcut("add_income", R.string.shortcut_add_income, R.drawable.ic_shortcut_income, FullaWidget.KIND_INCOME),
            )
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
