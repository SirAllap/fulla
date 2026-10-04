// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.analytics.DetectedRecurring
import io.github.sirallap.fulla.core.analytics.FixedStatus
import io.github.sirallap.fulla.core.analytics.PeriodReport
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import io.github.sirallap.fulla.core.roles.Permissions
import io.github.sirallap.fulla.core.schema.CustomField
import io.github.sirallap.fulla.core.schema.FieldType
import io.github.sirallap.fulla.core.schema.SchemaEngine
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.time.ChronoUnit
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.math.roundToInt

/** A value of a custom field as a person reads it in a list, or null when there is nothing to show. */
internal fun fieldText(view: HouseholdView, field: CustomField, value: Any?, format: Format): String? {
    val label = field.label(I18n.language)
    return when {
        value == null -> null
        field.type == FieldType.BOOLEAN -> if (value == true) label else null
        field.type == FieldType.MEMBER -> view.config.member(value as? String)?.displayName
        field.type == FieldType.MONEY -> (value as? Number)?.toLong()?.let { "$label ${format.money(it)}" }
        field.type == FieldType.DATE -> (value as? String)?.let { runCatching { format.day(LocalDate.parse(it)) }.getOrNull() }
        value is List<*> -> value.joinToString(", ")
        else -> value.toString()
    }
}

/**
 * The longer view of one period, any period: where the money went (vials of the jar's liquid), how the period went in
 * figures, which weekday the everyday money goes, the largest expenses, what is bought again and again, what moved
 * against the usual, who paid, and how each period compares.
 */
object InsightsScreen {
    private var chosen: YearMonth? = null
    private val made = mutableSetOf<String>()

    private class Vial(val label: String, val icon: String?, val level: Double, val share: Double, val description: String, val top: String? = null, val income: Boolean = false)

