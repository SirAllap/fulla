// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.core.analytics.Budgets
import io.github.sirallap.fulla.core.analytics.FixedItem
import io.github.sirallap.fulla.core.analytics.FixedStatus
import io.github.sirallap.fulla.core.analytics.PeriodForecast
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.rules.PeriodAnchors
import io.github.sirallap.fulla.core.schema.FieldType
import io.github.sirallap.fulla.core.schema.SchemaEngine
import io.github.sirallap.fulla.core.time.ChronoUnit
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth
import io.github.sirallap.fulla.core.trips.Trips
import kotlinx.browser.localStorage
import org.w3c.dom.HTMLElement

/** The period at a glance: the jar, then what it is made of. Only the jar is loud; everything under it is plain rows. */
object OverviewScreen {
    /** null: the period that contains today. */
    private var chosen: YearMonth? = null
    private var chargedOpen = false
    private var expanded: String? = null
    private var expandedSub: String? = null

    private fun notSalary(id: String): Set<String> = runCatching { localStorage.getItem("fulla.notSalary.$id")?.split(',')?.filter { it.isNotEmpty() }?.toSet() }.getOrNull().orEmpty()
    private fun addNotSalary(id: String, row: String) { runCatching { localStorage.setItem("fulla.notSalary.$id", (notSalary(id) + row).joinToString(",")) } }

