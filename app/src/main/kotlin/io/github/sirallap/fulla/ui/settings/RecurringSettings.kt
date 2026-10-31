// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import io.github.sirallap.fulla.ui.components.FullaDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.local.RecurringPlanner
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
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.Chip
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.SwitchRow
import io.github.sirallap.fulla.ui.entry.CategoryTiles
import io.github.sirallap.fulla.ui.components.ConfirmDialog
import io.github.sirallap.fulla.ui.components.DeleteButton
import io.github.sirallap.fulla.ui.components.FormLabel
import io.github.sirallap.fulla.ui.components.FormScreen
import io.github.sirallap.fulla.ui.components.Notice
import io.github.sirallap.fulla.ui.components.ProgressLine
import io.github.sirallap.fulla.ui.components.Revealed
import io.github.sirallap.fulla.ui.components.ScreenPrimaryAction
import io.github.sirallap.fulla.ui.components.SegmentedChoice
import io.github.sirallap.fulla.ui.entry.CategoryBadge
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/** The id a rule being made has in the preview: it has none until it is saved. */
private const val PREVIEW_ID = "00000000-0000-4000-8000-000000000000"

@Composable
private fun scheduleText(s: Schedule, start: LocalDate): String {
    val locale = Locale.getDefault()
    return when (s.frequency) {
        Frequency.DAILY -> stringResource(R.string.every_day)
        Frequency.WEEKLY -> stringResource(R.string.weekly_on, s.byWeekday.joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) })
        Frequency.MONTHLY -> {
            val day = s.byMonthDay ?: start.dayOfMonth
            when {
                s.byMonths.isNotEmpty() -> stringResource(R.string.monthly_in_months, day,
                    s.byMonths.joinToString(", ") { Month.of(it).getDisplayName(TextStyle.SHORT_STANDALONE, locale) })
                s.interval > 1 -> stringResource(R.string.every_n_months_on_day, s.interval, day)
                else -> stringResource(R.string.monthly_on_day, day)
            }
        }
        Frequency.YEARLY -> stringResource(R.string.yearly_on, s.byMonthDay ?: start.dayOfMonth,
            Month.of(s.byMonth ?: start.monthValue).getDisplayName(TextStyle.FULL_STANDALONE, locale))
    }
}

/**
 * Rent, salary, subscriptions: written down once, then written down by
 * themselves on their day. The list says what matters about each: when it
 * is next charged, what it is, how far along a plan of payments is; and
 * what they come to in a month. Adding one is the screen's button.
 */
