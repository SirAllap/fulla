// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.goals.Goal
import io.github.sirallap.fulla.core.goals.GoalEntry
import io.github.sirallap.fulla.core.goals.GoalProgress
import io.github.sirallap.fulla.core.goals.Goals
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.roles.Permissions
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.Chip
import io.github.sirallap.fulla.ui.components.ConfirmDialog
import io.github.sirallap.fulla.ui.components.DetailChip
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.FormLabel
import io.github.sirallap.fulla.ui.components.FormScreen
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.PickSheet
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.components.Revealed
import io.github.sirallap.fulla.ui.components.ScreenPrimaryAction
import io.github.sirallap.fulla.ui.components.SecondaryButton
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.SegmentedChoice
import io.github.sirallap.fulla.ui.components.Spread
import io.github.sirallap.fulla.ui.components.SwitchRow
import io.github.sirallap.fulla.ui.components.liquid
import io.github.sirallap.fulla.ui.components.rememberToday
import io.github.sirallap.fulla.ui.theme.FullaMotion
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Savings goals. A goal keeps a jar of its own (what is put in and taken
 * out, 0024_goal_jars.sql) or, for money kept apart in an account just for
 * it, reads that account's balance. The list shows each goal filling up;
 * a goal opens on its jar: put money in with a tap, the pace to its date,
 * every movement, and a cheer at each quarter of the way.
 */
@Composable
fun GoalsSettings(view: HouseholdView, change: Change) {
    val ledger = LocalContainer.current.ledger
    var open by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val canEdit = view.me?.let { Permissions.canEditTrips(it) } == true
    val live = view.config.goals.filter { !it.archived }
    val save: (Goal) -> Unit = { goal -> change { api -> ledger.upsert(view.id, Structure.GOAL, Wire.goal(goal), api) } }

    if (canEdit) ScreenPrimaryAction(stringResource(R.string.goal_new)) { creating = true }
    Column(Modifier.padding(top = 8.dp)) {
        if (live.isEmpty()) {
            EmptyState(Icons.Outlined.Flag, stringResource(R.string.goals_empty_title), stringResource(R.string.goals_empty_text))
        } else {
            live.forEach { g -> GoalRow(view, g, onClick = { open = g.id }) }
        }
        val archived = view.config.goals.filter { it.archived }
        if (archived.isNotEmpty()) {
            Section(stringResource(R.string.archived))
            archived.forEach { g -> GoalRow(view, g, dimmed = true, onClick = { open = g.id }) }
        }
    }

    if (canEdit && creating) GoalForm(view, null, onDismiss = { creating = false }, onSave = save)
    // Looked up by id each time, so the screen shows what another phone has just put in.
    open?.let { id -> view.config.goals.firstOrNull { it.id == id } }?.let { g ->
        GoalScreen(view, g, canEdit, onDismiss = { open = null }, save = save)
    }
}

@Composable
private fun progressOf(view: HouseholdView, goal: Goal): GoalProgress {
    val today = rememberToday()
    val balances = remember(view, today) { view.analytics.accountBalances(view.active, view.config.accounts, today) }
    return remember(goal, balances, today) { Goals.progress(goal, Goals.saved(goal, balances), today) }
}

@Composable
private fun paceText(view: HouseholdView, goal: Goal, p: GoalProgress): String {
    val f = view.formats
    return when {
        p.reached -> stringResource(R.string.goal_reached)
        p.late -> stringResource(R.string.goal_late, f.money(p.leftMinor))
        p.perMonthMinor != null -> stringResource(R.string.goal_per_month, f.money(p.perMonthMinor!!), f.day(goal.targetDate!!))
        else -> stringResource(R.string.goal_left, f.money(p.leftMinor))
    }
}

