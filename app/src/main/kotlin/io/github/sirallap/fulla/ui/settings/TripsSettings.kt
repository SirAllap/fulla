// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import io.github.sirallap.fulla.core.trips.TripPhase
import io.github.sirallap.fulla.core.trips.Trips
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.Chip
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.SwitchRow
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

    @Composable
    fun row(t: Trip) {
        ListRow(
            t.name, icon = Icons.Outlined.Luggage,
            context = t.budgetMinor?.let { stringResource(R.string.of_budget, f.money(it)) },
            onClick = { editing = t },
        )
    }

    Column {
        if (trips.isEmpty()) {
            EmptyState(Icons.Outlined.Luggage, stringResource(R.string.settings_trips), stringResource(R.string.trip_name))
        }
        if (upcoming.isNotEmpty()) {
            Section(stringResource(R.string.trip_upcoming))
            upcoming.forEach { row(it) }
        }
        if (active.isNotEmpty()) {
            Section(stringResource(R.string.trip_active))
            active.forEach { row(it) }
        }
        if (finished.isNotEmpty()) {
            Section(stringResource(R.string.trip_finished))
            finished.forEach { row(it) }
        }
        if (archived.isNotEmpty()) {
            ListRow(stringResource(R.string.archived), onClick = { archivedOpen = !archivedOpen },
                end = { Icon(if (archivedOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) })
            if (archivedOpen) archived.forEach { row(it) }
        }
        if (canEdit) {
            Box(Modifier.padding(20.dp)) {
                PrimaryButton(stringResource(R.string.trip_new), onClick = { creating = true }, icon = Icons.Outlined.Add)
            }
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
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }
    val overlap = view.config.trips.firstOrNull { it.id != existing?.id && !it.archived && start <= it.endDate && end >= it.startDate }

    EditDialog(
        title = existing?.name ?: stringResource(R.string.trip_new),
        initial = existing?.name ?: "",
        label = stringResource(R.string.trip_name),
        onDismiss = onDismiss,
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
            overlap?.let { Text(stringResource(R.string.trip_overlaps, it.name), color = c.warning) }
        },
    ) { name ->
        val budget = budgetText.takeIf { it.isNotBlank() }?.let { MoneyParser.parse(it, f.currency, f.decimalStyle) }
        val (from, to) = if (end < start) end to start else start to end
        val item = buildJsonObject {
            put("id", existing?.id ?: UUID.randomUUID().toString())
            put("name", name)
            put("start_date", from.toString())
            put("end_date", to.toString())
            put("budget_minor", budget)
            put("in_category_budgets", inBudgets)
            put("archived", existing?.archived ?: false)
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