@Composable
fun RecurringSettings(view: HouseholdView, canEdit: Boolean, change: Change) {
    val ledger = LocalContainer.current.ledger
    val f = view.formats
    val c = FullaTheme.colors
    var editing by remember { mutableStateOf<RecurringRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmingSkip by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    // What fell due before this period and was never written: offered, not written behind the person's back.
    val leftOut = remember(view) {
        runCatching {
            RecurringPlanner.leftOut(view.config, view.rows.map { it.transaction.id }.toSet(), view.active, today,
                RecurringPlanner.currentPeriodStart(view.config, view.active, today))
        }.getOrDefault(emptyList())
    }
    if (canEdit) ScreenPrimaryAction(stringResource(R.string.add_recurring)) { creating = true }
    Column {
        val shown = view.config.recurringRules.filter { !it.archived }
        if (canEdit && leftOut.isNotEmpty()) {
            Notice(
                title = stringResource(R.string.fixed_missed),
                text = stringResource(R.string.fixed_missed_text),
                actionLabel = stringResource(R.string.fixed_missed_apply, leftOut.size),
                onAction = { scope.launch { leftOut.forEach { ledger.applyRecurring(view.id, it.rule.id, it.date) } } },
                dismissLabel = stringResource(R.string.fixed_missed_skip),
                onDismiss = { confirmingSkip = true },
            )
            for (o in leftOut) {
                ListRow(o.rule.name, context = f.day(o.date), start = { CategoryBadge(view.config.category(o.rule.template.categoryId)) },
                    end = { AmountText(f.money(o.rule.template.amountMinor), color = c.ink) })
            }
        }
        if (shown.isEmpty()) {
            EmptyState(Icons.Outlined.Event, stringResource(R.string.no_recurring_title), stringResource(R.string.no_recurring_text))
            return@Column
        }
        // What they come to in a month: the year ahead of every cost, a twelfth of it.
        val costs = shown.filter { it.template.kind == TransactionKind.EXPENSE }
        // The figure first, in the dark card of the mockup.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).clip(RoundedCornerShape(20.dp))
                .background(c.ink).padding(16.dp),
        ) {
            Text(stringResource(R.string.recurring_month_share), style = FullaType.secondary, color = c.paper.copy(alpha = 0.75f))
            Text(f.money(Scheduler.monthlyShare(costs, today)), style = FullaType.screenTitle, color = c.paper)
        }
        // By when they are next charged, never by name: what is coming first is what a person looks for.
        val next = shown.associateWith { runCatching { Scheduler.nextDue(it, today) }.getOrNull() }
        val soon = shown.filter { r -> next[r]?.let { it <= today.plusDays(6) } == true }.sortedBy { next[it] }
        val later = shown.filter { r -> next[r]?.let { it > today.plusDays(6) } == true }.sortedBy { next[it] }
        val stopped = shown.filter { next[it] == null }.sortedBy { it.name.lowercase() }
        for ((title, rules) in listOf(R.string.recurring_soon to soon, R.string.recurring_later to later, R.string.recurring_stopped to stopped)) {
            if (rules.isEmpty()) continue
            val total = rules.filter { it.template.kind == TransactionKind.EXPENSE && next[it] != null }.sumOf { it.template.amountMinor }
            Section(stringResource(title), trailing = if (total > 0) ({ AmountText(f.money(total), color = c.inkMuted) }) else null)
            for (r in rules) RuleRow(view, r, next[r], today, onClick = if (canEdit) ({ editing = r }) else null)
        }
    }
    if (confirmingSkip) {
        ConfirmDialog(
            title = stringResource(R.string.fixed_missed_skip),
            text = stringResource(R.string.fixed_missed_skip_confirm, leftOut.size),
            confirmLabel = stringResource(R.string.fixed_missed_skip),
            onConfirm = {
                confirmingSkip = false
                scope.launch { leftOut.forEach { ledger.skipRecurring(view.id, it.rule.id, it.date) } }
            },
            onDismiss = { confirmingSkip = false },
            danger = false,
        )
    }
    if (creating || editing != null) {
        RecurringForm(view, editing, onDismiss = { creating = false; editing = null }, onDelete = { rule, withRows ->
            // Archived, as accounts and categories are: it never writes again and leaves the list. What it wrote stays, unless the person says otherwise.
            val wrote = if (withRows) view.rows.filter { it.transaction.recurringRuleId == rule.id && it.transaction.isActive }.map { it.transaction.id } else emptyList()
            change { api ->
                ledger.upsert(view.id, Structure.RECURRING, Wire.recurring(rule.copy(active = false, archived = true)), api)
                wrote.forEach { ledger.delete(view.id, it) }
            }
        }) { rule ->
            change { api -> ledger.upsert(view.id, Structure.RECURRING, Wire.recurring(rule), api) }
        }
    }
}