    fun build(view: HouseholdView, format: Format): HTMLElement {
        val a = view.analytics
        val today = LocalDate.now()
        val current = view.currentPeriod(today)
        val period = chosen ?: current
        val report = a.report(view.active, period, today, view.config.trips)
        val forecast = if (period == current) runCatching { a.forecast(view.active, period, today, view.deletedIds) }.getOrNull() else null
        val series = a.series(view.active, period, 12).reversed().filter { it.incomeMinor != 0L || it.expenseMinor != 0L }
        var unit = 1L; repeat(format.currency.minorUnits) { unit *= 10 }
        val trends = a.trends(view.active, period, minimumMinor = 10 * unit, today = today)
        val repeating = a.detectedRecurring(view.active, today)
        val byMember = a.byMember(view.active, period).filter { it.paidMinor != 0L || it.shareMinor != 0L }
        val noSpend = a.noSpendDays(view.active, period, today)
        val top = a.topCategories(view.active, period)
        val dimensions = SchemaEngine.dimensions(view.config.fields)
        val canEdit = view.config.me()?.let { Permissions.canEditStructure(it) } == true

        return el("main", "screen no-tabs insights") {
            backHeader(t("insights")) { App.closeInsights() }
            div("period") {
                appendChild(iconButton("chevron_left", t("previous_period"), "accent") { chosen = period.minusMonths(1); App.render() })
                span("t-amount label") { text(format.period(period)) }
                appendChild(iconButton("chevron_right", t("next_period"), "accent") { chosen = if (period.plusMonths(1) >= current) null else period.plusMonths(1); App.render() }
                    .also { if (period >= current) it.setAttribute("disabled", "") })
            }
            section(t("period_summary"), first = true)
            figureTiles(format, period, view, report, noSpend)
            if (forecast != null) {
                // The same forecast the overview shows, on the jar's liquid.
                val f = forecast
                if (f.known && !f.early) {
                    section(t("forecast"))
                    note(t("forecast_day", f.day, f.length))
                    val spendEnd = f.spentEndMinor!!
                    tiles(listOf(
                        Tile(t("forecast_spend"), "≈ " + format.money(spendEnd), t("forecast_between", format.money(f.spentEndLowMinor!!), format.money(f.spentEndHighMinor!!)), if (spendEnd > 0) (f.spentMinor.toDouble() / spendEnd).coerceIn(0.0, 1.0) else null, "out", phase = 0.4),
                        f.keptMinor?.let { k -> Tile(t("forecast_kept"), "≈ " + format.money(k), t("forecast_between", format.money(f.keptLowMinor!!), format.money(f.keptHighMinor!!)), (k.toDouble() / f.totalIncomeMinor).coerceIn(0.0, 1.0), "in", if (k < 0) "out" else "in", 1.6) }
                            ?: Tile(t("forecast_kept"), "—", t("forecast_add_income"), null, "in"),
                    ))
                } else if (f.known) { section(t("forecast")); note(t("forecast_too_early", f.day, f.length)) }
                if (!f.waiting) {
                    // The sum behind it, in view: only the everyday spending still to come is a guess.
                    section(t("forecast_how"))
                    div("rows") {
                        listRow(t("forecast_income"), context = if (f.expectedIncomeMinor > 0) t("forecast_income_context", format.money(f.incomeMinor), format.money(f.expectedIncomeMinor)) else null, end = { amountText(format.money(f.totalIncomeMinor), "in") })
                        listRow(t("forecast_spent_so_far"), end = { amountText(format.money(-f.spentMinor)) })
                        val pending = f.fixed.filter { it.status == io.github.sirallap.fulla.core.analytics.FixedStatus.PENDING }
                        listRow(t("forecast_fixed_to_come"), context = pending.take(4).joinToString(" · ") { it.name + " " + format.day(it.date) }.ifBlank { null }, end = { amountText(format.money(-f.fixedToComeMinor)) })
                        val free = f.leftToSpendMinor
                        if (free != null) listRow(t("forecast_free"), context = t("forecast_free_text"), end = { amountText(format.money(free), if (free < 0) "neg" else "in") })
                        val rest = f.everydayRestMinor
                        if (rest != null && !f.early) {
                            listRow(t("forecast_everyday"), context = t("forecast_between", format.money(f.everydayLowMinor!!), format.money(f.everydayHighMinor!!)), end = { amountText(format.money(-rest)) })
                            val kept = f.keptMinor
                            listRow(t(if (kept != null) "forecast_kept" else "forecast_spend"), divider = false, end = { amountText("≈ " + format.money(kept ?: f.spentEndMinor!!), if (kept != null && kept < 0) "neg" else "") })
                        }
                    }
                    if (f.leftToSpendMinor == null) note(t("forecast_add_income"))
                    note(t(if (f.ownPace) "forecast_note_own" else "forecast_note"))
                }
                if (f.fixed.isNotEmpty()) {
                    section(t("fixed_costs"))
                    val total = f.fixedTotalMinor; val left = f.leftToSpendMinor; val perDay = f.perDayMinor
                    val row1 = mutableListOf(Tile(t("fixed_total_tile"), format.money(total), t("fixed_progress", format.money(f.fixedPaidMinor), format.money(f.fixedToComeMinor)), if (total > 0) f.fixedPaidMinor.toDouble() / total else null, "out", phase = 2.2, onClick = { App.closeInsights(); App.openSettings(SettingsPage.RECURRING) }))
                    if (left != null) row1 += Tile(t("left_to_spend"), format.money(left), t("left_to_spend_text"), (left.toDouble() / f.totalIncomeMinor).coerceIn(0.0, 1.0), "in", if (left < 0) "out" else "in", 3.8)
                    tiles(row1)
                    if (left != null && perDay != null) tiles(listOf(Tile(t("per_day"), format.money(perDay), (f.everydayPerDayMinor?.let { t("per_day_text_pace", f.daysToGo, format.money(it)) } ?: t("per_day_text", f.daysToGo)), null, "in", if (perDay < 0) "out" else "in", 4.6)))
                    if (left == null) note(t("forecast_add_income"))
                }
            }
            if (top.isNotEmpty()) {
                val total = top.sumOf { it.amountMinor }.toDouble(); val max = top.maxOf { it.amountMinor }.toDouble()
                section(t("where_it_went"))
                vials(top.map { s ->
                    val cat = s.key?.let { view.config.category(it) }
                    val name = if (s.key == null) t("other_categories") else cat?.name ?: t("uncategorized")
                    Vial(name, if (s.key == null) null else cat?.icon ?: "label", s.amountMinor / max, s.amountMinor / total, "$name, ${format.money(s.amountMinor)}")
                })
            }
            if (report.weekdays.any { it > 0 }) {
                val total = report.weekdays.filter { it > 0 }.sum().toDouble(); val max = report.weekdays.max().toDouble()
                section(t("by_weekday"))
                vials(report.weekdays.mapIndexed { i, amount ->
                    val day = (js("new Date(2024, 0, i + 1)").toLocaleDateString(I18n.language, js("({ weekday: 'short' })")) as String).replaceFirstChar { it.titlecase() }.trimEnd('.')
                    val positive = amount.coerceAtLeast(0)
                    Vial(day, null, positive / max, positive / total, "$day, ${format.money(amount)}")
                })
            }
            if (report.biggest.isNotEmpty()) {
                section(t("biggest_expenses"))
                val largest = report.biggest.first().amountMinor.toDouble()
                for (tx in report.biggest) {
                    val category = view.config.category(tx.categoryId)?.name
                    bar(tx.note.ifBlank { category ?: t("uncategorized") }, format.money(tx.amountMinor), tx.amountMinor / largest,
                        listOfNotNull(category.takeIf { tx.note.isNotBlank() }, format.day(tx.date)).joinToString(" · "), tx.amountMinor % 7 * 0.9)
                }
            }
            if (report.places.isNotEmpty()) {
                section(t("repeated_most"))
                val largest = report.places.first().totalMinor.toDouble()
                for (p in report.places) bar(p.name, format.money(p.totalMinor), p.totalMinor / largest, t("times", p.count), p.count * 1.3)
            }
            if (trends.isNotEmpty()) {
                section(t("against_usual"))
                val largest = trends.maxOf { maxOf(it.currentMinor, it.averageMinor) }.toDouble()
                for (tr in trends) {
                    val up = tr.currentMinor > tr.averageMinor
                    bar(view.config.category(tr.categoryId)?.name ?: t("uncategorized"), format.money(tr.currentMinor), tr.currentMinor / largest,
                        t(if (report.partial) "usually_so_far" else "usually", format.money(tr.averageMinor)) + " · " + t(if (up) "percent_more" else "percent_less", abs(tr.change * 100).roundToInt()), tr.currentMinor % 5 * 1.1)
                }
            }
            if (byMember.size > 1 && !SharedPot.isShared(view.config.household)) {
                section(t("who_paid_period"))
                val largest = byMember.maxOf { it.paidMinor }.coerceAtLeast(1).toDouble()
                for (m in byMember) bar(view.config.member(m.memberId)?.displayName ?: "?", format.money(m.paidMinor), m.paidMinor / largest, t("their_share", format.money(m.shareMinor)), 0.0)
            }
            for (field in dimensions) {
                val totals = a.groupBy(view.active, period, field.key).filterKeys { it.isNotEmpty() }.filterValues { it > 0L }
                if (totals.isEmpty()) continue
                section(field.label(I18n.language))
                val largest = totals.values.max().toDouble()
                for ((value, amount) in totals.entries.sortedByDescending { it.value })
                    bar(fieldText(view, field, if (field.type == FieldType.BOOLEAN) value.toBooleanStrictOrNull() else value, format) ?: value, format.money(amount), amount / largest, null, 0.0)
            }
            if (series.size > 1) {
                val shown = series.take(6).reversed()
                val max = shown.maxOf { it.expenseMinor }.coerceAtLeast(1).toDouble()
                section(t("period_by_period"))
                vials(shown.map { s -> Vial(format.shortPeriod(s.period), null, s.expenseMinor.coerceAtLeast(0) / max, 0.0,
                    format.period(s.period) + ", " + t("in_out", format.money(s.incomeMinor), format.money(s.expenseMinor)), top = format.whole(s.expenseMinor)) })
            }
            if (series.isNotEmpty()) div("rows") {
                for (s in series.take(6)) listRow(format.period(s.period), context = t("in_out", format.money(s.incomeMinor), format.money(s.expenseMinor)),
                    end = { amountText(format.money(s.savingsMinor, signed = true), if (s.savingsMinor < 0) "" else "in") })
            }
            if (repeating.isNotEmpty()) {
                section(t("looks_recurring"))
                div("rows") {
                    for (r in repeating) {
                        val done = r.note in made
                        listRow(r.note, context = t("seen_in_months", r.months),
                            detail = if (done) t("now_recurring") else if (canEdit) t("tap_to_make_recurring") else t("last_seen", format.day(r.lastDate)),
                            end = { amountText(format.money(r.typicalMinor)) },
                            onClick = if (canEdit && !done) ({ makeRecurring(view, r) }) else null)
                    }
                }
            }
        }
    }

