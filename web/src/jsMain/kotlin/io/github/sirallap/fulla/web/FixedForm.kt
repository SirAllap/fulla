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
import io.github.sirallap.fulla.core.time.LocalDate
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/** Fixed costs: rent, salary, subscriptions. They write themselves on their day. */
object FixedForm {
    /** The rule being edited, "new" for a new one, or null for the list. */
    private var editing: String? = null
    private var name = ""
    private var kind = TransactionKind.EXPENSE
    private var amount = ""
    private var categoryId: String? = null
    private var accountId: String? = null
    private var frequency = Frequency.MONTHLY
    private var day = 1
    private var weekday = 1
    private var month = 1
    private var every = 1
    private var firstMonth = 1
    private var auto = true
    private var active = true
    private var problem: String? = null

    fun close() { editing = null; problem = null }

    fun page(parent: HTMLElement, view: HouseholdView, format: Format) = parent.run {
        if (editing == null) list(view, format) else form(view, format)
    }

    private fun weekdayName(n: Int): String {
        val d = js("new Date(2024, 0, n)")
        return (d.toLocaleDateString(I18n.language, js("({ weekday: 'short' })")) as String)
    }

    private fun monthName(n: Int): String {
        val m = n - 1
        val d = js("new Date(2024, m, 1)")
        return (d.toLocaleDateString(I18n.language, js("({ month: 'short' })")) as String)
    }

    private fun scheduleText(s: Schedule, start: LocalDate): String = when (s.frequency) {
        Frequency.DAILY -> t("every_day")
        Frequency.WEEKLY -> t("weekly_on", s.byWeekday.joinToString(", ") { weekdayName(it) })
        Frequency.MONTHLY -> {
            val d = s.byMonthDay ?: 1
            if (s.interval > 1) t("every_n_months_on_day", s.interval, d) else t("monthly_on_day", d)
        }
        Frequency.YEARLY -> t("yearly_on", s.byMonthDay ?: 1, monthName(s.byMonth ?: start.monthValue))
    }

    private fun HTMLElement.list(view: HouseholdView, format: Format) {
        val rules = view.config.recurringRules.filter { !it.archived }
        if (rules.isEmpty()) div("empty") {
            child("h2") { text(t("fixed_empty_title")) }
            child("p", "muted") { text(t("fixed_empty_text")) }
        } else div("card flush") {
            for (r in rules) child("button", if (r.active) "list-row" else "list-row archived") {
                attr("type", "button")
                div("row-text") {
                    span("name") { text(r.name) }
                    span("muted small") { text(scheduleText(r.schedule, r.startDate) + (if (!r.autoCreate) " · " + t("summary_reminder_only") else "") + (if (!r.active) " · " + t("paused") else "")) }
                }
                span("amount") { text(format.money(r.template.amountMinor)) }
                click { startEditing(view, format, r) }
            }
        }
        button(t("add_recurring"), "btn primary wide") { startEditing(view, format, null) }
    }

    private fun startEditing(view: HouseholdView, format: Format, r: RecurringRule?) {
        val today = LocalDate.now()
        editing = r?.id ?: "new"
        name = r?.name ?: ""
        kind = r?.template?.kind ?: TransactionKind.EXPENSE
        amount = r?.template?.amountMinor?.let { minor ->
            val units = format.currency.minorUnits
            val digits = minor.toString().padStart(units + 1, '0')
            if (units == 0) digits else digits.dropLast(units) + "." + digits.takeLast(units)
        } ?: ""
        categoryId = r?.template?.categoryId
        accountId = r?.template?.accountId ?: view.config.accounts.firstOrNull { !it.archived }?.id
        frequency = r?.schedule?.frequency ?: Frequency.MONTHLY
        day = r?.schedule?.byMonthDay ?: today.dayOfMonth
        weekday = r?.schedule?.byWeekday?.firstOrNull() ?: today.dayOfWeek.value
        month = r?.schedule?.byMonth ?: today.monthValue
        every = r?.schedule?.interval ?: 1
        firstMonth = r?.startDate?.monthValue ?: today.monthValue
        auto = r?.autoCreate ?: true
        active = r?.active ?: true
        problem = null
        App.render()
    }

