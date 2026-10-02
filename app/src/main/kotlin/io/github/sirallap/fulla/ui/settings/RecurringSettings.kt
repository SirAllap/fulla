// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

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

/** Rent, salary, subscriptions: written down once, then written down by themselves on their day. */
@Composable
fun RecurringSettings(view: HouseholdView, canEdit: Boolean, change: Change) {
    val ledger = LocalContainer.current.ledger
    val f = view.formats
    val c = FullaTheme.colors
    var editing by remember { mutableStateOf<RecurringRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmingSkip by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // What fell due before this period and was never written: offered, not written behind the person's back.
    val leftOut = remember(view) {
        val today = LocalDate.now()
        runCatching {
            RecurringPlanner.leftOut(view.config, view.rows.map { it.transaction.id }.toSet(), view.active, today,
                RecurringPlanner.currentPeriodStart(view.config, view.active, today))
        }.getOrDefault(emptyList())
    }
    Column {
        Text(stringResource(R.string.recurring_text), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(20.dp))
        if (canEdit && leftOut.isNotEmpty()) {
            Section(stringResource(R.string.fixed_missed), top = 8.dp)
            Text(stringResource(R.string.fixed_missed_text), style = FullaType.secondary, color = c.inkMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            for (o in leftOut) {
                ListRow(o.rule.name, context = f.day(o.date), end = { AmountText(f.money(o.rule.template.amountMinor), color = c.ink) })
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { leftOut.forEach { ledger.applyRecurring(view.id, it.rule.id, it.date) } } }) {
                    Text(stringResource(R.string.fixed_missed_apply, leftOut.size))
                }
                TextButton(onClick = { confirmingSkip = true }) {
                    Text(stringResource(R.string.fixed_missed_skip))
                }
            }
            Section(stringResource(R.string.settings_recurring), top = 16.dp)
        }
        val shown = view.config.recurringRules.filter { !it.archived }
        if (shown.isEmpty()) {
            EmptyState(Icons.Outlined.Event, stringResource(R.string.no_recurring_title), stringResource(R.string.no_recurring_text))
        }
        for (r in shown.sortedWith(compareBy({ !it.active }, { it.name }))) {
            val income = r.template.kind == TransactionKind.INCOME
            val ended = r.endDate
            ListRow(r.name, titleColor = if (r.active) c.ink else c.inkMuted,
                context = scheduleText(r.schedule, r.startDate) + (runCatching { Scheduler.progress(r, LocalDate.now()) }.getOrNull()
                    ?.let { (done, all) -> " · " + stringResource(R.string.payment_progress, done, all) } ?: ""),
                detail = when {
                    !r.active -> stringResource(R.string.paused)
                    ended != null && ended < LocalDate.now() -> stringResource(R.string.fixed_ended, f.day(ended))
                    else -> view.categoryName(r.template.categoryId)
                },
                end = { AmountText(f.money(r.template.amountMinor, signed = income), color = if (income) c.moneyIn else c.ink) },
                onClick = if (canEdit) ({ editing = r }) else null)
        }
        if (canEdit) ListRow(stringResource(R.string.add_recurring), icon = Icons.Outlined.Add, onClick = { creating = true })
    }
    if (confirmingSkip) {
        FullaDialog(
            onDismissRequest = { confirmingSkip = false },
            title = { Text(stringResource(R.string.fixed_missed_skip)) },
            text = { Text(stringResource(R.string.fixed_missed_skip_confirm, leftOut.size), style = FullaType.secondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSkip = false
                    scope.launch { leftOut.forEach { ledger.skipRecurring(view.id, it.rule.id, it.date) } }
                }) { Text(stringResource(R.string.fixed_missed_skip)) }
            },
            dismissButton = { TextButton(onClick = { confirmingSkip = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (creating || editing != null) {
        RecurringDialog(view, editing, onDismiss = { creating = false; editing = null }, onDelete = { rule, withRows ->
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

@Composable
private fun RecurringDialog(view: HouseholdView, existing: RecurringRule?, onDismiss: () -> Unit, onDelete: (RecurringRule, Boolean) -> Unit, onSave: (RecurringRule) -> Unit) {
    val f = view.formats
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
    val minor = MoneyParser.parse(amount, f.currency, f.decimalStyle)
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
    val valid = name.isNotBlank() && minor != null && minor > 0 && category != null && schedule != null && !endProblem

    FullaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.add_recurring else R.string.edit)) },
        text = {
            androidx.compose.runtime.CompositionLocalProvider(io.github.sirallap.fulla.ui.components.LocalRowInset provides 0.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name)) }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.kind_expense), kind == TransactionKind.EXPENSE, { kind = TransactionKind.EXPENSE; category = null })
                    Chip(stringResource(R.string.kind_income), kind == TransactionKind.INCOME, { kind = TransactionKind.INCOME; category = null })
                }
                OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.amount)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text(f.currency.code) })
                // The same tiles as the entry screen: icon and colour of each category, the most used first, and its subcategories below.
                FormLabel(stringResource(R.string.category))
                val topCategory = view.config.category(category)?.let { it.parentId ?: it.id }
                CategoryTiles(view, kind, topCategory, showAll = allCategories, onPick = { category = it }, onMore = { allCategories = !allCategories },
                    columns = 3, sidePadding = 0.dp)
                val subcategories = view.config.categories.filter { it.parentId == topCategory && topCategory != null && !it.archived && it.appliesTo.allows(kind) }.sortedBy { it.sort }
                if (subcategories.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.subcategory_general), category == topCategory, { category = topCategory })
                    for (sub in subcategories) Chip(sub.name, category == sub.id, { category = sub.id })
                }
                val accounts = view.config.accounts.filter { !it.archived || it.id == account }
                if (accounts.size > 1) {
                    FormLabel(stringResource(R.string.account))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (a in accounts) Chip(a.name, a.id == account, { account = a.id })
                    }
                }
                FormLabel(stringResource(R.string.repeats))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((fr, label) in listOf(Frequency.WEEKLY to R.string.weekly, Frequency.MONTHLY to R.string.monthly, Frequency.YEARLY to R.string.yearly, Frequency.DAILY to R.string.daily)) {
                        Chip(stringResource(label), frequency == fr, { frequency = fr })
                    }
                }
                when (frequency) {
                    Frequency.WEEKLY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (d in 1..7) Chip(DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, locale), d in weekdays, {
                            weekdays = if (d in weekdays && weekdays.size > 1) weekdays - d else weekdays + d
                        })
                    }
                    Frequency.MONTHLY, Frequency.YEARLY -> {
                        OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.day_of_month)) },
                            supportingText = { Text(stringResource(R.string.day_of_month_help)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        if (frequency == Frequency.YEARLY) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (m in 1..12) Chip(Month.of(m).getDisplayName(TextStyle.SHORT, locale), m == month, { month = m })
                        }
                        if (frequency == Frequency.MONTHLY) {
                            FormLabel(stringResource(R.string.how_often))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Chip(stringResource(R.string.every_month), every == 1, { every = 1 })
                                for (n in listOf(2, 3, 6)) Chip(stringResource(R.string.every_n_months, n), every == n, { every = n })
                                Chip(stringResource(R.string.pick_months), every == 0, { every = 0 })
                            }
                            if (every == 0) {
                                FormLabel(stringResource(R.string.only_these_months))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (m in 1..12) Chip(Month.of(m).getDisplayName(TextStyle.SHORT_STANDALONE, locale), m in months, {
                                        months = if (m in months && months.size > 1) months - m else months + m
                                    })
                                }
                            } else if (every > 1) {
                                FormLabel(stringResource(R.string.first_payment_in))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // Every N months from the first one: the months it falls due in light up, so the calendar is seen at once.
                                    val dueMonths = (0 until 12).map { (firstMonth - 1 + it * every) % 12 + 1 }.toSet()
                                    for (m in 1..12) Chip(Month.of(m).getDisplayName(TextStyle.SHORT_STANDALONE, locale), m in dueMonths, { firstMonth = m })
                                }
                            }
                            if (nextDue.isNotEmpty()) Text(stringResource(R.string.next_charges, nextDue.joinToString(" · ") { dateFormat.format(it) }),
                                style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
                        }
                    }
                    Frequency.DAILY -> Unit
                }
                FormLabel(stringResource(R.string.ends))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.ends_never), ends == 0, { ends = 0 })
                    Chip(stringResource(R.string.ends_on_date), ends == 1, { ends = 1 })
                    Chip(stringResource(R.string.ends_after_payments), ends == 2, { ends = 2 })
                }
                if (ends == 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(f.day(endPick), true, { pickingEnd = true }, leading = { Icon(Icons.Outlined.Event, null, Modifier.size(18.dp)) })
                }
                if (ends == 2) OutlinedTextField(paymentsText, { paymentsText = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.payments_count)) }, supportingText = { Text(stringResource(R.string.payments_count_help)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (ends != 0) {
                    if (endProblem) Text(stringResource(R.string.end_problem), style = FullaType.secondary, color = FullaTheme.colors.danger)
                    else Text(stringResource(R.string.last_payment, dateFormat.format(endPayments.last()), endPayments.size),
                        style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
                }
                SwitchRow(stringResource(R.string.write_itself), stringResource(R.string.write_itself_help), auto) { auto = it }
                if (existing != null) SwitchRow(stringResource(R.string.active), stringResource(R.string.active_help), active) { active = it }
                // What saving does, in one place: the summary, what it writes right now, and what is still missing.
                val missing = if (valid) emptyList() else listOfNotNull(
                    if (name.isBlank()) stringResource(R.string.name) else null,
                    if (minor == null || minor <= 0) stringResource(R.string.amount) else null,
                    if (category == null) stringResource(R.string.category) else null,
                )
                if (schedule != null || missing.isNotEmpty() || writeNow.isNotEmpty()) Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(FullaTheme.colors.paperHigh).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (schedule != null) Text(
                        listOfNotNull(
                            scheduleText(schedule, startDate),
                            view.accountName(account),
                            if (ends != 0 && endDate != null && !endProblem) stringResource(R.string.until_date, dateFormat.format(endDate)) else null,
                            stringResource(if (auto) R.string.summary_writes_itself else R.string.summary_reminder_only),
                        ).joinToString(" · "),
                        style = FullaType.body, color = FullaTheme.colors.ink,
                    )
                    if (writeNow.isNotEmpty()) Text(
                        if (writeNow.size <= 3) stringResource(R.string.write_now, writeNow.joinToString(" · ") { dateFormat.format(it) })
                        else stringResource(R.string.write_now_many, writeNow.size, dateFormat.format(writeNow.first())),
                        style = FullaType.secondary, color = FullaTheme.colors.inkMuted,
                    )
                    if (missing.isNotEmpty()) Text(stringResource(R.string.still_needed, missing.joinToString(" · ")),
                        style = FullaType.secondary, color = FullaTheme.colors.warning)
                }
            }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
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
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        neutralButton = if (existing != null) ({ io.github.sirallap.fulla.ui.components.DeleteButton(onClick = { confirmingDelete = true }) }) else null,
    )
    if (confirmingDelete && existing != null) {
        val wrote = view.rows.count { it.transaction.recurringRuleId == existing.id && it.transaction.isActive }
        FullaDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.delete_fixed_title, existing.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.delete_fixed_text), style = FullaType.secondary)
                    if (wrote > 0) io.github.sirallap.fulla.ui.components.DeleteButton(
                        onClick = { onDelete(existing, true); confirmingDelete = false; onDismiss() },
                        label = stringResource(R.string.delete_fixed_and_rows, wrote),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onDelete(existing, false); confirmingDelete = false; onDismiss() },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = FullaTheme.colors.danger)) { Text(stringResource(R.string.delete)) }
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

/** The small heading of a group of options in a form, with room above it so the groups can be told apart. */
@Composable
private fun FormLabel(text: String) {
    Text(text, style = FullaType.label, color = FullaTheme.colors.inkMuted, modifier = Modifier.padding(top = 8.dp))
}