    /** A charge found repeating becomes a recurring item, from its latest occurrence. */
    private fun makeRecurring(view: HouseholdView, r: DetectedRecurring) {
        val latest = view.active.filter { it.note == r.note }.maxByOrNull { it.date } ?: return
        val rule = RecurringRule(randomUuid(), r.note,
            latest.copy(amountMinor = r.typicalMinor, recurrence = Recurrence.FIXED, importFingerprint = null, recurringRuleId = null, occurrenceDate = null),
            Schedule(Frequency.MONTHLY, byMonthDay = r.lastDate.dayOfMonth),
            // Starts after the last one seen, so nothing already written down is written again.
            r.lastDate.plusDays(1), autoCreate = true)
        App.launch { try { Ledger.upsert(Structure.RECURRING, Wire.recurring(rule)); made += r.note; App.render() } catch (e: Throwable) { App.toast(Remote.message(e)) } }
    }

    private class Tile(val title: String, val value: String, val context: String?, val level: Double?, val tone: String, val valueTone: String = "", val phase: Double = 0.0, val onClick: (() -> Unit)? = null)

    private fun HTMLElement.tiles(list: List<Tile>) {
        for (pair in list.chunked(2)) div("tiles-row") {
            for (tile in pair) child(if (tile.onClick != null) "button" else "div", "lq-tile") {
                if (tile.onClick != null) { attr("type", "button"); click(tile.onClick) }
                span("t-label") { text(tile.title) }
                div("value ${tile.valueTone}") { text(tile.value) }
                if (tile.context != null) span("ctx") { text(tile.context) }
                if (tile.level != null) Liquids.sheet(this, tile.level, tile.tone, vertical = true, phase = tile.phase)
            }
            if (pair.size == 1) span("lq-tile spacer") { }
        }
    }