/** A goal as a card that fills with the jar's liquid as far as it has got: name and how far, saved of target, the pace. */
@Composable
fun GoalRow(view: HouseholdView, goal: Goal, dimmed: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = FullaTheme.colors
    val f = view.formats
    val p = progressOf(view, goal)
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).clip(shape)
            .background(if (dimmed) c.paper else c.paperHigh)
            .liquid(p.fraction.toFloat(), phase = (goal.id.hashCode() % 7).toFloat())
            .border(1.dp, c.line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spread(startShare = 0.6f, start = {
            Text(goal.name, style = FullaType.title, color = if (dimmed) c.inkMuted else c.ink)
        }, end = {
            Text(stringResource(R.string.goal_percent, (p.fraction * 100).toInt()), style = FullaType.amount, color = c.ink)
        })
        Text(stringResource(R.string.budget_of, f.money(p.savedMinor), f.money(goal.targetMinor)), style = FullaType.body, color = c.ink)
        Text(listOfNotNull(paceText(view, goal, p), goal.targetDate?.let { f.day(it) }).joinToString(" · "),
            style = FullaType.secondary, color = if (p.late) c.warning else c.inkMuted)
    }
}

/**
 * One goal, on its jar: how much is in it and how far that is, the pace to
 * its date, quick amounts to put in, every movement. Putting money in that
 * crosses a quarter of the way says so.
 */
@Composable
private fun GoalScreen(view: HouseholdView, goal: Goal, canEdit: Boolean, onDismiss: () -> Unit, save: (Goal) -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val p = progressOf(view, goal)
    var editing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Int?>(null) } // +1 put in, -1 take out
    var removing by remember { mutableStateOf<GoalEntry?>(null) }
    var cheer by remember { mutableStateOf<Int?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val dbUpdate = stringResource(R.string.goal_db_update)

    // Writes an entry; on a connected household whose database is too old to keep it, says so instead of losing it.
    fun record(entry: GoalEntry) {
        scope.launch {
            if (view.state.connected && container.backendBehind()) { problem = dbUpdate; return@launch }
            problem = null
            Goals.milestone(goal.jarMinor, goal.jarMinor + entry.amountMinor, goal.targetMinor)?.let { cheer = it }
            save(goal.copy(contributions = goal.contributions + entry))
        }
    }
    fun entry(amount: Long, note: String? = null) =
        GoalEntry(UUID.randomUUID().toString(), LocalDate.now(), amount, note?.trim()?.takeIf { it.isNotEmpty() }, view.config.meMemberId)

    FormScreen(
        title = goal.name,
        onClose = onDismiss,
        primaryLabel = stringResource(if (goal.ownJar) R.string.goal_put_in else R.string.goal_edit),
        onPrimary = { if (goal.ownJar) moving = 1 else editing = true },
        primaryEnabled = canEdit,
        neutral = if (canEdit && goal.ownJar) ({ SecondaryButton(stringResource(R.string.goal_edit), { editing = true }) }) else null,
    ) {
        cheer?.let { q ->
            // A cheer at each quarter of the way, popping in.
            val pop = remember(q) { Animatable(if (FullaMotion.reduced()) 1f else 0.6f) }
            val settle = FullaMotion.settle<Float>(FullaMotion.reduced())
            LaunchedEffect(q) { pop.animateTo(1f, settle) }
            Text(
                stringResource(when (q) { 25 -> R.string.goal_quarter_25; 50 -> R.string.goal_quarter_50; 75 -> R.string.goal_quarter_75; else -> R.string.goal_quarter_100 }),
                style = FullaType.title, color = c.onHighlight,
                modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                    .clip(RoundedCornerShape(16.dp)).background(c.highlight).padding(16.dp),
            )
        }
        // The jar: what it holds, of what, filled to how far.
        Column(
            Modifier.fillMaxWidth().heightIn(min = 180.dp).clip(RoundedCornerShape(24.dp)).background(c.paperHigh)
                .liquid(p.fraction.toFloat(), vertical = true).border(1.dp, c.line, RoundedCornerShape(24.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.goal_percent, (p.fraction * 100).toInt()), style = FullaType.secondary, color = c.inkMuted)
            Text(f.money(p.savedMinor), style = FullaType.heroFigure, color = c.ink, maxLines = 1, softWrap = false)
            Text(stringResource(R.string.goal_of_target, f.money(goal.targetMinor)), style = FullaType.body, color = c.ink)
            Text(paceText(view, goal, p), style = FullaType.secondary, color = if (p.late) c.warning else c.inkMuted)
            // The four quarters, reached ones filled.
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (q in listOf(25, 50, 75, 100)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (p.fraction * 100 >= q) c.highlight else c.line))
                }
            }
        }
        problem?.let { Text(it, style = FullaType.secondary, color = c.warning) }
        if (!goal.ownJar) {
            Text(stringResource(R.string.goal_balance_of, view.accountName(goal.accountId) ?: "?"), style = FullaType.secondary, color = c.inkMuted)
        } else {
            if (canEdit) {
                // Quick amounts in whole units, larger for a larger goal: one tap each.
                val unit = (0 until f.currency.minorUnits).fold(1L) { acc, _ -> acc * 10 }
                val base = listOf(10L, 20L, 50L, 100L).map { it * unit }
                val steps = if (goal.targetMinor >= 5_000 * unit) base.map { it * 5 } else base
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in steps) Chip(stringResource(R.string.goal_quick, f.whole(s)), false, { record(entry(s)) })
                }
                SecondaryButton(stringResource(R.string.goal_take_out), { moving = -1 })
            }
            FormLabel(stringResource(R.string.goal_history))
            val entries = goal.entries
            if (entries.isEmpty()) Text(stringResource(R.string.goal_no_entries), style = FullaType.secondary, color = c.inkMuted)
            for (e in entries) {
                val who = e.memberId?.let { view.memberName(it) }
                ListRow(
                    e.note ?: who ?: f.day(e.date),
                    context = listOfNotNull(f.day(e.date).takeIf { e.note != null || who != null }, who.takeIf { e.note != null }).joinToString(" · ").ifBlank { null },
                    onClick = if (canEdit) ({ removing = e }) else null,
                    end = { AmountText(f.money(e.amountMinor, signed = true), color = if (e.amountMinor > 0) c.moneyIn else c.ink) },
                )
            }
        }
    }

    moving?.let { sign -> MoveSheet(view, sign, onDismiss = { moving = null }) { amount, note -> moving = null; record(entry(sign * amount, note)) } }
    removing?.let { e ->
        ConfirmDialog(stringResource(R.string.goal_entry_remove), stringResource(R.string.goal_entry_remove_text),
            stringResource(R.string.goal_entry_remove_confirm), onConfirm = {
                removing = null
                save(goal.copy(contributions = goal.contributions.map { if (it.id == e.id) it.copy(deleted = true) else it }))
            }, onDismiss = { removing = null })
    }
    if (editing) GoalForm(view, goal, onDismiss = { editing = false }, onSave = save)
}