    private fun HTMLElement.form(view: HouseholdView, format: Format) {
        val config = view.config
        val existing = config.recurringRules.firstOrNull { it.id == editing }
        div("card") {
            val nameInput = field(t("name"), name)
            nameInput.on("input") { name = nameInput.value }
            div("chips") {
                for ((k, label) in listOf(TransactionKind.EXPENSE to "kind_expense", TransactionKind.INCOME to "kind_income"))
                    button(t(label), if (k == kind) "chip selected" else "chip") { kind = k; categoryId = null; App.render() }
            }
            div("amount small") {
                span("currency") { text(format.currency.code) }
                val input = input("text", amount, "amount-input") { attr("inputmode", "decimal"); attr("placeholder", "0"); attr("aria-label", t("amount")) }
                input.on("input") { amount = input.value }
            }
            val categories = config.categories.filter { !it.archived && it.appliesTo.allows(kind) && it.parentId == null }.sortedBy { it.sort }
            div("field") {
                label(t("category"))
                val select = child("select", "select") {
                    child("option") { attr("value", ""); text("—") }
                    for (c in categories) child("option") {
                        attr("value", c.id); text(c.name)
                        if (c.id == categoryId) attr("selected", "selected")
                    }
                } as HTMLSelectElement
                select.on("change") { categoryId = select.value.ifEmpty { null } }
            }
            val accounts = config.accounts.filter { !it.archived }
            if (accounts.size > 1) div("field") {
                label(t("account"))
                val select = child("select", "select") {
                    for (a in accounts) child("option") {
                        attr("value", a.id); text(a.name)
                        if (a.id == accountId) attr("selected", "selected")
                    }
                } as HTMLSelectElement
                select.on("change") { accountId = select.value }
            }
            div("field") {
                label(t("how_often"))
                val select = child("select", "select") {
                    for ((f, key) in listOf(Frequency.MONTHLY to "monthly", Frequency.WEEKLY to "weekly", Frequency.YEARLY to "yearly", Frequency.DAILY to "daily"))
                        child("option") { attr("value", f.key); text(t(key)); if (f == frequency) attr("selected", "selected") }
                } as HTMLSelectElement
                select.on("change") { frequency = Frequency.of(select.value) ?: Frequency.MONTHLY; App.render() }
            }
            when (frequency) {
                Frequency.MONTHLY, Frequency.YEARLY -> div("field") {
                    label(t("day_of_month"))
                    val select = child("select", "select") {
                        for (d in 1..31) child("option") { attr("value", d.toString()); text(d.toString()); if (d == day) attr("selected", "selected") }
                    } as HTMLSelectElement
                    select.on("change") { day = select.value.toInt() }
                    child("small", "muted") { text(t("day_of_month_help")) }
                }
                Frequency.WEEKLY -> div("field") {
                    val select = child("select", "select") {
                        for (d in 1..7) child("option") { attr("value", d.toString()); text(weekdayName(d)); if (d == weekday) attr("selected", "selected") }
                    } as HTMLSelectElement
                    select.on("change") { weekday = select.value.toInt() }
                }
                Frequency.DAILY -> Unit
            }
            if (frequency == Frequency.YEARLY) div("field") {
                val select = child("select", "select") {
                    for (m in 1..12) child("option") { attr("value", m.toString()); text(monthName(m)); if (m == month) attr("selected", "selected") }
                } as HTMLSelectElement
                select.on("change") { month = select.value.toInt() }
            }
            if (frequency == Frequency.MONTHLY) div("field") {
                val select = child("select", "select") {
                    for (n in listOf(1, 2, 3, 4, 6, 12)) child("option") {
                        attr("value", n.toString()); text(if (n == 1) t("every_month") else t("every_n_months", n)); if (n == every) attr("selected", "selected")
                    }
                } as HTMLSelectElement
                select.on("change") { every = select.value.toInt(); App.render() }
            }
            if (frequency == Frequency.MONTHLY && every > 1) div("field") {
                label(t("first_payment_in"))
                val select = child("select", "select") {
                    for (m in 1..12) child("option") { attr("value", m.toString()); text(monthName(m)); if (m == firstMonth) attr("selected", "selected") }
                } as HTMLSelectElement
                select.on("change") { firstMonth = select.value.toInt() }
            }
            checkbox(t("write_itself"), t("write_itself_help"), auto) { auto = it }
            if (existing != null) checkbox(t("active"), t("active_help"), active) { active = it }
            problem?.let { child("p", "problem") { attr("role", "alert"); text(it) } }
            button(t("save"), "btn primary wide") { save(view, format, existing) }
            button(t("cancel"), "btn quiet wide") { close(); App.render() }
            if (existing != null) button(t("delete"), "btn danger wide") {
                if (js("confirm")(t("delete_fixed_title", existing.name) + "\n" + t("delete_fixed_text")) as Boolean) {
                    close()
                    App.launch { Ledger.upsert(Structure.RECURRING, Wire.recurring(existing.copy(archived = true))) }
                }
            }
        }
    }