    /** The period in figures, two glass tiles to a row, each filled to what it measures. A tile with nothing to say is left out. */
    private fun HTMLElement.figureTiles(format: Format, period: YearMonth, view: HouseholdView, report: PeriodReport, noSpend: Int) {
        val range = view.rule.daysOf(period)
        val length = (ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1).toInt().coerceAtLeast(1)
        val list = mutableListOf<Tile>()
        val previous = report.previousSpentMinor
        val vsPrevious = when {
            previous == null || previous <= 0 -> null
            report.spentMinor == previous -> t(if (report.partial) "vs_previous_same_sofar" else "vs_previous_same")
            else -> t((if (report.spentMinor > previous) "vs_previous_more" else "vs_previous_less") + (if (report.partial) "_sofar" else ""), abs((report.spentMinor - previous) * 100.0 / previous).roundToInt())
        }
        list += Tile(t("spent"), format.money(report.spentMinor), vsPrevious, if (report.incomeMinor > 0) report.spentMinor.toDouble() / report.incomeMinor else null, "out", phase = 0.2)
        report.savingsRate?.let { r -> list += Tile(t("savings"), "${(r * 100).roundToInt()} %", t(if (report.partial) "of_income_so_far" else "of_income", format.money(report.incomeMinor)), r.coerceIn(0.0, 1.0), "in", if (r < 0) "out" else "in", 1.4) }
        if (report.days > 0) list += Tile(t("daily_average"), format.money(report.dailyMinor), t("day_of", report.days, length), report.days.toDouble() / length, "out", phase = 2.1)
        if (report.count > 0) list += Tile(t("movements"), "${report.count}", t("movements_text", format.money(report.averageMinor)), null, "out")
        val both = report.fixedMinor + report.variableMinor
        if (both > 0) list += Tile(t("fixed_variable"), "${(report.fixedMinor * 100.0 / both).roundToInt()} %", t("fixed_variable_text", format.money(report.fixedMinor), format.money(report.variableMinor)), report.fixedMinor.toDouble() / both, "out", phase = 3.6)
        if (report.days > 0) list += Tile(t("no_spend_days"), "$noSpend", t("of_days", report.days), noSpend.toDouble() / report.days, "in", phase = 4.4)
        if (report.budgets > 0) list += Tile(t("budgets_over"), t("budgets_over_value", report.budgetsOver, report.budgets), null, report.budgetsOver.toDouble() / report.budgets, "out", if (report.budgetsOver > 0) "out" else "", 5.2)
        tiles(list)
    }