/** How much to put in or take out, and a note: a sheet over the goal, its button saying the amount. */
@Composable
private fun MoveSheet(view: HouseholdView, sign: Int, onDismiss: () -> Unit, onDone: (Long, String?) -> Unit) {
    val f = view.formats
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amount = text.takeIf { it.isNotBlank() }?.let { MoneyParser.parseTyped(it, f.currency, f.decimalStyle) }?.takeIf { it > 0 }
    PickSheet(stringResource(if (sign > 0) R.string.goal_put_in else R.string.goal_take_out), onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.goal_amount)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text(f.currency.code) })
            OutlinedTextField(note, { note = it.take(80) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.goal_note)) }, singleLine = true)
            PrimaryButton(
                amount?.let { stringResource(if (sign > 0) R.string.goal_put_in_amount else R.string.goal_take_out_amount, f.money(it)) }
                    ?: stringResource(if (sign > 0) R.string.goal_put_in else R.string.goal_take_out),
                onClick = { amount?.let { onDone(it, note) } }, enabled = amount != null,
            )
        }
    }
}

/**
 * Making or changing a goal, as a form screen: what for, how much, by when,
 * and where the money is: a jar of its own (the usual), or an account kept
 * just for it. A new jar can start with what is already saved.
 */
@Composable
private fun GoalForm(view: HouseholdView, existing: Goal?, onDismiss: () -> Unit, onSave: (Goal) -> Unit) {
    val f = view.formats
    val c = FullaTheme.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val accounts = view.config.accounts.filter { !it.archived || it.id == existing?.accountId }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var targetText by remember { mutableStateOf(existing?.targetMinor?.let { f.plain(it) } ?: "") }
    var ownJar by remember { mutableStateOf(existing?.ownJar ?: true) }
    var accountId by remember {
        mutableStateOf(existing?.accountId
            ?: (accounts.firstOrNull { it.type == io.github.sirallap.fulla.core.model.AccountType.SAVINGS } ?: accounts.firstOrNull())?.id)
    }
    var alreadyText by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(existing?.targetDate) }
    var archived by remember { mutableStateOf(existing?.archived ?: false) }
    var picking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val dbUpdate = stringResource(R.string.goal_db_update)

    val target = targetText.takeIf { it.isNotBlank() }?.let { MoneyParser.parseTyped(it, f.currency, f.decimalStyle) }
    val already = alreadyText.takeIf { it.isNotBlank() }?.let { MoneyParser.parseTyped(it, f.currency, f.decimalStyle) }
    val blocker = when {
        name.isBlank() -> stringResource(R.string.goal_name)
        target == null || target <= 0 -> stringResource(R.string.goal_target_invalid)
        alreadyText.isNotBlank() && (already == null || already < 0) -> stringResource(R.string.goal_target_invalid)
        !ownJar && accountId == null -> stringResource(R.string.goal_account)
        else -> null
    }

    FormScreen(
        title = existing?.name ?: stringResource(R.string.goal_new),
        onClose = onDismiss,
        primaryLabel = stringResource(R.string.goal_save),
        blocker = problem ?: blocker,
        onPrimary = {
            val start = already?.takeIf { it > 0 && existing == null }?.let {
                GoalEntry(UUID.randomUUID().toString(), LocalDate.now(), it, null, view.config.meMemberId)
            }
            val goal = Goal(
                id = existing?.id ?: UUID.randomUUID().toString(), name = name.trim().take(40), targetMinor = target!!,
                accountId = if (ownJar) null else accountId, targetDate = date, archived = archived,
                contributions = (existing?.contributions ?: emptyList()) + listOfNotNull(start),
            )
            scope.launch {
                // An older database would refuse a goal without an account, or drop its jar.
                if (view.state.connected && goal.ownJar && container.backendBehind()) { problem = dbUpdate; return@launch }
                onSave(goal); onDismiss()
            }
        },
    ) {
        OutlinedTextField(name, { name = it.take(40); problem = null }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.goal_name)) }, singleLine = true)
        OutlinedTextField(targetText, { targetText = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.goal_target)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text(f.currency.code) })
        FormLabel(stringResource(R.string.goal_date))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val d = date
            if (d == null) DetailChip(stringResource(R.string.goal_pick_date), { picking = true }, icon = Icons.Outlined.Event, empty = true)
            else {
                DetailChip(f.day(d), { picking = true }, icon = Icons.Outlined.Event)
                Chip(stringResource(R.string.goal_no_date), false, { date = null })
            }
        }
        FormLabel(stringResource(R.string.goal_where))
        SegmentedChoice(listOf(true to stringResource(R.string.goal_jar), false to stringResource(R.string.goal_jar_account)), ownJar, { ownJar = it; problem = null })
        if (ownJar) {
            Text(stringResource(R.string.goal_jar_help), style = FullaType.secondary, color = c.inkMuted)
            if (existing == null) {
                OutlinedTextField(alreadyText, { alreadyText = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.goal_already)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text(f.currency.code) })
            }
        } else {
            Revealed {
                Text(stringResource(R.string.goal_account_help), style = FullaType.secondary, color = c.inkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in accounts) Chip(a.name, a.id == accountId, { accountId = a.id })
                }
            }
        }
        if (existing != null) {
            SwitchRow(stringResource(R.string.archive), stringResource(R.string.goal_archive_help), archived) { archived = it }
        }
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
