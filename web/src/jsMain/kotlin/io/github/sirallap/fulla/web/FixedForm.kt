// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.local.RecurringPlanner
import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import io.github.sirallap.fulla.core.recurring.Scheduler
import io.github.sirallap.fulla.core.time.LocalDate
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * Fixed costs: rent, salary, subscriptions, and financing that ends. Written down once, then written down by
 * themselves on their day. The same list and the same form as the Android app's, and the same planner decides
 * what saving writes (core's Scheduler, client's RecurringPlanner).
 */
object FixedForm {
    /** The rule being edited, "new" for a new one, or null for the list. */
    private var editing: String? = null
    private var name = ""
    private var kind = TransactionKind.EXPENSE
    private var amount = ""
    private var categoryId: String? = null
    private var accountId: String? = null
    private var frequency = Frequency.MONTHLY
    private var day = ""
    private var weekdays: Set<Int> = setOf(1)
    private var month = 1
    /** 1 = every month, 2/3/6 = every that many, 0 = only the months picked. */
    private var every = 1
    private var months: Set<Int> = setOf(1)
    private var firstMonth = 1
    /** 0 = never, 1 = on a date, 2 = after some number of payments. Saved as a date either way. */
    private var ends = 0
    private var endPick = LocalDate.now()
    private var paymentsText = "6"
    private var showAll = false
    private var auto = true
    private var active = true

    val isEditing: Boolean get() = editing != null

    fun close() { editing = null; showAll = false }

    fun page(parent: HTMLElement, view: HouseholdView, format: Format, canEdit: Boolean) = parent.run {
        if (editing == null) list(view, format, canEdit) else form(view, format)
    }

    // ── words ────────────────────────────────────────────────────────────────

    private fun weekdayName(n: Int): String {
        val d = js("new Date(2024, 0, n)")
        return (d.toLocaleDateString(I18n.language, js("({ weekday: 'short' })")) as String)
    }

    private fun monthName(n: Int, long: Boolean = false): String {
        val m = n - 1
        val d = js("new Date(2024, m, 1)")
        val style = if (long) "long" else "short"
        return (d.toLocaleDateString(I18n.language, js("({ month: style })")) as String)
    }

    private fun mediumDay(d: LocalDate): String {
        val y = d.year; val m = d.monthValue - 1; val dd = d.dayOfMonth
        val jsDate = js("new Date(y, m, dd)")
        return jsDate.toLocaleDateString(I18n.language, js("({ day: 'numeric', month: 'short', year: 'numeric' })")) as String
    }

    private fun scheduleText(s: Schedule, start: LocalDate): String = when (s.frequency) {
        Frequency.DAILY -> t("every_day")
        Frequency.WEEKLY -> t("weekly_on", s.byWeekday.joinToString(", ") { weekdayName(it) })
        Frequency.MONTHLY -> {
            val d = s.byMonthDay ?: start.dayOfMonth
            when {
                s.byMonths.isNotEmpty() -> t("monthly_in_months", d, s.byMonths.joinToString(", ") { monthName(it) })
                s.interval > 1 -> t("every_n_months_on_day", s.interval, d)
                else -> t("monthly_on_day", d)
            }
        }
        Frequency.YEARLY -> t("yearly_on", s.byMonthDay ?: start.dayOfMonth, monthName(s.byMonth ?: start.monthValue, long = true))
    }

    // ── the list ─────────────────────────────────────────────────────────────

