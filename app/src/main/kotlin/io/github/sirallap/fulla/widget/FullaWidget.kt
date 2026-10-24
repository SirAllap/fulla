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
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.widget.WidgetData
import io.github.sirallap.fulla.core.widget.WidgetFigures
import io.github.sirallap.fulla.core.widget.WidgetMode
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

    override fun onDeleted(context: Context, ids: IntArray) = WidgetChoices(context).forget(ids)

    /** The first widget placed: from now on it follows the rows (FullaApp starts this itself while one is placed). */
    override fun onEnabled(context: Context) = FullaWidget.observe(context, (context.applicationContext as FullaApp).container)

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

    private var observing = false

    /** Whether any widget of Fulla is on the home screen. */
    fun placed(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, FullaWidgetProvider::class.java)).isNotEmpty()

    /**
     * Redraws whenever what it shows changes: the household's rows, the active household or the settings. Started
     * only once a widget is placed, so a phone without one never opens the database for it; once per process.
     */
    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Synchronized
    fun observe(context: Context, container: AppContainer) {
        if (observing) return
        observing = true
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
        val choices = WidgetChoices(context)
        val made = mutableMapOf<WidgetMode, Content>()
        for (id in ids) {
            val content = made.getOrPut(choices.of(id)) { content(context, container, choices.of(id)) }
            manager.updateAppWidget(id, views(context, content, manager.getAppWidgetOptions(id)))
        }
        scheduleMidnight(context, ids)
    }

    /** What the widget says, read once for every widget on the screen. */
    private class Content(
        val label: String, val amount: String?, val detail: String?, val detailLines: Int, val amountColor: Int,
        val paper: Int, val ink: Int, val inkMuted: Int, val accent: Int, val onAccent: Int,
    )

    private suspend fun content(context: Context, container: AppContainer, mode: WidgetMode): Content {
        val settings = container.settings.current()
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (settings.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> night }
        val colors = BaseColors.of(dark, settings.accent)
        val res = context.resources
        val view = settings.activeHouseholdId?.let { id ->
            container.ledger.household(id)?.let { HouseholdView(it, container.ledger.transactions(id).first(), res.configuration.locales[0].language) }
        }
        val t = describe(res, view, hidden = settings.widgetHideAmounts, today = LocalDate.now(), mode = mode)
        return Content(t.label, t.amount, t.detail, t.detailLines, (if (t.over) settings.palette.of(dark).outText else colors.ink).toInt(),
            colors.paper.toInt(), colors.ink.toInt(), colors.inkMuted.toInt(), colors.accent.toInt(), colors.onAccent.toInt())
    }

    /** The widget's words: no household yet, amounts hidden, or what [mode] shows. */
    internal class Texts(val label: String, val amount: String?, val detail: String?, val over: Boolean = false, val detailLines: Int = 2)

    internal fun describe(res: Resources, view: HouseholdView?, hidden: Boolean, today: LocalDate, mode: WidgetMode = WidgetMode.LEFT): Texts {
        if (view == null) return Texts(res.getString(R.string.app_name), null, res.getString(R.string.widget_start))
        if (hidden) return Texts(view.config.household.name, "•••", res.getString(R.string.widget_hidden))
        val f = view.formats
        val period = f.currentPeriod(today)
        return when (mode) {
            WidgetMode.LEFT -> left(res, view, period, today)
            WidgetMode.TODAY -> {
                val t = WidgetData.today(view.analytics, view.active, period, today, view.deletedIds)
                val perDay = t.perDayMinor
                Texts(res.getString(R.string.widget_today), f.money(t.spentMinor),
                    if (perDay != null) res.getString(R.string.widget_today_can, f.money(perDay)) else f.day(today), over = t.over)
            }
            WidgetMode.BUDGET -> {
                val b = WidgetData.budget(view.config, view.analytics, view.active, period)
                when {
                    b == null -> Texts(res.getString(R.string.budget_of_period), null, res.getString(R.string.widget_budget_none))
                    b.overMinor > 0 -> Texts(res.getString(R.string.widget_budget_over), f.money(b.overMinor),
                        res.getString(R.string.widget_of, f.money(b.limitMinor)), over = true)
                    else -> Texts(res.getString(R.string.widget_budget_left), f.money(b.leftMinor), res.getString(R.string.widget_of, f.money(b.limitMinor)))
                }
            }
            WidgetMode.ACCOUNTS -> {
                val total = WidgetData.accounts(view.config, view.analytics, view.active, today)
                if (total == null) Texts(res.getString(R.string.accounts_total), null, res.getString(R.string.widget_accounts_none))
                else Texts(res.getString(R.string.accounts_total), f.money(total), f.day(today), over = total < 0)
            }
            WidgetMode.RECENT -> {
                val rows = WidgetData.recent(view.active, today)
                if (rows.isEmpty()) Texts(res.getString(R.string.widget_recent), null, res.getString(R.string.widget_recent_none))
                else Texts(res.getString(R.string.widget_recent), null, rows.joinToString("\n") { t ->
                    val name = when (t.kind) {
                        TransactionKind.TRANSFER -> res.getString(R.string.transfer_between, view.accountName(t.accountId) ?: "?", view.accountName(t.toAccountId) ?: "?")
                        TransactionKind.SETTLEMENT -> res.getString(R.string.settlement_between, view.memberName(t.paidByMemberId), view.memberName(t.toMemberId))
                        else -> t.note.ifBlank { view.categoryName(t.categoryId) ?: res.getString(R.string.uncategorized) }
                    }
                    val signed = t.kind == TransactionKind.INCOME || t.kind == TransactionKind.REFUND
                    name + "  " + f.money(t.amountMinor, signed = signed)
                }, detailLines = 3)
            }
            WidgetMode.GOAL -> {
                val found = WidgetData.goal(view.config, view.analytics, view.active, today)
                if (found == null) Texts(res.getString(R.string.settings_goals), null, res.getString(R.string.widget_goal_none))
                else {
                    val (goal, p) = found
                    Texts(goal.name, f.money(p.savedMinor), when {
                        p.reached -> res.getString(R.string.goal_reached)
                        else -> res.getString(R.string.widget_of, f.money(goal.targetMinor)) + " · " + res.getString(R.string.goal_left, f.money(p.leftMinor))
                    })
                }
            }
        }
    }

    private fun left(res: Resources, view: HouseholdView, period: java.time.YearMonth, today: LocalDate): Texts {
        val f = view.formats
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
        setInt(R.id.widget_detail, "setMaxLines", c.detailLines)
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

/** What each widget on the home screen was set to show, by its id. The phone's own, like every setting of the widget. */
class WidgetChoices(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun of(id: Int): WidgetMode = WidgetMode.of(prefs.getString("mode_$id", null))

    fun set(id: Int, mode: WidgetMode) = prefs.edit().putString("mode_$id", mode.key).apply()

    fun forget(ids: IntArray) = prefs.edit().apply { ids.forEach { remove("mode_$it") } }.apply()
}
