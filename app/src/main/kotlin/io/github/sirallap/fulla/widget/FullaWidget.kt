// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import io.github.sirallap.fulla.AppContainer
import io.github.sirallap.fulla.FullaApp
import io.github.sirallap.fulla.MainActivity
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.design.BaseColors
import io.github.sirallap.fulla.core.widget.WidgetFigures
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.theme.ThemeMode
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime

/** The home-screen widget's receiver: the launcher asks it to draw, and so does the alarm set for midnight. */
class FullaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redraw(context)

    private fun redraw(context: Context) {
        val pending = goAsync()
        val container = (context.applicationContext as FullaApp).container
        container.scope.launch {
            try { FullaWidget.refresh(context, container) } finally { pending.finish() }
        }
    }
}

/**
 * The widget: the round Add button when small; beside it, when wider, what is
 * left of the period, per day, and what was spent. Its figures are core's
 * ([WidgetFigures], the overview's own), drawn and never worked out here.
 * Amounts are hidden when the person asks in Settings (a home screen is seen by
 * whoever holds the phone); the app's lock alone does not hide them, or a locked
 * app would leave the widget with nothing to say. Works for every household,
 * phone-only ones included: it reads what the phone holds and needs no network.
 */
object FullaWidget {
    const val ACTION_ADD = "io.github.sirallap.fulla.ADD"
    const val ACTION_OVERVIEW = "io.github.sirallap.fulla.OVERVIEW"
    const val EXTRA_KIND = "kind"
    const val KIND_INCOME = "income"

    /** Wider than this (dp), the figures are shown beside the button. */
    private const val FIGURES_MIN_WIDTH = 180f

    /** Redraws whenever what it shows changes: the household's rows, the active household or the settings. */
    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(context: Context, container: AppContainer) {
        container.scope.launch {
            container.ledger.active.flatMapLatest { h -> if (h == null) flowOf(emptyList()) else container.ledger.transactions(h.id) }
                .combine(container.settings.settings) { rows, settings -> rows to settings }
                .debounce(500)
                .collect { refresh(context, container) }
        }
    }

    suspend fun refresh(context: Context, container: AppContainer) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, FullaWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val content = content(context, container)
        for (id in ids) manager.updateAppWidget(id, views(context, content, manager.getAppWidgetOptions(id)))
        scheduleMidnight(context, ids)
    }

    /** What the widget says, read once for every widget on the screen. */
    private class Content(
        val label: String, val amount: String?, val detail: String?, val amountColor: Int,
        val paper: Int, val ink: Int, val inkMuted: Int, val accent: Int, val onAccent: Int,
    )

    private suspend fun content(context: Context, container: AppContainer): Content {
        val settings = container.settings.current()
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (settings.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> night }
        val colors = BaseColors.of(dark, settings.accent)
        val res = context.resources
        val view = settings.activeHouseholdId?.let { id ->
            container.ledger.household(id)?.let { HouseholdView(it, container.ledger.transactions(id).first(), res.configuration.locales[0].language) }
        }
        val t = describe(res, view, hidden = settings.widgetHideAmounts, today = LocalDate.now())
        return Content(t.label, t.amount, t.detail, (if (t.over) settings.palette.of(dark).outText else colors.ink).toInt(),
            colors.paper.toInt(), colors.ink.toInt(), colors.inkMuted.toInt(), colors.accent.toInt(), colors.onAccent.toInt())
    }

    /** The widget's words: no household yet, amounts hidden, over, left (and per day), or only what was spent. */
    internal class Texts(val label: String, val amount: String?, val detail: String?, val over: Boolean = false)

    internal fun describe(res: Resources, view: HouseholdView?, hidden: Boolean, today: LocalDate): Texts {
        if (view == null) return Texts(res.getString(R.string.app_name), null, res.getString(R.string.widget_start))
        if (hidden) return Texts(view.config.household.name, "•••", res.getString(R.string.widget_hidden))
        val f = view.formats
        val period = f.currentPeriod(today)
        val w = WidgetFigures.of(view.analytics, view.active, period, today, view.deletedIds)
        val spent = res.getString(R.string.widget_spent, f.money(w.spentMinor))
        val over = w.overMinor
        val left = w.leftMinor
        val days = w.daysToGo ?: 0
        return when {
            over != null -> Texts(res.getString(R.string.widget_over), f.money(over), spent, over = true)
            left != null -> Texts(res.getString(R.string.widget_left), f.money(left),
                listOfNotNull(w.perDayMinor?.let { res.getQuantityString(R.plurals.widget_per_day, days, f.money(it), days) }, spent)
                    .joinToString("\n"))
            else -> Texts(res.getString(R.string.widget_spent_label), f.money(w.spentMinor), f.period(period))
        }
    }

    private fun views(context: Context, c: Content, options: Bundle): RemoteViews {
        val small = small(context, c)
        val wide = wide(context, c)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(mapOf(SizeF(40f, 40f) to small, SizeF(FIGURES_MIN_WIDTH, 40f) to wide))
        } else {
            if (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) >= FIGURES_MIN_WIDTH) wide else small
        }
    }

    private fun small(context: Context, c: Content) = RemoteViews(context.packageName, R.layout.widget_add).apply { common(context, c) }

    private fun wide(context: Context, c: Content) = RemoteViews(context.packageName, R.layout.widget_figures).apply {
        common(context, c)
        setTextViewText(R.id.widget_label, c.label)
        setTextColor(R.id.widget_label, c.inkMuted)
        setTextViewText(R.id.widget_amount, c.amount ?: "")
        setViewVisibility(R.id.widget_amount, if (c.amount == null) android.view.View.GONE else android.view.View.VISIBLE)
        setTextColor(R.id.widget_amount, c.amountColor)
        setTextViewText(R.id.widget_detail, c.detail ?: "")
        setTextColor(R.id.widget_detail, c.inkMuted)
        setOnClickPendingIntent(R.id.widget_figures, open(context, ACTION_OVERVIEW, null, 2))
    }

    private fun RemoteViews.common(context: Context, c: Content) {
        setInt(R.id.widget_bg, "setColorFilter", c.paper)
        setInt(R.id.widget_add_circle, "setColorFilter", c.accent)
        setInt(R.id.widget_add_icon, "setColorFilter", c.onAccent)
        setOnClickPendingIntent(R.id.widget_add_button, open(context, ACTION_ADD, null, 1))
        setOnClickPendingIntent(R.id.widget_root, open(context, ACTION_OVERVIEW, null, 2))
    }

    /** Opens the app on Add (an expense, or an income with [kind]) or on the overview. */
    fun intent(context: Context, action: String, kind: String?): Intent = Intent(context, MainActivity::class.java)
        .setAction(action)
        .apply { if (kind != null) putExtra(EXTRA_KIND, kind) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun open(context: Context, action: String, kind: String?, code: Int): PendingIntent =
        PendingIntent.getActivity(context, code, intent(context, action, kind), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The day moves on by itself: a redraw just after midnight, inexact, which needs no permission. */
    private fun scheduleMidnight(context: Context, ids: IntArray) {
        val now = ZonedDateTime.now()
        val at = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(1).toInstant().toEpochMilli()
        val update = Intent(context, FullaWidgetProvider::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        val pending = PendingIntent.getBroadcast(context, 0, update, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, at, pending)
    }
}
