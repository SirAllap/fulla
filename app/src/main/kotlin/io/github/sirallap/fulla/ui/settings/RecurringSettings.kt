// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
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
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.Chip
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.SwitchRow
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

@Composable
private fun scheduleText(s: Schedule, start: LocalDate): String {
    val locale = Locale.getDefault()
    return when (s.frequency) {
        Frequency.DAILY -> stringResource(R.string.every_day)
        Frequency.WEEKLY -> stringResource(R.string.weekly_on, s.byWeekday.joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) })
        Frequency.MONTHLY -> stringResource(R.string.monthly_on_day, s.byMonthDay ?: start.dayOfMonth)
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
    Column {
        Text(stringResource(R.string.recurring_text), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(20.dp))
        if (view.config.recurringRules.isEmpty()) {
            EmptyState(Icons.Outlined.Event, stringResource(R.string.no_recurring_title), stringResource(R.string.no_recurring_text))
        }
        for (r in view.config.recurringRules.sortedWith(compareBy({ !it.active }, { it.name }))) {
            val income = r.template.kind == TransactionKind.INCOME
            ListRow(r.name, titleColor = if (r.active) c.ink else c.inkMuted,
                context = scheduleText(r.schedule, r.startDate),
                detail = if (!r.active) stringResource(R.string.paused) else view.categoryName(r.template.categoryId),
                end = { AmountText(f.money(r.template.amountMinor, signed = income), color = if (income) c.moneyIn else c.ink) },
                onClick = if (canEdit) ({ editing = r }) else null)
        }
        if (canEdit) ListRow(stringResource(R.string.add_recurring), icon = Icons.Outlined.Add, onClick = { creating = true })
    }
    if (creating || editing != null) {
        RecurringDialog(view, editing, onDismiss = { creating = false; editing = null }) { rule ->
            change { api -> ledger.upsert(view.id, Structure.RECURRING, Wire.recurring(rule), api) }
        }
    }
}

@Composable
private fun RecurringDialog(view: HouseholdView, existing: RecurringRule?, onDismiss: () -> Unit, onSave: (RecurringRule) -> Unit) {
    val f = view.formats
    val locale = Locale.getDefault()
    val t = existing?.template
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(t?.kind ?: TransactionKind.EXPENSE) }
    var amount by remember { mutableStateOf(t?.let { f.plain(it.amountMinor) } ?: "") }
    var category by remember { mutableStateOf(t?.categoryId) }
    var frequency by remember { mutableStateOf(existing?.schedule?.frequency ?: Frequency.MONTHLY) }
    var day by remember { mutableStateOf((existing?.schedule?.byMonthDay ?: LocalDate.now().dayOfMonth).toString()) }
    var weekdays by remember { mutableStateOf(existing?.schedule?.byWeekday?.toSet() ?: setOf(LocalDate.now().dayOfWeek.value)) }
    var month by remember { mutableStateOf(existing?.schedule?.byMonth ?: LocalDate.now().monthValue) }
    var auto by remember { mutableStateOf(existing?.autoCreate ?: true) }
    var active by remember { mutableStateOf(existing?.active ?: true) }
    val minor = MoneyParser.parse(amount, f.currency, f.decimalStyle)
    val dayNumber = day.toIntOrNull()?.takeIf { it in 1..31 }
    val schedule = runCatching {
        when (frequency) {
            Frequency.DAILY -> Schedule(Frequency.DAILY)
            Frequency.WEEKLY -> Schedule(Frequency.WEEKLY, byWeekday = weekdays.sorted())
            Frequency.MONTHLY -> Schedule(Frequency.MONTHLY, byMonthDay = dayNumber)
            Frequency.YEARLY -> Schedule(Frequency.YEARLY, byMonthDay = dayNumber, byMonth = month)
        }
    }.getOrNull()
    val valid = name.isNotBlank() && minor != null && minor > 0 && category != null && schedule != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.add_recurring else R.string.edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name)) }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.kind_expense), kind == TransactionKind.EXPENSE, { kind = TransactionKind.EXPENSE; category = null })
                    Chip(stringResource(R.string.kind_income), kind == TransactionKind.INCOME, { kind = TransactionKind.INCOME; category = null })
                }
                OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.amount)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text(f.currency.code) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (cat in view.config.categories.filter { !it.archived && it.appliesTo.allows(kind) }) Chip(cat.name, cat.id == category, { category = cat.id })
                }
                Text(stringResource(R.string.repeats), style = FullaType.label, color = FullaTheme.colors.inkMuted)
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
                    }
                    Frequency.DAILY -> Unit
                }
                SwitchRow(stringResource(R.string.write_itself), stringResource(R.string.write_itself_help), auto) { auto = it }
                if (existing != null) SwitchRow(stringResource(R.string.active), stringResource(R.string.active_help), active) { active = it }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val everyone = view.config.activeMembers.map { it.id }
                val template = (t ?: Transaction(id = "", kind = kind, date = LocalDate.now(), amountMinor = 0, createdAt = "", clientUpdatedAt = "")).copy(
                    kind = kind, amountMinor = minor!!, categoryId = category,
                    accountId = t?.accountId ?: view.config.accounts.firstOrNull { !it.archived }?.id,
                    paidByMemberId = t?.paidByMemberId ?: view.config.meMemberId,
                    split = if (kind == TransactionKind.EXPENSE && everyone.size >= 2) (t?.split ?: Split.Equal(everyone)) else null,
                    recurrence = Recurrence.FIXED, note = name.trim(), status = Status.ACTIVE,
                )
                onSave(RecurringRule(
                    id = existing?.id ?: UUID.randomUUID().toString(), name = name.trim(), template = template, schedule = schedule!!,
                    startDate = existing?.startDate ?: LocalDate.now().withDayOfMonth(1), endDate = existing?.endDate,
                    autoCreate = auto, active = active,
                ))
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