    private fun HTMLElement.list(view: HouseholdView, format: Format, canEdit: Boolean) {
        val today = LocalDate.now()
        val config = view.config
        // What fell due before this period and was never written: offered, not written behind the person's back.
        val leftOut = runCatching {
            RecurringPlanner.leftOut(config, view.rows.map { it.transaction.id }.toSet(), view.active, today,
                RecurringPlanner.currentPeriodStart(config, view.active, today))
        }.getOrDefault(emptyList())
        note(t("recurring_text"))
        if (canEdit && leftOut.isNotEmpty()) {
            section(t("fixed_missed"))
            note(t("fixed_missed_text"))
            div("rows") {
                for (o in leftOut) listRow(o.rule.name, context = format.day(o.date), end = { amountText(format.money(o.rule.template.amountMinor)) })
            }
            div("actions") {
                primaryButton(t("fixed_missed_apply", leftOut.size)) {
                    App.launch { leftOut.forEach { Ledger.applyRecurring(it.rule.id, it.date) } }
                }
                quietButton(t("fixed_missed_skip")) {
                    confirmSheet(t("fixed_missed_skip"), t("fixed_missed_skip_confirm", leftOut.size), t("fixed_missed_skip")) {
                        App.launch { leftOut.forEach { Ledger.applyRecurring(it.rule.id, it.date, skip = true) } }
                    }
                }
            }
            section(t("settings_recurring"))
        }
        val shown = config.recurringRules.filter { !it.archived }
        if (shown.isEmpty()) emptyState("event", t("no_recurring_title"), t("no_recurring_text"))
        div("rows") {
            for (r in shown.sortedWith(compareBy({ !it.active }, { it.name }))) {
                val income = r.template.kind == TransactionKind.INCOME
                val ended = r.endDate
                val progress = runCatching { Scheduler.progress(r, today) }.getOrNull()
                listRow(r.name, dim = !r.active,
                    context = scheduleText(r.schedule, r.startDate) + (progress?.let { (done, all) -> " · " + t("payment_progress", done, all) } ?: ""),
                    detail = when {
                        !r.active -> t("paused")
                        ended != null && ended < today -> t("fixed_ended", format.day(ended))
                        else -> config.category(r.template.categoryId)?.name
                    },
                    end = { amountText(format.money(r.template.amountMinor, signed = income), if (income) "in" else "") },
                    onClick = if (canEdit) ({ startEditing(view, format, r) }) else null)
            }
            if (canEdit) listRow(t("add_recurring"), start = leadIcon("add")) { startEditing(view, format, null) }
        }
    }

    private fun startEditing(view: HouseholdView, format: Format, r: RecurringRule?) {
        val today = LocalDate.now()
        editing = r?.id ?: "new"
        name = r?.name ?: ""
        kind = r?.template?.kind ?: TransactionKind.EXPENSE
        amount = r?.template?.let { format.plain(it.amountMinor) } ?: ""
        categoryId = r?.template?.categoryId
        accountId = r?.template?.accountId ?: view.config.accounts.firstOrNull { !it.archived }?.id
        frequency = r?.schedule?.frequency ?: Frequency.MONTHLY
        day = (r?.schedule?.byMonthDay ?: today.dayOfMonth).toString()
        weekdays = r?.schedule?.byWeekday?.toSet() ?: setOf(today.dayOfWeek.value)
        month = r?.schedule?.byMonth ?: today.monthValue
        every = r?.schedule?.let { if (it.byMonths.isNotEmpty()) 0 else it.interval } ?: 1
        months = r?.schedule?.byMonths?.toSet()?.takeIf { it.isNotEmpty() } ?: setOf(today.monthValue)
        firstMonth = (r?.startDate ?: today).monthValue
        ends = if (r?.endDate != null) 1 else 0
        endPick = r?.endDate ?: today.plusMonths(6)
        paymentsText = "6"
        auto = r?.autoCreate ?: true
        active = r?.active ?: true
        App.render()
        window.scrollTo(0.0, 0.0)
    }

    // ── the form ─────────────────────────────────────────────────────────────

    private fun HTMLElement.formLabel(caption: String) { div("form-label") { span("t-label") { text(caption) } } }

    private fun HTMLElement.chipsFor(wrap: Boolean = true, build: HTMLElement.() -> Unit) = chipRow(wrap, build)