    private fun HTMLElement.bar(title: String, amount: String, fraction: Double, context: String?, phase: Double) {
        div("lq-bar") {
            div("text") { span("title") { text(title) }; if (context != null) span("ctx") { text(context) } }
            span("amount") { text(amount) }
            Liquids.sheet(this, fraction, "out", vertical = false, phase = phase)
        }
    }

    /** Where it went, as a row of vials filled with the jar's liquid: the name under each, its share above it. */
    private fun HTMLElement.vials(list: List<Vial>) {
        val icons = list.any { it.icon != null }
        div("vials") {
            list.forEachIndexed { index, v ->
                div("vial") {
                    attr("role", "img"); attr("aria-label", v.description)
                    span("t-label top") { text(v.top ?: "${(v.share * 100).roundToInt()} %") }
                    div("vial-glass") {
                        Liquids.paint(this) { ctx, w, h, t, _ ->
                            val vw = minOf(w * 0.72, 44.0); val left = (w - vw) / 2
                            ctx.beginPath(); ctx.roundRect(left, 0, vw, h, vw / 2)
                            ctx.save(); ctx.clip()
                            ctx.fillStyle = Liquids.css("--paper-high"); ctx.fillRect(left, 0, vw, h)
                            val level = h - h * (0.04 + 0.9 * v.level.coerceIn(0.0, 1.0)) * t
                            val n = 24
                            val wave = io.github.sirallap.fulla.core.design.Liquid.surface(n, (3.0 / h).toFloat(), index * 1.7f + 0.4f, 1.1f, meniscus = (4.0 / h).toFloat())
                            ctx.beginPath(); ctx.moveTo(left, h)
                            for (i in 0 until n) ctx.lineTo(left + vw * i / (n - 1), level + wave[i] * h)
                            ctx.lineTo(left + vw, h); ctx.closePath()
                            val tone = if (v.income) "in" else "out"
                            val g = ctx.createLinearGradient(0, minOf(level, h - 1), 0, h)
                            g.addColorStop(0, Liquids.css("--$tone-surface")); g.addColorStop(1, Liquids.css("--$tone-body"))
                            ctx.fillStyle = g; ctx.fill()
                            ctx.restore()
                            ctx.strokeStyle = Liquids.withAlpha(Liquids.css("--ink-muted"), 0.35); ctx.lineWidth = 1.5
                            ctx.beginPath(); ctx.roundRect(left, 0, vw, h, vw / 2); ctx.stroke()
                        }
                    }
                    if (v.icon != null) span("vial-icon") { categoryIcon(v.icon, 18) } else if (icons) span("vial-icon") { }
                    span("t-label name") { text(v.label) }
                }
            }
        }
    }
}
