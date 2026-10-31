// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.trips

import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import io.github.sirallap.fulla.ui.components.listEndPadding
import io.github.sirallap.fulla.ui.components.FullaDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.core.analytics.Hero
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.trips.Trips
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.BackHeader
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.HeroJar
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.MenuItem
import io.github.sirallap.fulla.ui.components.rememberChange
import io.github.sirallap.fulla.ui.settings.TripEditDialog
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * A trip: the money side of a place or event. The jar (when there is a
 * budget) is the only loud element, exactly as on the Overview.
 */
@Composable
fun TripScreen(view: HouseholdView, tripId: String, onBack: () -> Unit, onOpenTransaction: (String) -> Unit, onAdd: () -> Unit = {}) {
    val container = LocalContainer.current
    val ledger = container.ledger
    val f = view.formats
    val trip = view.config.trip(tripId)
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val change = rememberChange(view) { error = it }

    Column(Modifier.fillMaxSize()) {
        if (trip == null) {
            BackHeader(stringResource(R.string.settings_trips), onBack)
            return@Column
        }
        BackHeader(
            trip.name, onBack,
            menu = listOf(
                MenuItem(stringResource(R.string.trip_name), Icons.Outlined.Edit, onClick = { editing = true }),
                MenuItem(
                    stringResource(if (trip.archived) R.string.trip_unarchive else R.string.trip_archive),
                    if (trip.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    onClick = {
                        val item = buildJsonObject {
                            put("id", trip.id); put("name", trip.name)
                            put("start_date", trip.startDate.toString()); put("end_date", trip.endDate.toString())
                            put("budget_minor", trip.budgetMinor); put("in_category_budgets", trip.inCategoryBudgets)
                            put("archived", !trip.archived)
                        }
                        change { api -> ledger.upsert(view.id, Structure.TRIP, item, api) }
                    },
                ),
                MenuItem(stringResource(R.string.trip_delete), Icons.Outlined.Delete, danger = true, onClick = { confirmingDelete = true }),
            ),
        )
        if (confirmingDelete) {
            FullaDialog(
                onDismissRequest = { confirmingDelete = false },
                text = { Text(stringResource(R.string.trip_delete_confirm, trip.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingDelete = false
                        // Deliberately not `change {}` (Pieces.kt's
                        // rememberChange): that launches on the screen's own
                        // rememberCoroutineScope, and onBack() below pops
                        // this screen right away, which would cancel a
                        // connected delete mid-RPC (review: HIGH 2). The
                        // container's scope outlives the screen.
                        val connected = view.state.connected
                        container.scope.launch {
                            val api = if (connected) container.api() else null
                            runCatching { ledger.deleteTrip(view.id, trip.id, api) }
                        }
                        onBack()
                    }) { Text(stringResource(R.string.trip_delete), color = FullaTheme.colors.danger) }
                },
                dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        error?.let { Text(it, style = FullaType.secondary, color = FullaTheme.colors.danger, modifier = Modifier.padding(horizontal = 20.dp)) }

        val today = io.github.sirallap.fulla.ui.components.rememberToday()
        val budget = trip.budgetMinor
        val rows = remember(view, trip) { view.active.filter { it.tripId == trip.id } }
        val totals = remember(trip, rows) { Trips.totals(trip, rows) }
        val perDay = remember(trip, totals, today) { Trips.perDay(trip, totals, today) }
        val left = totals.leftMinor ?: 0
        val byMember = remember(rows) { Trips.paidBy(rows) }

        val c = FullaTheme.colors
        val phase = Trips.phase(trip, today)
        // As in the mockup: dates, kind and day under the name; a card with what is left; who goes; on what; then every expense.
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = listEndPadding()) {
            item {
                val day = (java.time.temporal.ChronoUnit.DAYS.between(trip.startDate, today) + 1).toInt()
                val days = (java.time.temporal.ChronoUnit.DAYS.between(trip.startDate, trip.endDate) + 1).toInt()
                Text(listOfNotNull(
                    if (trip.startDate == trip.endDate) f.day(trip.startDate) else f.day(trip.startDate) + " – " + f.day(trip.endDate),
                    stringResource(io.github.sirallap.fulla.ui.settings.tripKindLabel(trip.kind)),
                    stringResource(R.string.trip_day_of, day, days).takeIf { phase == io.github.sirallap.fulla.core.trips.TripPhase.ACTIVE },
                ).joinToString(" · "), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(c.paperHigh)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (budget != null) {
                        Text(if (totals.overMinor > 0) stringResource(R.string.trip_over, f.money(totals.overMinor)) else stringResource(R.string.trip_left_label),
                            style = FullaType.secondary, color = if (totals.overMinor > 0) c.danger else c.inkMuted)
                        Text(f.money(left), style = FullaType.heroFigure, color = c.ink, maxLines = 1, softWrap = false)
                        io.github.sirallap.fulla.ui.components.ProgressLine(totals.spentMinor.toFloat() / budget, c.highlight, over = totals.overMinor > 0)
                    } else {
                        Text(stringResource(R.string.money_out), style = FullaType.secondary, color = c.inkMuted)
                        Text(f.money(totals.spentMinor), style = FullaType.heroFigure, color = c.ink, maxLines = 1, softWrap = false)
                    }
                    Text(listOfNotNull(
                        budget?.let { stringResource(R.string.trip_spent_of, f.money(totals.spentMinor), f.money(it)) },
                        perDay?.let { p -> pluralStringResource(R.plurals.trip_per_day, p.days, f.money(p.amountMinor), p.days) },
                    ).joinToString(" · "), style = FullaType.secondary, color = c.inkMuted)
                }
            }
            val going = trip.memberIds.mapNotNull { view.config.member(it) }
            if (going.isNotEmpty() && view.config.activeMembers.size >= 2) {
                item { io.github.sirallap.fulla.ui.components.Section(stringResource(R.string.trip_going)) }
                item {
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in going) {
                            val paid = byMember[m.id]
                            Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(c.paperHigh)
                                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                io.github.sirallap.fulla.ui.components.MemberBadge(m.initials, m.colorIndex, size = 28.dp)
                                Text(m.displayName + (paid?.takeIf { !SharedPot.isShared(view.config.household) }?.let { " · " + f.money(it) } ?: ""),
                                    style = FullaType.secondary, color = c.ink)
                            }
                        }
                    }
                }
            }
            // What the money went on, by category, each bar against the largest.
            val byCategory = rows.groupBy { t -> view.config.category(t.categoryId)?.let { it.parentId ?: it.id } }
                .mapValues { (_, ts) -> ts.sumOf { if (it.kind == TransactionKind.REFUND) -it.amountMinor else it.amountMinor } }
                .filter { it.value > 0 }.toList().sortedByDescending { it.second }
            if (byCategory.isNotEmpty()) {
                item { io.github.sirallap.fulla.ui.components.Section(stringResource(R.string.trip_on_what)) }
                val top = byCategory.first().second
                items(byCategory, key = { "cat-" + it.first }) { (catId, amount) ->
                    val cat = view.config.category(catId)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        io.github.sirallap.fulla.ui.components.Spread(start = {
                            Text(cat?.name ?: stringResource(R.string.uncategorized), style = FullaType.body, color = c.ink)
                        }, end = { AmountText(f.money(amount)) })
                        io.github.sirallap.fulla.ui.components.ProgressLine(amount.toFloat() / top, cat?.let { c.category(it.colorIndex) } ?: c.inkMuted)
                    }
                }
            }
            if (phase == io.github.sirallap.fulla.core.trips.TripPhase.ACTIVE) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        io.github.sirallap.fulla.ui.components.PrimaryButton(stringResource(R.string.trip_add_expense), onAdd, icon = Icons.Outlined.Add)
                    }
                }
            }
            if (rows.isEmpty()) {
                item { EmptyState(Icons.Outlined.Edit, trip.name, stringResource(R.string.empty_period_text)) }
            } else {
                item { io.github.sirallap.fulla.ui.components.Section(stringResource(R.string.trip_rows)) }
                items(rows.sortedByDescending { it.date }, key = { it.id }) { t ->
                    ListRow(
                        t.note.ifBlank { view.config.category(t.categoryId)?.name ?: "" },
                        context = f.day(t.date),
                        onClick = { onOpenTransaction(t.id) },
                        end = { AmountText(f.money(if (t.kind == TransactionKind.REFUND) -t.amountMinor else t.amountMinor)) },
                    )
                }
            }
        }
    }
    if (editing) {
        TripEditDialog(view, trip, onDismiss = { editing = false }) { item ->
            change { api -> ledger.upsert(view.id, Structure.TRIP, item, api) }
        }
    }
}

@Composable
private fun Figure(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier.padding(end = 20.dp)) {
        Text(label, style = FullaType.label, color = FullaTheme.colors.inkMuted)
        if (value.isNotEmpty()) Text(value, style = FullaType.amount, color = color, maxLines = 1)
    }
}