    private fun HTMLElement.form(view: HouseholdView, format: Format) {
        val config = view.config
        val existing = config.recurringRules.firstOrNull { it.id == editing }
        val today = LocalDate.now()
        val minor = MoneyParser.parseTyped(amount, format.currency, format.decimalStyle)
        val dayNumber = day.toIntOrNull()?.takeIf { it in 1..31 }
        val schedule = runCatching {
            when (frequency) {
                Frequency.DAILY -> Schedule(Frequency.DAILY)
                Frequency.WEEKLY -> Schedule(Frequency.WEEKLY, byWeekday = weekdays.sorted())
                Frequency.MONTHLY -> if (every == 0) Schedule(Frequency.MONTHLY, byMonthDay = dayNumber, byMonths = months.sorted())
                    else Schedule(Frequency.MONTHLY, interval = every, byMonthDay = dayNumber)
                Frequency.YEARLY -> Schedule(Frequency.YEARLY, byMonthDay = dayNumber, byMonth = month)
            }
        }.getOrNull()
        // From today on, never back (RecurringPlanner.startFor): a new item applies from the start of this period, a changed one from today.
        val periodStart = RecurringPlanner.currentPeriodStart(config, view.active, today)
        val startDate = schedule?.let { RecurringPlanner.startFor(existing, it, active, if (every > 1) firstMonth else null, periodStart, today) } ?: today
        val startRule = schedule?.let {
            RecurringRule(existing?.id ?: PREVIEW_ID, "", Transaction(id = "", kind = kind, date = today, amountMinor = minor ?: 0, categoryId = categoryId,
                createdAt = "", clientUpdatedAt = ""), it, startDate)
        }
        val payments = paymentsText.toIntOrNull()?.takeIf { it in 1..999 }
        val endDate: LocalDate? = when (ends) {
            1 -> endPick
            2 -> startRule?.let { r -> payments?.let { n -> runCatching { Scheduler.endAfter(r, n) }.getOrNull() } }
            else -> null
        }
        val previewRule = startRule?.copy(endDate = endDate)
        val endPayments = if (ends != 0 && previewRule != null && endDate != null) runCatching {
            Scheduler.occurrences(previewRule, previewRule.startDate, endDate)
        }.getOrDefault(emptyList()) else emptyList()
        val endProblem = ends != 0 && schedule != null && (endDate == null || endPayments.isEmpty())
        val held = view.rows.map { it.transaction.id }.toSet()
        // What saving writes right now, asked of the planner itself, so the screen never promises what the app will not do.
        val writeNow = if (previewRule != null && auto && active && minor != null && minor > 0) {
            val rule = previewRule.copy(autoCreate = true, active = true)
            val cfg = config.copy(recurringRules = config.recurringRules.filter { it.id != rule.id } + rule)
            runCatching { RecurringPlanner.due(cfg, held, today, view.active, periodStart).filter { it.recurringRuleId == rule.id }.map { it.date } }.getOrDefault(emptyList())
        } else emptyList()
        val nextDue = if (previewRule != null && frequency == Frequency.MONTHLY && every != 1) {
            val ahead = Scheduler.occurrences(previewRule, today.plusDays(1), today.plusMonths(36))
            val year = ahead.filter { it <= today.plusMonths(12) }
            if (year.size >= 3) year.take(6) else ahead.take(3)
        } else emptyList()
        val valid = name.isNotBlank() && minor != null && minor > 0 && categoryId != null && schedule != null && !endProblem

        val nameInput = field(t("name"), name) { attr("maxlength", "60") }
        nameInput.on("input") { name = nameInput.value }
        chipsFor {
            chip(t("kind_expense"), kind == TransactionKind.EXPENSE) { kind = TransactionKind.EXPENSE; categoryId = null; App.render() }
            chip(t("kind_income"), kind == TransactionKind.INCOME) { kind = TransactionKind.INCOME; categoryId = null; App.render() }
        }
        val amountInput = field(t("amount"), amount, help = format.currency.code) { attr("inputmode", "decimal") }
        amountInput.on("input") { amount = amountInput.value }

        // The same tiles as the entry screen: icon and colour of each category, the most used first, and its subcategories below.
        formLabel(t("category"))
        val usable = config.categories.filter { !it.archived && it.appliesTo.allows(kind) && it.parentId == null }
        val since = today.minusMonths(3)
        val use = view.active.filter { it.date >= since && it.kind == kind }.groupingBy { config.category(it.categoryId)?.parentId ?: it.categoryId }.eachCount()
        val ordered = usable.sortedWith(compareByDescending<io.github.sirallap.fulla.core.model.Category> { use[it.id] ?: 0 }.thenBy { it.sort })
        val top = config.category(categoryId)?.let { it.parentId ?: it.id }
        val limit = 8
        val shown = if (showAll || ordered.size <= limit) ordered else ordered.take(limit - 1).let { first ->
            if (top != null && first.none { it.id == top }) first.dropLast(1) + ordered.first { it.id == top } else first
        }
        div("tiles") {
            for (c in shown) tile(c.name, c.icon, "var(--cat-${c.colorIndex.mod(12)})", c.id == top) { categoryId = c.id; App.render() }
            if (ordered.size > limit) tile(if (showAll) t("fewer") else t("more"), "", "var(--ink-muted)", false, uiIcon = "expand_more") { showAll = !showAll; App.render() }
        }
        val subs = config.categories.filter { it.parentId == top && top != null && !it.archived && it.appliesTo.allows(kind) }.sortedBy { it.sort }
        if (subs.isNotEmpty()) chipsFor {
            chip(t("subcategory_general"), categoryId == top) { categoryId = top; App.render() }
            for (s in subs) chip(s.name, categoryId == s.id) { categoryId = s.id; App.render() }
        }
        val accounts = config.accounts.filter { !it.archived || it.id == accountId }
        if (accounts.size > 1) {
            formLabel(t("account"))
            chipsFor { for (a in accounts) chip(a.name, a.id == accountId) { accountId = a.id; App.render() } }
        }

        formLabel(t("repeats"))
        chipsFor {
            for ((f, key) in listOf(Frequency.WEEKLY to "weekly", Frequency.MONTHLY to "monthly", Frequency.YEARLY to "yearly", Frequency.DAILY to "daily"))
                chip(t(key), frequency == f) { frequency = f; App.render() }
        }
        when (frequency) {
            Frequency.WEEKLY -> chipsFor {
                for (d in 1..7) chip(weekdayName(d), d in weekdays) { weekdays = if (d in weekdays && weekdays.size > 1) weekdays - d else weekdays + d; App.render() }
            }
            Frequency.MONTHLY, Frequency.YEARLY -> {
                val dayInput = field(t("day_of_month"), day, "text", help = t("day_of_month_help")) { attr("inputmode", "numeric"); attr("maxlength", "2") }
                dayInput.on("input") { day = dayInput.value.filter { it.isDigit() }.take(2) }
                if (frequency == Frequency.YEARLY) chipsFor { for (m in 1..12) chip(monthName(m), m == month) { month = m; App.render() } }
                if (frequency == Frequency.MONTHLY) {
                    formLabel(t("how_often"))
                    chipsFor {
                        chip(t("every_month"), every == 1) { every = 1; App.render() }
                        for (n in listOf(2, 3, 6)) chip(t("every_n_months", n), every == n) { every = n; App.render() }
                        chip(t("pick_months"), every == 0) { every = 0; App.render() }
                    }
                    if (every == 0) {
                        formLabel(t("only_these_months"))
                        chipsFor { for (m in 1..12) chip(monthName(m), m in months) { months = if (m in months && months.size > 1) months - m else months + m; App.render() } }
                    } else if (every > 1) {
                        formLabel(t("first_payment_in"))
                        // Every N months from the first one: the months it falls due in light up, so the calendar is seen at once.
                        val dueMonths = (0 until 12).map { (firstMonth - 1 + it * every) % 12 + 1 }.toSet()
                        chipsFor { for (m in 1..12) chip(monthName(m), m in dueMonths) { firstMonth = m; App.render() } }
                    }
                    if (nextDue.isNotEmpty()) child("p", "muted pad") { text(t("next_charges", nextDue.joinToString(" · ") { mediumDay(it) })) }
                }
            }
            Frequency.DAILY -> Unit
        }

        formLabel(t("ends"))
        chipsFor {
            chip(t("ends_never"), ends == 0) { ends = 0; App.render() }
            chip(t("ends_on_date"), ends == 1) { ends = 1; App.render() }
            chip(t("ends_after_payments"), ends == 2) { ends = 2; App.render() }
        }
        if (ends == 1) {
            val dateInput = field(t("ends_on_date"), endPick.toString(), "date")
            dateInput.on("change") { runCatching { LocalDate.parse(dateInput.value) }.getOrNull()?.let { endPick = it; App.render() } }
        }
        if (ends == 2) {
            val n = field(t("payments_count"), paymentsText, help = t("payments_count_help")) { attr("inputmode", "numeric"); attr("maxlength", "3") }
            n.on("input") { paymentsText = n.value.filter { it.isDigit() }.take(3); }
            n.on("change") { App.render() }
        }
        if (ends != 0) {
            if (endProblem) child("p", "problem") { text(t("end_problem")) }
            else child("p", "muted pad") { text(t("last_payment", mediumDay(endPayments.last()), endPayments.size)) }
        }

        div("rows") {
            switchRow(t("write_itself"), t("write_itself_help"), auto) { auto = it; App.render() }
            if (existing != null) switchRow(t("active"), t("active_help"), active) { active = it; App.render() }
        }

        // What saving does, in one place: the summary, what it writes right now, and what is still missing.
        val missing = if (valid) emptyList() else listOfNotNull(
            if (name.isBlank()) t("name") else null,
            if (minor == null || minor <= 0) t("amount") else null,
            if (categoryId == null) t("category") else null,
        )
        if (schedule != null || missing.isNotEmpty() || writeNow.isNotEmpty()) div("summary") {
            if (schedule != null) child("p") {
                text(listOfNotNull(
                    scheduleText(schedule, startDate), config.account(accountId)?.name,
                    if (ends != 0 && endDate != null && !endProblem) t("until_date", mediumDay(endDate)) else null,
                    t(if (auto) "summary_writes_itself" else "summary_reminder_only"),
                ).joinToString(" · "))
            }
            if (writeNow.isNotEmpty()) child("p", "muted") {
                text(if (writeNow.size <= 3) t("write_now", writeNow.joinToString(" · ") { mediumDay(it) }) else t("write_now_many", writeNow.size, mediumDay(writeNow.first())))
            }
            if (missing.isNotEmpty()) child("p", "warn") { text(t("still_needed", missing.joinToString(" · "))) }
        }

        div("actions") {
            primaryButton(t("save"), enabled = valid) {
                val everyone = config.activeMembers.map { it.id }
                val old = existing?.template
                val template = (old ?: Transaction(id = "", kind = kind, date = today, amountMinor = 0, createdAt = "", clientUpdatedAt = "")).copy(
                    kind = kind, amountMinor = minor!!, categoryId = categoryId, accountId = accountId,
                    paidByMemberId = old?.paidByMemberId ?: config.meMemberId,
                    split = if (kind == TransactionKind.EXPENSE && everyone.size >= 2) (old?.split ?: Split.Equal(everyone)) else null,
                    recurrence = Recurrence.FIXED, note = name.trim(), status = Status.ACTIVE,
                )
                val rule = RecurringRule(existing?.id ?: randomUuid(), name.trim(), template, schedule!!, startDate, endDate, auto, active, false)
                close()
                App.launch {
                    try { Ledger.upsert(Structure.RECURRING, Wire.recurring(rule)); App.toast(t("web_saved")) } catch (e: Throwable) { App.toast(Remote.message(e)) }
                }
            }
            quietButton(t("cancel")) { close(); App.render() }
            if (existing != null) dangerButton(t("delete")) { confirmDelete(view, existing) }
        }
    }