    private fun HTMLElement.checkbox(title: String, help: String, value: Boolean, onChange: (Boolean) -> Unit) {
        child("label", "switch-row") {
            div("row-text") { span("name") { text(title) }; span("muted small") { text(help) } }
            val box = input("checkbox", "", "switch") { checked = value }
            box.on("change") { onChange(box.checked) }
        }
    }

    private fun save(view: HouseholdView, format: Format, existing: RecurringRule?) {
        val config = view.config
        val minor = MoneyParser.parseTyped(amount, format.currency, format.decimalStyle)
        if (name.isBlank() || minor == null || minor <= 0) { problem = if (name.isBlank()) t("name") else t("amount"); App.render(); return }
        if (categoryId == null) { problem = t("category"); App.render(); return }
        val schedule = runCatching {
            when (frequency) {
                Frequency.MONTHLY -> Schedule(Frequency.MONTHLY, interval = every, byMonthDay = day)
                Frequency.WEEKLY -> Schedule(Frequency.WEEKLY, byWeekday = listOf(weekday))
                Frequency.YEARLY -> Schedule(Frequency.YEARLY, byMonthDay = day, byMonth = month)
                Frequency.DAILY -> Schedule(Frequency.DAILY)
            }
        }.getOrNull() ?: run { problem = t("something_failed"); App.render(); return }
        val today = LocalDate.now()
        val periodStart = RecurringPlanner.currentPeriodStart(config, view.active, today)
        val start = RecurringPlanner.startFor(existing, schedule, active, if (every > 1) firstMonth else null, periodStart, today)
        val everyone = config.activeMembers.map { it.id }
        val template = (existing?.template ?: Transaction(id = "", kind = kind, date = today, amountMinor = 0, createdAt = "", clientUpdatedAt = "")).copy(
            kind = kind, amountMinor = minor, categoryId = categoryId, accountId = accountId,
            paidByMemberId = existing?.template?.paidByMemberId ?: config.meMemberId,
            split = if (kind == TransactionKind.EXPENSE && everyone.size >= 2) (existing?.template?.split ?: Split.Equal(everyone)) else null,
            recurrence = Recurrence.FIXED, note = name.trim(), status = Status.ACTIVE,
        )
        val rule = RecurringRule(existing?.id ?: randomUuid(), name.trim(), template, schedule, start, existing?.endDate, auto, active, false)
        close()
        App.launch {
            Ledger.upsert(Structure.RECURRING, Wire.recurring(rule))
            App.toast(t("web_saved"))
        }
    }
}
