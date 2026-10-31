// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Flag
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.goals.Goal
import io.github.sirallap.fulla.core.goals.Goals
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.roles.Permissions
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.CardGroup
import io.github.sirallap.fulla.ui.components.Chip
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.components.ProgressLine
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.theme.FullaType
import io.github.sirallap.fulla.ui.components.SwitchRow
import io.github.sirallap.fulla.ui.components.rememberToday
import io.github.sirallap.fulla.ui.theme.FullaTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Settings › Savings goals: each goal with how far it has got, and the way to add, change or archive one. */
@Composable
fun GoalsSettings(view: HouseholdView, change: Change) {
    val ledger = LocalContainer.current.ledger
    var editing by remember { mutableStateOf<Goal?>(null) }
    var creating by remember { mutableStateOf(false) }
    val canEdit = view.me?.let { Permissions.canEditTrips(it) } == true
    val live = view.config.goals.filter { !it.archived }

    // As in the mockup: a card per goal, a line on how saving works, "New goal" as the screen's button.
    if (canEdit) io.github.sirallap.fulla.ui.components.ScreenPrimaryAction(stringResource(R.string.goal_new)) { creating = true }
    Column(Modifier.padding(top = 8.dp)) {
        if (live.isEmpty()) {
            EmptyState(Icons.Outlined.Flag, stringResource(R.string.goals_empty_title), stringResource(R.string.goals_empty_text))
        } else {
            live.forEach { g -> GoalRow(view, g, onClick = if (canEdit) ({ editing = g }) else null) }
            Text(stringResource(R.string.goal_help), style = FullaType.secondary, color = FullaTheme.colors.inkMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        val archived = view.config.goals.filter { it.archived }
        if (archived.isNotEmpty()) {
            Section(stringResource(R.string.archived))
            archived.forEach { g -> GoalRow(view, g, dimmed = true, onClick = if (canEdit) ({ editing = g }) else null) }
        }
    }

    if (canEdit && (creating || editing != null)) {
        GoalDialog(view, editing, onDismiss = { creating = false; editing = null }) { goal ->
            change { api -> ledger.upsert(view.id, Structure.GOAL, Wire.goal(goal), api) }
        }
    }
}

/** A goal and how far it has got: saved of target, what to put aside a month, and a line that fills up. */
@Composable
fun GoalRow(view: HouseholdView, goal: Goal, dimmed: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = FullaTheme.colors
    val f = view.formats
    val today = rememberToday()
    val balances = remember(view, today) { view.analytics.accountBalances(view.active, view.config.accounts, today) }
    val p = remember(goal, balances, today) { Goals.progress(goal, balances[goal.accountId] ?: 0, today) }
    val account = view.accountName(goal.accountId) ?: "?"
    val detail = when {
        p.reached -> stringResource(R.string.goal_reached)
        p.late -> stringResource(R.string.goal_late, f.money(p.leftMinor))
        p.perMonthMinor != null -> stringResource(R.string.goal_per_month, f.money(p.perMonthMinor!!), f.day(goal.targetDate!!))
        else -> stringResource(R.string.goal_left, f.money(p.leftMinor))
    }
    // A card, as in the mockup: name and date, a thick bar, saved of target and what it takes, the account.
    io.github.sirallap.fulla.ui.components.ItemCard(dimmed = dimmed, onClick = onClick) {
        io.github.sirallap.fulla.ui.components.Spread(startShare = 0.6f, start = {
            Text(goal.name, style = FullaType.title, color = if (dimmed) c.inkMuted else c.ink)
        }, end = {
            Text(goal.targetDate?.let { f.day(it) } ?: stringResource(R.string.goal_no_date), style = FullaType.secondary, color = c.inkMuted)
        })
        ProgressLine(p.fraction.toFloat(), if (p.reached) c.moneyIn else c.highlight)
        io.github.sirallap.fulla.ui.components.Spread(startShare = 0.5f, start = {
            Text(stringResource(R.string.budget_of, f.money(p.savedMinor), f.money(goal.targetMinor)), style = FullaType.secondary, color = c.ink)
        }, end = {
            Text(detail, style = FullaType.secondary, color = if (p.late) c.warning else c.inkMuted)
        })
        Text(stringResource(R.string.goal_in_account, account), style = FullaType.label, color = c.inkMuted)
    }
}

@Composable
private fun GoalDialog(view: HouseholdView, existing: Goal?, onDismiss: () -> Unit, onSave: (Goal) -> Unit) {
    val f = view.formats
    val c = FullaTheme.colors
    val accounts = view.config.accounts.filter { !it.archived || it.id == existing?.accountId }
    var targetText by remember { mutableStateOf(existing?.targetMinor?.let { f.plain(it) } ?: "") }
    // A new goal starts in the household's savings account, if it has one.
    var accountId by remember {
        mutableStateOf(existing?.accountId
            ?: (accounts.firstOrNull { it.type == io.github.sirallap.fulla.core.model.AccountType.SAVINGS } ?: accounts.firstOrNull())?.id)
    }
    var date by remember { mutableStateOf(existing?.targetDate) }
    var archived by remember { mutableStateOf(existing?.archived ?: false) }
    var picking by remember { mutableStateOf(false) }

    // What a person types is read as typed, either decimal key (MoneyParser.parseTyped); text that is not an amount says so.
    val target = targetText.takeIf { it.isNotBlank() }?.let { MoneyParser.parseTyped(it, f.currency, f.decimalStyle) }
    val problem = when {
        targetText.isBlank() -> null
        target == null || target <= 0 -> stringResource(R.string.goal_target_invalid)
        else -> null
    }

    EditDialog(
        title = existing?.name ?: stringResource(R.string.goal_new),
        initial = existing?.name ?: "",
        label = stringResource(R.string.goal_name),
        onDismiss = onDismiss,
        enabledFor = { name -> name.length <= 40 && target != null && target > 0 && accountId != null },
        extra = {
            OutlinedTextField(
                targetText, { targetText = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = { Text(stringResource(R.string.goal_target)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text(f.currency.code) },
            )
            problem?.let { Text(it, color = c.danger) }
            Section(stringResource(R.string.goal_account), top = 8.dp)
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in accounts) Chip(a.name, a.id == accountId, { accountId = a.id })
            }
            Section(stringResource(R.string.goal_date), top = 8.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.goal_no_date), date == null, { date = null })
                Chip(date?.let { f.day(it) } ?: stringResource(R.string.goal_pick_date), date != null, { picking = true })
            }
            Text(stringResource(R.string.goal_help), color = c.inkMuted, modifier = Modifier.padding(top = 4.dp))
            if (existing != null) {
                SwitchRow(stringResource(R.string.archive), stringResource(R.string.goal_archive_help), archived) { archived = it }
            }
        },
    ) { name ->
        onSave(Goal(existing?.id ?: UUID.randomUUID().toString(), name, target ?: return@EditDialog, accountId ?: return@EditDialog, date, archived))
    }

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now().plusMonths(6))
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { picking = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                picking = false
            }) { Text(stringResource(R.string.done)) }
        }) { DatePicker(state) }
    }
}