    fun build(view: HouseholdView, format: Format): HTMLElement {
        val today = LocalDate.now()
        val current = view.currentPeriod(today)
        val period = chosen ?: current
        val config = view.config
        val summary = view.analytics.summary(view.active, period)
        val hero = view.analytics.hero(view.active, period)
        val categories = view.analytics.byCategory(view.active, period)
        val forecast = if (period == current) runCatching { view.analytics.forecast(view.active, period, today, view.deletedIds) }.getOrNull() else null
        val budgets = Budgets.forPeriod(config, period)
        val budgetSpend = view.analytics.budgetSpend(view.active, period, config.trips).associate { it.categoryId to it.amountMinor }
        val homeTrip = Trips.activeOn(config.trips, today)
            ?: config.trips.filter { !it.archived && it.startDate >= today && it.startDate <= today.plusDays(7) }.minByOrNull { it.startDate }

        return el("main", "screen overview") {
            tabHeader(config.household.name, insightsPill())
            InstallHint.card()?.let { appendChild(it) }
            div("period") {
                appendChild(iconButton("chevron_left", t("previous_period"), "accent") { chosen = period.minusMonths(1); App.render() })
                span("t-amount label") { text(format.period(period)) }
                appendChild(iconButton("chevron_right", t("next_period"), "accent") {
                    chosen = if (period.plusMonths(1) >= current) null else period.plusMonths(1); App.render()
                }.also { if (period >= current) it.setAttribute("disabled", "") })
            }
            format.periodRange(view, period)?.let { child("p", "muted center range") { text(it) } }
            appendChild(Jar.build(hero.incomeFraction, hero.expenseFraction, format.money(hero.savingsMinor), hero.savingsMinor,
                t("hero_description", format.period(period), format.money(summary.incomeMinor), format.money(summary.expenseMinor), format.money(summary.savingsMinor))))

            // Most likely this month's salary, written down without the mark.
            val candidate = if (period == current) PeriodAnchors.unmarkedSalary(view.active, notSalary(view.id)) else null
            if (candidate != null) div("rows") {
                listRow(t("salary_question"), start = { span("lead in") { ui("payments") } },
                    context = listOf(candidate.note.ifBlank { config.category(candidate.categoryId)?.name ?: "" }, format.money(candidate.amountMinor), format.day(candidate.date)).filter { it.isNotBlank() }.joinToString(" · "))
                chipRow {
                    chip(t("salary_question_yes"), true) { App.launch { Ledger.save(PeriodAnchors.mark(candidate, true)) } }
                    chip(t("salary_question_no"), false) { addNotSalary(view.id, candidate.id); App.render() }
                }
            }
            val waiting = if (period == current && candidate == null) view.rule.daysWaitingForSalary(today) else null
            if (waiting != null) div("rows") { listRow(t("salary_overdue"), context = t("salary_overdue_text", waiting), start = { span("lead warn") { ui("payments") } }) }
            if (backupDue(view)) div("rows") { listRow(t("backup_due"), context = t("backup_due_text"), start = { span("lead warn") { ui("save_alt") } }) { App.openSettings(SettingsPage.BACKUP) } }
            val accounts = config.accounts.filter { !it.archived }
            if (accounts.any { it.openingBalanceMinor != 0L }) div("rows") {
                val total = view.analytics.accountBalances(view.active, accounts, today).values.sum()
                listRow(t("accounts_total"), context = t("accounts_total_help"), detail = format.money(total), start = leadIcon("account_balance")) { App.go(Tab.BALANCES) }
            }

            div("figures") {
                figure(t("money_in"), format.money(summary.incomeMinor, signed = true), "in")
                figure(t("money_out"), format.money(summary.expenseMinor), "out")
                figure(t("saved"), summary.savingsRate?.let { "${(it * 100).toInt()} %" } ?: "—", "")
            }
            div("rows") {
                if (budgets.isNotEmpty()) {
                    val spent = budgetSpend.filterKeys { it in budgets }.values.sum()
                    val total = budgets.values.sum()
                    listRow(t("budget_of_period"), context = t("budget_left_of", format.money(total - spent), format.money(total)), end = { chevron() },
                        below = { progress(if (total > 0) spent.toDouble() / total else 0.0, spent > total) }) { App.openSettings(SettingsPage.BUDGETS) }
                }
                if (homeTrip != null) {
                    val totals = Trips.totals(homeTrip, view.active)
                    val budget = homeTrip.budgetMinor
                    val left = totals.leftMinor
                    listRow(homeTrip.name, start = leadIcon(tripKindIcon(homeTrip.kind)), end = { chevron() },
                        context = if (budget != null && left != null) t("trip_left", format.money(left), format.money(budget)) else format.money(totals.spentMinor),
                        below = if (budget != null) ({ progress(totals.spentMinor.toDouble() / budget, totals.overMinor > 0) }) else null) { App.openTrip(homeTrip.id) }
                }
            }
            if (forecast != null) {
                forecastSection(view, forecast, format)
                fixedCosts(view, forecast, format, today)
            }
            if (categories.isEmpty()) emptyState("opacity", t("empty_period_title"), t("empty_period_text"))
            else {
                section(t("where_it_went"))
                div("rows") {
                    for (row in categories) {
                        val cat = config.category(row.categoryId)
                        val budget = budgets[row.categoryId]
                        val forBudget = budgetSpend[row.categoryId] ?: 0L
                        val onTrips = row.amountMinor - forBudget
                        listRow(cat?.name ?: t("uncategorized"),
                            start = {
                                style.setProperty("--tile", "var(--cat-${(cat?.colorIndex ?: 0).mod(12)})")
                                span("glyph") { categoryIcon(cat?.icon ?: "label", 22) }
                            },
                            context = when {
                                budget != null && onTrips > 0 -> t("of_budget", format.money(budget)) + " · " + t("on_trips", format.money(onTrips))
                                budget != null -> t("of_budget", format.money(budget))
                                row.previousAverageMinor > 0 -> t("usually", format.money(row.previousAverageMinor))
                                else -> null
                            },
                            below = if (budget != null) ({ progress(forBudget.toDouble() / budget, forBudget > budget) }) else null,
                            end = { amountText(format.money(row.amountMinor)) }) { expanded = if (expanded == row.categoryId) null else row.categoryId; expandedSub = null; App.render() }
                        if (expanded == row.categoryId) breakdown(view, format, period, row.categoryId)
                    }
                }
            }
        }
    }

