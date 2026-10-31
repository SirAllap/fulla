// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import io.github.sirallap.fulla.ui.theme.FullaType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
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
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.roles.Permissions
import io.github.sirallap.fulla.core.trips.Trip
import io.github.sirallap.fulla.core.trips.TripKind
import io.github.sirallap.fulla.core.trips.TripPhase
import io.github.sirallap.fulla.core.trips.Trips
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.Chip
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.MemberBadge
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.SwitchRow
import io.github.sirallap.fulla.ui.components.tripKindIcon
import androidx.compose.foundation.layout.FlowRow
import io.github.sirallap.fulla.ui.theme.FullaTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Settings › Trips: upcoming, active and finished trips, archived ones collapsed under them. */
@Composable
fun TripsSettings(view: HouseholdView, change: Change) {
    val ledger = LocalContainer.current.ledger
    val f = view.formats
    val today = remember { LocalDate.now() }
    var editing by remember { mutableStateOf<Trip?>(null) }
    var creating by remember { mutableStateOf(false) }
    var archivedOpen by remember { mutableStateOf(false) }
    val canEdit = view.me?.let { Permissions.canEditTrips(it) } == true

    val trips = view.config.trips
    val archived = trips.filter { it.archived }
    val live = trips.filterNot { it.archived }
    val upcoming = live.filter { Trips.phase(it, today) == TripPhase.UPCOMING }
    val active = live.filter { Trips.phase(it, today) == TripPhase.ACTIVE }
    val finished = live.filter { Trips.phase(it, today) == TripPhase.FINISHED }

    val c = FullaTheme.colors
    val range: (Trip) -> String = { t -> if (t.startDate == t.endDate) f.day(t.startDate) else f.day(t.startDate) + " – " + f.day(t.endDate) }

    // A trip under way, as in the mockup: a dark card with its dates, how much of the budget is gone and which day it is.
    @Composable
    fun current(t: Trip) {
        val totals = remember(view, t) { Trips.totals(t, view.active) }
        val day = (java.time.temporal.ChronoUnit.DAYS.between(t.startDate, today) + 1).toInt()
        val days = (java.time.temporal.ChronoUnit.DAYS.between(t.startDate, t.endDate) + 1).toInt()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(c.ink)
                .then(if (canEdit) Modifier.clickable { editing = t } else Modifier).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            io.github.sirallap.fulla.ui.components.Spread(startShare = 0.5f, start = {
                Text(t.name, style = FullaType.title, color = c.paper)
            }, end = { Text(range(t), style = FullaType.secondary, color = c.paper.copy(alpha = 0.75f)) })
            t.budgetMinor?.let { b -> io.github.sirallap.fulla.ui.components.ProgressLine(totals.spentMinor.toFloat() / b, c.highlight, over = totals.overMinor > 0) }
            Text(listOfNotNull(
                t.budgetMinor?.let { b -> if (totals.overMinor > 0) stringResource(R.string.trip_over, f.money(totals.overMinor)) else stringResource(R.string.trip_left, f.money(totals.leftMinor ?: 0), f.money(b)) }
                    ?: stringResource(R.string.trip_spent, f.money(totals.spentMinor)),
                stringResource(R.string.trip_day_of, day, days),
            ).joinToString(" · "), style = FullaType.secondary, color = c.paper.copy(alpha = 0.75f))
        }
    }

    // Any other trip: a card with its name, dates and kind, and its budget, or what it cost once over.
    @Composable
    fun card(t: Trip, finishedTrip: Boolean) {
        val totals = remember(view, t) { Trips.totals(t, view.active) }
        io.github.sirallap.fulla.ui.components.ItemCard(dimmed = finishedTrip || t.archived, onClick = if (canEdit) ({ editing = t }) else null) {
            io.github.sirallap.fulla.ui.components.Spread(startShare = 0.55f, start = {
                Column {
                    Text(t.name, style = FullaType.body, color = c.ink)
                    Text(range(t) + " · " + stringResource(tripKindLabel(t.kind)), style = FullaType.secondary, color = c.inkMuted)
                }
            }, end = {
                Text(when {
                    finishedTrip || t.archived -> stringResource(R.string.trip_spent, f.money(totals.spentMinor))
                    else -> t.budgetMinor?.let { f.money(it) } ?: stringResource(R.string.trip_no_budget)
                }, style = FullaType.secondary, color = if (finishedTrip) c.inkMuted else c.ink)
            })
        }
    }

    if (canEdit) io.github.sirallap.fulla.ui.components.ScreenPrimaryAction(stringResource(R.string.trip_new)) { creating = true }
    Column {
        if (trips.isEmpty()) {
            EmptyState(Icons.Outlined.Luggage, stringResource(R.string.settings_trips), stringResource(R.string.trips_empty_text))
        }
        if (active.isNotEmpty()) {
            Section(stringResource(R.string.trip_active))
            active.forEach { current(it) }
        }
        if (upcoming.isNotEmpty()) {
            Section(stringResource(R.string.trip_upcoming))
            upcoming.sortedBy { it.startDate }.forEach { card(it, false) }
        }
        if (finished.isNotEmpty()) {
            Section(stringResource(R.string.trip_finished))
            finished.sortedByDescending { it.startDate }.forEach { card(it, true) }
        }
        if (archived.isNotEmpty()) {
            ListRow(stringResource(R.string.archived), onClick = { archivedOpen = !archivedOpen },
                end = { Icon(if (archivedOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) })
            if (archivedOpen) archived.forEach { card(it, true) }
        }
    }

    if (canEdit && (creating || editing != null)) {
        TripEditDialog(view, editing, onDismiss = { creating = false; editing = null }) { item ->
            change { api -> ledger.upsert(view.id, Structure.TRIP, item, api) }
        }
    }
}

