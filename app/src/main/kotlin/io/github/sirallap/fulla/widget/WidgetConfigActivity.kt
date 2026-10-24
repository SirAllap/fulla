// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.sirallap.fulla.FullaApp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.widget.WidgetMode
import io.github.sirallap.fulla.ui.LanguageApplier
import io.github.sirallap.fulla.ui.components.BackHeader
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch

/**
 * What a widget shows, chosen when it is placed and again whenever the person
 * reconfigures it (long-press it on the home screen). Leaving without choosing
 * keeps what it showed, so a widget is never lost for want of a choice.
 */
class WidgetConfigActivity : ComponentActivity() {
    private val container get() = (application as FullaApp).container

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageApplier.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // Backing out still places the widget, with what it showed (what is left, for a new one).
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val choices = WidgetChoices(this)
        val current = choices.of(id)
        enableEdgeToEdge()
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings
            FullaTheme(mode = s?.theme ?: io.github.sirallap.fulla.ui.theme.ThemeMode.SYSTEM,
                palette = s?.palette ?: io.github.sirallap.fulla.core.design.MoneyPalette.DEFAULT,
                accent = s?.accent ?: io.github.sirallap.fulla.core.design.Accent.DEFAULT) {
                val c = FullaTheme.colors
                Column(Modifier.fillMaxSize().background(c.paper).statusBarsPadding()) {
                    BackHeader(stringResource(R.string.widget_choose), onBack = { finish() })
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.widget_choose_text), style = FullaType.secondary, color = c.inkMuted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        for (mode in WidgetMode.entries) {
                            val (title, text) = words(mode)
                            ListRow(stringResource(title), context = stringResource(text),
                                end = { if (mode == current) Icon(Icons.Outlined.Check, null, tint = c.accent) },
                                onClick = {
                                    choices.set(id, mode)
                                    lifecycleScope.launch { FullaWidget.refresh(applicationContext, container); finish() }
                                })
                        }
                    }
                }
            }
        }
    }

    private fun words(mode: WidgetMode): Pair<Int, Int> = when (mode) {
        WidgetMode.LEFT -> R.string.widget_mode_left to R.string.widget_mode_left_text
        WidgetMode.TODAY -> R.string.widget_mode_today to R.string.widget_mode_today_text
        WidgetMode.BUDGET -> R.string.widget_mode_budget to R.string.widget_mode_budget_text
        WidgetMode.ACCOUNTS -> R.string.widget_mode_accounts to R.string.widget_mode_accounts_text
        WidgetMode.RECENT -> R.string.widget_mode_recent to R.string.widget_mode_recent_text
        WidgetMode.GOAL -> R.string.widget_mode_goal to R.string.widget_mode_goal_text
    }
}