    private fun backupDue(view: HouseholdView): Boolean {
        val last = Ledger.lastBackupAt
        return !view.connected && last != null && view.rows.size >= 20 && view.active.any { io.github.sirallap.fulla.core.demo.DemoData.TAG !in it.tags } &&
            last < io.github.sirallap.fulla.core.time.Instant.now().toEpochMilli() - 30L * 24 * 3600 * 1000
    }

    private fun HTMLElement.insightsPill(): HTMLElement = el("button", "pill") {
        attr("type", "button"); attr("aria-label", t("insights"))
        ui("insights", 22); span("pill-label") { text(t("insights")) }
        click { App.openInsights() }
    }

    private fun HTMLElement.progress(fraction: Double, over: Boolean) {
        div(if (over) "progress over" else "progress") { div("") { style.width = "${(fraction * 100).coerceIn(0.0, 100.0)}%" } }
    }

    private fun HTMLElement.figure(label: String, value: String, tone: String) = div("figure $tone") {
        span("t-label") { text(label) }
        div("v") { text(value) }
    }

    /** What a category's spending was made of, one level at a time: its subcategories, then a field's values, then the rows. */
    private fun HTMLElement.breakdown(view: HouseholdView, format: Format, period: YearMonth, categoryId: String) {
        val subs = view.analytics.bySubcategory(view.active, period, categoryId)
        if (subs.isEmpty()) { fieldBreakdown(view, format, period, categoryId, 1); return }
        for (s in subs) {
            val id = s.key ?: continue
            val name = if (id == categoryId) t("subcategory_general") else view.config.category(id)?.name ?: ""
            listRow(name, end = { amountText(format.money(s.amountMinor), "muted") }) { expandedSub = if (expandedSub == id) null else id; App.render() }.classList.add("indent1")
            if (expandedSub == id) fieldBreakdown(view, format, period, id, 2)
        }
    }

    private fun HTMLElement.fieldBreakdown(view: HouseholdView, format: Format, period: YearMonth, categoryId: String, level: Int) {
        val field = SchemaEngine.fieldsForCategory(view.config.fields, TransactionKind.EXPENSE, view.config.category(categoryId)).firstOrNull { it.type == FieldType.SELECT }
        val values = field?.let { view.analytics.byFieldValue(view.active, period, categoryId, it.key) }.orEmpty()
        if (field == null || values.none { it.key != null }) {
            rows(view, format, view.analytics.spending(view.active, period, categoryId, withSubcategories = false), level); return
        }
        for (v in values) {
            val id = v.key ?: "\u0000"
            listRow(v.key ?: t("field_value_none"), end = { amountText(format.money(v.amountMinor), "muted") }) { expandedSub = if (expandedSub == "$categoryId|$id") null else "$categoryId|$id"; App.render() }.classList.add("indent$level")
            if (expandedSub == "$categoryId|$id") rows(view, format, view.analytics.spending(view.active, period, categoryId, withSubcategories = false, field = field.key to v.key), level + 1)
        }
    }

    private fun HTMLElement.rows(view: HouseholdView, format: Format, list: List<io.github.sirallap.fulla.core.model.Transaction>, level: Int) {
        for (tx in list) listRow(tx.note.ifBlank { view.config.category(tx.categoryId)?.name ?: "" }, context = format.day(tx.date),
            end = { amountText(format.money(if (tx.kind == TransactionKind.REFUND) -tx.amountMinor else tx.amountMinor), "muted") }) { App.editing = tx.id; App.go(Tab.ADD) }
            .classList.add("indent${minOf(level, 2)}")
    }

    // ── forecast ─────────────────────────────────────────────────────────────