/** One fixed cost: its category, its name, when it is next charged, how it repeats, the plan's progress as a bar, and the amount. */
@Composable
private fun RuleRow(view: HouseholdView, r: RecurringRule, next: LocalDate?, today: LocalDate, onClick: (() -> Unit)?) {
    val f = view.formats
    val c = FullaTheme.colors
    val income = r.template.kind == TransactionKind.INCOME
    val category = view.config.category(r.template.categoryId)
    val ended = r.endDate
    val progress = runCatching { Scheduler.progress(r, today) }.getOrNull()
    ListRow(
        r.name,
        titleColor = if (next != null) c.ink else c.inkMuted,
        start = { CategoryBadge(category) },
        context = listOfNotNull(
            when {
                !r.active -> stringResource(R.string.paused)
                next == null && ended != null -> stringResource(R.string.fixed_ended, f.day(ended))
                else -> next?.let { f.day(it) }
            },
            category?.name,
        ).joinToString(" · "),
        // A plan in payments shows as a short bar and "1 of 3 paid" under the date, as in the mockup; how it repeats is in its form.
        below = if (progress != null) ({
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(72.dp)) {
                    ProgressLine(progress.first.toFloat() / progress.second, category?.let { c.category(it.colorIndex) } ?: c.accent)
                }
                Text(stringResource(R.string.payment_progress, progress.first, progress.second), style = FullaType.label, color = c.inkMuted)
            }
        }) else null,
        end = { AmountText(f.money(r.template.amountMinor, signed = income), color = if (income) c.moneyIn else c.ink) },
        onClick = onClick,
    )
}

/**
 * Adding or changing a fixed cost, as a screen of its own (docs/ux.md,
 * piece 2): what it is, how it repeats, when it ends. What a choice opens
 * appears right under it (piece 4), the summary says what saving will do,
 * and the button says what is still missing instead of turning grey.
 */