    private fun confirmDelete(view: HouseholdView, rule: RecurringRule) {
        val wrote = view.rows.count { it.transaction.recurringRuleId == rule.id && it.transaction.isActive }
        sheet(t("delete_fixed_title", rule.name)) { close ->
            child("p", "pad") { text(t("delete_fixed_text")) }
            div("actions") {
                // Archived, as accounts and categories are: it never writes again and leaves the list. What it wrote stays, unless the person says otherwise.
                if (wrote > 0) dangerButton(t("delete_fixed_and_rows", wrote)) { close(); archive(view, rule, withRows = true) }
                dangerButton(t("delete")) { close(); archive(view, rule, withRows = false) }
                quietButton(t("cancel")) { close() }
            }
        }
    }

    private fun archive(view: HouseholdView, rule: RecurringRule, withRows: Boolean) {
        val wrote = if (withRows) view.rows.filter { it.transaction.recurringRuleId == rule.id && it.transaction.isActive }.map { it.transaction.id } else emptyList()
        close()
        App.launch {
            Ledger.upsert(Structure.RECURRING, Wire.recurring(rule.copy(active = false, archived = true)))
            wrote.forEach { Ledger.delete(it) }
        }
    }

    /** The id a rule being made has in the preview: it has none until it is saved. */
    private const val PREVIEW_ID = "00000000-0000-4000-8000-000000000000"
}