    private fun tile(parent: HTMLElement, title: String, value: String, context: String?, level: Double?, tone: String, valueTone: String = "", phase: Double = 0.0, onClick: (() -> Unit)? = null) {
        parent.child(if (onClick != null) "button" else "div", "lq-tile") {
            if (onClick != null) { attr("type", "button"); click(onClick) }
            span("t-label") { text(title) }
            div("value $valueTone") { text(value) }
            if (context != null) span("ctx") { text(context) }
            if (level != null) Liquids.sheet(this, level, tone, vertical = true, phase = phase)
        }
    }

    private fun HTMLElement.forecastSection(view: HouseholdView, f: PeriodForecast, format: Format) {
        section(t("forecast"))
        when {
            f.known && f.early -> note(t("forecast_too_early", f.day, f.length))
            f.known -> {
                note(t("forecast_day", f.day, f.length))
                val spendEnd = f.spentEndMinor!!
                div("tiles-row") {
                    tile(this, t("forecast_spend"), "≈ " + format.money(spendEnd), t("forecast_between", format.money(f.spentEndLowMinor!!), format.money(f.spentEndHighMinor!!)),
                        if (spendEnd > 0) (f.spentMinor.toDouble() / spendEnd).coerceIn(0.0, 1.0) else null, "out", phase = 0.4) { explain(view, f, format) }
                    val kept = f.keptMinor
                    if (kept != null) tile(this, t("forecast_kept"), "≈ " + format.money(kept), t("forecast_between", format.money(f.keptLowMinor!!), format.money(f.keptHighMinor!!)),
                        (kept.toDouble() / f.totalIncomeMinor).coerceIn(0.0, 1.0), "in", if (kept < 0) "out" else "in", 1.6) { explain(view, f, format) }
                    else tile(this, t("forecast_kept"), "—", t("forecast_add_income"), null, "in") { explain(view, f, format) }
                }
            }
            f.waiting -> div("rows") { listRow(t("forecast_waiting"), context = t("forecast_waiting_text"), divider = false) }
            else -> child("button", "plain-btn pad muted") { attr("type", "button"); text(t("forecast_no_history")); click { explain(view, f, format) } }
        }
    }

    /** How the forecast is worked out, line by line, so every figure can be checked. */
    private fun explain(view: HouseholdView, f: PeriodForecast, format: Format) {
        sheet(t("forecast_how")) { _ ->
            div("rows") {
                listRow(t("forecast_income"), context = if (f.expectedIncomeMinor > 0) t("forecast_income_context", format.money(f.incomeMinor), format.money(f.expectedIncomeMinor)) else null,
                    end = { amountText(format.money(f.totalIncomeMinor), "in") })
                listRow(t("forecast_spent_so_far"), end = { amountText(format.money(-f.spentMinor)) })
                val pending = f.fixed.filter { it.status == FixedStatus.PENDING }
                listRow(t("forecast_fixed_to_come"), context = pending.take(4).joinToString(" · ") { it.name + " " + format.day(it.date) }.ifBlank { null }, end = { amountText(format.money(-f.fixedToComeMinor)) })
                val rest = f.everydayRestMinor
                if (rest != null) {
                    listRow(t("forecast_everyday"), context = t("forecast_between", format.money(f.everydayLowMinor!!), format.money(f.everydayHighMinor!!)), end = { amountText(format.money(-rest)) })
                    val kept = f.keptMinor
                    listRow(t(if (kept != null) "forecast_kept" else "forecast_spend"), divider = false,
                        end = { amountText("≈ " + format.money(kept ?: f.spentEndMinor!!), if (kept != null && kept < 0) "" else "") })
                } else note(t("forecast_no_history"))
            }
            note(t("forecast_note"))
        }
    }

    // ── fixed costs ──────────────────────────────────────────────────────────