@Composable
private fun RecurringForm(view: HouseholdView, existing: RecurringRule?, onDismiss: () -> Unit, onDelete: (RecurringRule, Boolean) -> Unit, onSave: (RecurringRule) -> Unit) {
    val f = view.formats
    val c = FullaTheme.colors
    val locale = Locale.getDefault()
    val dateFormat = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)
    val t = existing?.template
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(t?.kind ?: TransactionKind.EXPENSE) }
    var amount by remember { mutableStateOf(t?.let { f.plain(it.amountMinor) } ?: "") }
    var category by remember { mutableStateOf(t?.categoryId) }
    // The account it is charged to: the one it already has, else the first; the person can pick any.
    var account by remember { mutableStateOf(t?.accountId ?: view.config.accounts.firstOrNull { !it.archived }?.id) }
    var frequency by remember { mutableStateOf(existing?.schedule?.frequency ?: Frequency.MONTHLY) }
    var day by remember { mutableStateOf((existing?.schedule?.byMonthDay ?: LocalDate.now().dayOfMonth).toString()) }
    var weekdays by remember { mutableStateOf(existing?.schedule?.byWeekday?.toSet() ?: setOf(LocalDate.now().dayOfWeek.value)) }
    var month by remember { mutableStateOf(existing?.schedule?.byMonth ?: LocalDate.now().monthValue) }
    // 1 = every month, 2/3/6 = every that many, 0 = only the months picked
    var every by remember { mutableStateOf(existing?.schedule?.let { if (it.byMonths.isNotEmpty()) 0 else it.interval } ?: 1) }
    var months by remember { mutableStateOf(existing?.schedule?.byMonths?.toSet() ?: setOf(LocalDate.now().monthValue)) }
    var firstMonth by remember { mutableStateOf((existing?.startDate ?: LocalDate.now()).monthValue) }
    // How it ends: 0 = never, 1 = on a date, 2 = after some number of payments. Saved as a date either way.
    var ends by remember { mutableStateOf(if (existing?.endDate != null) 1 else 0) }
    var endPick by remember { mutableStateOf(existing?.endDate ?: LocalDate.now().plusMonths(6)) }
    var paymentsText by remember { mutableStateOf("6") }
    var pickingEnd by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var allCategories by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(existing?.autoCreate ?: true) }
    var active by remember { mutableStateOf(existing?.active ?: true) }
    val minor = MoneyParser.parseTyped(amount, f.currency, f.decimalStyle)
    val dayNumber = day.toIntOrNull()?.takeIf { it in 1..31 }
    val schedule = runCatching {
        when (frequency) {
            Frequency.DAILY -> Schedule(Frequency.DAILY)
            Frequency.WEEKLY -> Schedule(Frequency.WEEKLY, byWeekday = weekdays.sorted())
            Frequency.MONTHLY -> when {
                every == 0 -> Schedule(Frequency.MONTHLY, byMonthDay = dayNumber, byMonths = months.sorted())
                else -> Schedule(Frequency.MONTHLY, interval = every, byMonthDay = dayNumber)
            }
            Frequency.YEARLY -> Schedule(Frequency.YEARLY, byMonthDay = dayNumber, byMonth = month)
        }
    }.getOrNull()
    // From today on, never back (RecurringPlanner.startFor): a new item applies from the start of this period, a changed one from today.
    val today = LocalDate.now()
    val periodStart = remember(view.config, view.active) { RecurringPlanner.currentPeriodStart(view.config, view.active, today) }
    val startDate = schedule?.let { RecurringPlanner.startFor(existing, it, active, if (every > 1) firstMonth else null, periodStart, today) } ?: today
    // What saving does, before it does it: the rule as it would be saved, what it writes now and what it writes next.
    val startRule = schedule?.let {
        RecurringRule(existing?.id ?: PREVIEW_ID, "", Transaction(id = "", kind = kind, date = today, amountMinor = minor ?: 0, categoryId = category,
            createdAt = "", clientUpdatedAt = ""), it, startDate)
    }
    // The end, as the date it is saved with: picked, or the day the Nth payment falls due counting from the start.
    val payments = paymentsText.toIntOrNull()?.takeIf { it in 1..999 }
    val endDate = when (ends) {
        1 -> endPick
        2 -> startRule?.let { r -> payments?.let { n -> runCatching { Scheduler.endAfter(r, n) }.getOrNull() } }
        else -> null
    }
    val previewRule = startRule?.copy(endDate = endDate)
    // What the end leaves: its last payment and how many there are. An end that leaves none is a mistake the person should hear about now.
    val endPayments = if (ends != 0 && previewRule != null && endDate != null) runCatching {
        Scheduler.occurrences(previewRule, previewRule.startDate, endDate)
    }.getOrDefault(emptyList()) else emptyList()
    val endProblem = ends != 0 && schedule != null && (endDate == null || endPayments.isEmpty())
    val held = remember(view.rows) { view.rows.map { it.transaction.id }.toSet() }
    // What saving writes right now: asked of the planner itself, with the rule as it would be saved, so the screen never promises what the phone will not do.
    val writeNow = if (previewRule != null && auto && active && minor != null && minor > 0) {
        val rule = previewRule.copy(autoCreate = true, active = true)
        val config = view.config.copy(recurringRules = view.config.recurringRules.filter { it.id != rule.id } + rule)
        runCatching {
            RecurringPlanner.due(config, held, today, view.active, periodStart).filter { it.recurringRuleId == rule.id }.map { it.date }
        }.getOrDefault(emptyList())
    } else emptyList()
    // The coming year of an irregular monthly calendar, so the person sees exactly what they are setting.
    val nextDue = if (previewRule != null && frequency == Frequency.MONTHLY && every != 1) {
        val ahead = Scheduler.occurrences(previewRule, today.plusDays(1), today.plusMonths(36))
        val year = ahead.filter { it <= today.plusMonths(12) }
        if (year.size >= 3) year.take(6) else ahead.take(3)
    } else emptyList()
    val missing = listOfNotNull(
        if (name.isBlank()) stringResource(R.string.name) else null,
        if (minor == null || minor <= 0) stringResource(R.string.amount) else null,
        if (category == null) stringResource(R.string.category) else null,
    )
    val valid = missing.isEmpty() && schedule != null && !endProblem
    val blocker = when {
        missing.isNotEmpty() -> stringResource(R.string.still_needed, missing.joinToString(" · "))
        endProblem -> stringResource(R.string.end_problem)
        else -> null
    }

    FormScreen(
        title = stringResource(if (existing == null) R.string.add_recurring else R.string.edit),
        onClose = onDismiss,
        primaryLabel = stringResource(R.string.save),
        primaryEnabled = valid,
        blocker = blocker,
        onPrimary = {
            val everyone = view.config.activeMembers.map { it.id }
            val template = (t ?: Transaction(id = "", kind = kind, date = LocalDate.now(), amountMinor = 0, createdAt = "", clientUpdatedAt = "")).copy(
                kind = kind, amountMinor = minor!!, categoryId = category,
                accountId = account,
                paidByMemberId = t?.paidByMemberId ?: view.config.meMemberId,
                split = if (kind == TransactionKind.EXPENSE && everyone.size >= 2) (t?.split ?: Split.Equal(everyone)) else null,
                recurrence = Recurrence.FIXED, note = name.trim(), status = Status.ACTIVE,
            )
            onSave(RecurringRule(
                id = existing?.id ?: UUID.randomUUID().toString(), name = name.trim(), template = template, schedule = schedule!!,
                startDate = startDate, endDate = endDate,
                autoCreate = auto, active = active,
            ))
            onDismiss()
        },
        neutral = if (existing != null) ({ DeleteButton(onClick = { confirmingDelete = true }) }) else null,
    ) {
        SegmentedChoice(listOf(TransactionKind.EXPENSE to stringResource(R.string.kind_expense), TransactionKind.INCOME to stringResource(R.string.kind_income)),
            kind, { kind = it; category = null })
        OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name)) }, singleLine = true)
        OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.amount)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text(f.currency.code) })
        // The same tiles as the entry screen: icon and colour of each category, the most used first, and its subcategories below.
        FormLabel(stringResource(R.string.category))
        val topCategory = view.config.category(category)?.let { it.parentId ?: it.id }
        CategoryTiles(view, kind, topCategory, showAll = allCategories, onPick = { category = it }, onMore = { allCategories = !allCategories },
            columns = 3, sidePadding = 0.dp)
        val subcategories = view.config.categories.filter { it.parentId == topCategory && topCategory != null && !it.archived && it.appliesTo.allows(kind) }.sortedBy { it.sort }
        if (subcategories.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.subcategory_general), category == topCategory, { category = topCategory })
            for (sub in subcategories) Chip(sub.name, category == sub.id, { category = sub.id })
        }
        val accounts = view.config.accounts.filter { !it.archived || it.id == account }
        if (accounts.size > 1) {
            FormLabel(stringResource(R.string.account))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in accounts) Chip(a.name, a.id == account, { account = a.id })
            }
        }
        FormLabel(stringResource(R.string.repeats))
        SegmentedChoice(listOf(Frequency.WEEKLY to stringResource(R.string.weekly), Frequency.MONTHLY to stringResource(R.string.monthly),
            Frequency.YEARLY to stringResource(R.string.yearly), Frequency.DAILY to stringResource(R.string.daily)), frequency, { frequency = it })
        when (frequency) {
            Frequency.WEEKLY -> Revealed {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in 1..7) Chip(DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, locale), d in weekdays, {
                        weekdays = if (d in weekdays && weekdays.size > 1) weekdays - d else weekdays + d
                    })
                }
            }
            Frequency.MONTHLY, Frequency.YEARLY -> Revealed {
                OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.day_of_month)) },
                    supportingText = { Text(stringResource(R.string.day_of_month_help)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (frequency == Frequency.YEARLY) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in 1..12) Chip(Month.of(m).getDisplayName(TextStyle.SHORT, locale), m == month, { month = m })
                }
                if (frequency == Frequency.MONTHLY) {
                    FormLabel(stringResource(R.string.how_often))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.every_month), every == 1, { every = 1 })
                        for (n in listOf(2, 3, 6)) Chip(stringResource(R.string.every_n_months, n), every == n, { every = n })
                        Chip(stringResource(R.string.pick_months), every == 0, { every = 0 })
                    }
                    if (every == 0) {
                        FormLabel(stringResource(R.string.only_these_months))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (m in 1..12) Chip(Month.of(m).getDisplayName(TextStyle.SHORT_STANDALONE, locale), m in months, {
                                months = if (m in months && months.size > 1) months - m else months + m
                            })
                        }
                    } else if (every > 1) {
                        FormLabel(stringResource(R.string.first_payment_in))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Every N months from the first one: the months it falls due in light up, so the calendar is seen at once.
                            val dueMonths = (0 until 12).map { (firstMonth - 1 + it * every) % 12 + 1 }.toSet()
                            for (m in 1..12) Chip(Month.of(m).getDisplayName(TextStyle.SHORT_STANDALONE, locale), m in dueMonths, { firstMonth = m })
                        }
                    }
                    if (nextDue.isNotEmpty()) Text(stringResource(R.string.next_charges, nextDue.joinToString(" · ") { dateFormat.format(it) }),
                        style = FullaType.secondary, color = c.inkMuted)
                }
            }
            Frequency.DAILY -> Unit
        }
        FormLabel(stringResource(R.string.ends))
        SegmentedChoice(listOf(0 to stringResource(R.string.ends_never), 1 to stringResource(R.string.ends_on_date),
            2 to stringResource(R.string.ends_after_payments)), ends, { ends = it })
        if (ends != 0) Revealed {
            if (ends == 1) Chip(f.day(endPick), true, { pickingEnd = true }, leading = { Icon(Icons.Outlined.Event, null, Modifier.size(18.dp)) })
            if (ends == 2) OutlinedTextField(paymentsText, { paymentsText = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.payments_count)) }, supportingText = { Text(stringResource(R.string.payments_count_help)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (endProblem) Text(stringResource(R.string.end_problem), style = FullaType.secondary, color = c.danger)
            else if (endPayments.isNotEmpty()) Text(stringResource(R.string.last_payment, dateFormat.format(endPayments.last()), endPayments.size),
                style = FullaType.secondary, color = c.inkMuted)
        }
        SwitchRow(stringResource(R.string.write_itself), stringResource(R.string.write_itself_help), auto) { auto = it }
        if (existing != null) SwitchRow(stringResource(R.string.active), stringResource(R.string.active_help), active) { active = it }
        // What saving does, in one place: the summary and what it writes right now.
        if (schedule != null) Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.paperHigh).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                listOfNotNull(
                    scheduleText(schedule, startDate),
                    view.accountName(account),
                    if (ends != 0 && endDate != null && !endProblem) stringResource(R.string.until_date, dateFormat.format(endDate)) else null,
                    stringResource(if (auto) R.string.summary_writes_itself else R.string.summary_reminder_only),
                ).joinToString(" · "),
                style = FullaType.body, color = c.ink,
            )
            if (writeNow.isNotEmpty()) Text(
                if (writeNow.size <= 3) stringResource(R.string.write_now, writeNow.joinToString(" · ") { dateFormat.format(it) })
                else stringResource(R.string.write_now_many, writeNow.size, dateFormat.format(writeNow.first())),
                style = FullaType.secondary, color = c.inkMuted,
            )
        }
    }
    if (confirmingDelete && existing != null) {
        val wrote = view.rows.count { it.transaction.recurringRuleId == existing.id && it.transaction.isActive }
        FullaDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.delete_fixed_title, existing.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.delete_fixed_text), style = FullaType.secondary)
                    if (wrote > 0) DeleteButton(
                        onClick = { onDelete(existing, true); confirmingDelete = false; onDismiss() },
                        label = stringResource(R.string.delete_fixed_and_rows, wrote),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onDelete(existing, false); confirmingDelete = false; onDismiss() },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = c.danger)) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (pickingEnd) {
        val state = rememberDatePickerState(initialSelectedDateMillis = endPick.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickingEnd = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { endPick = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                pickingEnd = false
            }) { Text(stringResource(R.string.done)) }
        }) { DatePicker(state) }
    }
}