@Composable
internal fun TripEditDialog(view: HouseholdView, existing: Trip?, onDismiss: () -> Unit, onSave: (kotlinx.serialization.json.JsonObject) -> Unit) {
    val f = view.formats
    val c = FullaTheme.colors
    var start by remember { mutableStateOf(existing?.startDate ?: LocalDate.now()) }
    var end by remember { mutableStateOf(existing?.endDate ?: existing?.startDate ?: LocalDate.now()) }
    var budgetText by remember { mutableStateOf(existing?.budgetMinor?.let { f.plain(it) } ?: "") }
    var inBudgets by remember { mutableStateOf(existing?.inCategoryBudgets ?: false) }
    // A brand new trip starts with just its creator on it, never "everyone":
    // who else is going is an explicit choice, the same way the trip itself
    // being assigned to an expense now is (owner feedback: a household
    // member who isn't actually on a trip must never end up on it by default).
    var members by remember { mutableStateOf(existing?.memberIds?.toSet() ?: setOfNotNull(view.config.meMemberId)) }
    var kind by remember { mutableStateOf(existing?.kind ?: TripKind.HOLIDAY) }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }
    val overlap = view.config.trips.firstOrNull { it.id != existing?.id && !it.archived && start <= it.endDate && end >= it.startDate }

    // Mirrors fulla.save_trip's checks (0012_trips.sql), so a trip made in a
    // phone-only household is caught here instead of at upload time, when
    // `validation_failed` would abort the whole household upload (M3).
    val (from, to) = if (end < start) end to start else start to end
    val spanProblem = (to.toEpochDay() - from.toEpochDay() >= 366).let { if (it) stringResource(R.string.trip_span_too_long) else null }
    val budgetParsed = budgetText.takeIf { it.isNotBlank() }?.let { MoneyParser.parseTyped(it, f.currency, f.decimalStyle) }
    val budgetProblem = when {
        budgetText.isBlank() -> null
        budgetParsed == null -> stringResource(R.string.trip_budget_invalid)
        budgetParsed <= 0 -> stringResource(R.string.trip_budget_invalid)
        else -> null
    }
    val problem = spanProblem ?: budgetProblem

    EditDialog(
        title = existing?.name ?: stringResource(R.string.trip_new),
        initial = existing?.name ?: "",
        label = stringResource(R.string.trip_name),
        onDismiss = onDismiss,
        enabledFor = { name -> name.length <= 40 && problem == null },
        extra = {
            Section(stringResource(R.string.trip_dates), top = 8.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(f.day(start), false, { pickingStart = true })
                Chip(f.day(end), false, { pickingEnd = true })
            }
            OutlinedTextField(
                budgetText, { budgetText = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                label = { Text(stringResource(R.string.trip_budget)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text(f.currency.code) },
            )
            SwitchRow(stringResource(R.string.trip_in_budgets), stringResource(R.string.trip_in_budgets_help), inBudgets) { inBudgets = it }
            Section(stringResource(R.string.trip_kind))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in TripKind.entries) {
                    Chip(stringResource(tripKindLabel(k)), kind == k, { kind = k }, leading = { Icon(tripKindIcon(k), null, Modifier.size(18.dp)) })
                }
            }
            Section(stringResource(R.string.trip_who_is_going))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in view.config.activeMembers) {
                    val on = m.id in members
                    Chip(m.displayName, on, {
                        members = if (on) members - m.id else members + m.id
                    }, leading = { MemberBadge(m.initials, m.colorIndex, size = 20.dp) })
                }
            }
            overlap?.let { Text(stringResource(R.string.trip_overlaps, it.name), color = c.warning) }
            problem?.let { Text(it, color = c.danger) }
        },
    ) { name ->
        val item = buildJsonObject {
            put("id", existing?.id ?: UUID.randomUUID().toString())
            put("name", name)
            put("start_date", from.toString())
            put("end_date", to.toString())
            put("budget_minor", budgetParsed)
            put("in_category_budgets", inBudgets)
            put("archived", existing?.archived ?: false)
            put("member_ids", kotlinx.serialization.json.JsonArray(members.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("trip_kind", kind.wire)
        }
        onSave(item)
    }

    if (pickingStart) {
        val state = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDayMillis())
        DatePickerDialog(onDismissRequest = { pickingStart = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { start = it.toLocalDate() }
                pickingStart = false
            }) { Text(stringResource(R.string.done)) }
        }) { DatePicker(state) }
    }
    if (pickingEnd) {
        val state = rememberDatePickerState(initialSelectedDateMillis = end.atStartOfDayMillis())
        DatePickerDialog(onDismissRequest = { pickingEnd = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { end = it.toLocalDate() }
                pickingEnd = false
            }) { Text(stringResource(R.string.done)) }
        }) { DatePicker(state) }
    }
}

private fun LocalDate.atStartOfDayMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

internal fun tripKindLabel(kind: TripKind): Int = when (kind) {
    TripKind.HOLIDAY -> R.string.trip_kind_holiday
    TripKind.WORK -> R.string.trip_kind_work
    TripKind.EVENT -> R.string.trip_kind_event
    TripKind.FAMILY -> R.string.trip_kind_family
    TripKind.OTHER -> R.string.trip_kind_other
}