    private fun HTMLElement.fixedCosts(view: HouseholdView, f: PeriodForecast, format: Format, today: LocalDate) {
        if (f.waiting) div("rows") { listRow(t("forecast_waiting"), context = t("forecast_waiting_text"), divider = false) }
        if (f.fixed.isNotEmpty()) {
            section(t("fixed_costs"))
            note(t("fixed_bars_help"))
            val coming = f.fixed.filter { it.status == FixedStatus.PENDING }
            val done = f.fixed.filter { it.status != FixedStatus.PENDING }
            for (item in coming) fixedBar(item, format, today, f.length)
            if (done.isNotEmpty()) {
                val charged = done.filter { it.status == FixedStatus.PAID }
                div("rows") {
                    listRow(t(if (charged.size == done.size) "fixed_charged_group" else "fixed_charged_skipped_group", done.size), start = { span("lead in") { ui("check_circle") } },
                        end = { amountText(format.money(charged.sumOf { it.amountMinor }), "muted"); span("chev") { ui(if (chargedOpen) "expand_less" else "expand_more") } }) { chargedOpen = !chargedOpen; App.render() }
                }
                if (chargedOpen) for (item in done) fixedBar(item, format, today, f.length)
            }
        } else if (view.config.recurringRules.none { it.active }) {
            div("rows") { listRow(t("fixed_empty_title"), context = t("fixed_empty_text")) { App.openSettings(SettingsPage.RECURRING) } }
        }
        val leftOut = runCatching {
            io.github.sirallap.fulla.client.local.RecurringPlanner.leftOut(view.config, view.rows.map { it.transaction.id }.toSet(), view.active, today,
                io.github.sirallap.fulla.client.local.RecurringPlanner.currentPeriodStart(view.config, view.active, today))
        }.getOrDefault(emptyList())
        if (leftOut.isNotEmpty()) div("rows") {
            listRow(t("fixed_missed_hint", leftOut.size), context = t("fixed_missed_hint_text"), start = { span("lead warn") { ui("warning") } }) { App.openSettings(SettingsPage.RECURRING) }
        }
    }

    /** One fixed cost of the period: its bar, when it is due or was charged, which instalment it is. */
    private fun HTMLElement.fixedBar(item: FixedItem, format: Format, today: LocalDate, length: Int) {
        val daysLeft = ChronoUnit.DAYS.between(today, item.date).toInt()
        val daysText = if (item.status == FixedStatus.PENDING && !item.overdue && daysLeft > 0) t("in_days", daysLeft) else null
        val context = when {
            item.status == FixedStatus.PAID && item.byHand -> t("fixed_by_hand", format.day(item.date))
            item.status == FixedStatus.PAID -> t("fixed_paid_on", format.day(item.date))
            item.status == FixedStatus.PENDING && item.overdue -> t("fixed_overdue", format.day(item.date))
            item.status == FixedStatus.PENDING -> t("fixed_due_on", format.day(item.date))
            else -> t("fixed_skipped")
        }.let { base -> listOfNotNull(base, daysText).joinToString(" · ") }
        val paid = item.status == FixedStatus.PAID
        val overdue = item.status == FixedStatus.PENDING && item.overdue
        div("lq-bar") {
            span("lead " + if (paid) "in" else if (overdue) "warn" else "") {
                ui(when { paid -> "check_circle"; overdue -> "warning"; item.status == FixedStatus.PENDING -> "schedule"; else -> "remove_circle_outline" }, 20)
            }
            div("text") {
                span("title") { text(item.name) }
                span("ctx") { text(context) }
                if (item.installment != null && item.installments != null) span("detail") { text(t("installment_of", item.installment, item.installments)) }
                if (overdue) child("button", "text-btn") { attr("type", "button"); text(t("fixed_apply")); click { App.launch { Ledger.applyRecurring(item.ruleId, item.date) } } }
            }
            span("amount") { text(format.money(item.amountMinor)) }
            Liquids.sheet(this, item.countdown(today, length).toDouble(), if (paid) "in" else "out", vertical = false, phase = item.date.dayOfMonth * 0.7)
        }
    }
}
