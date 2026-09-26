// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.trips

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * A trip: the money side of a place or event. The jar (when there is a
 * budget) is the only loud element, exactly as on the Overview.
 */
@Composable
fun TripScreen(view: HouseholdView, tripId: String, onBack: () -> Unit, onOpenTransaction: (String) -> Unit) {
    val container = LocalContainer.current
    val ledger = container.ledger
    val f = view.formats
    val trip = view.config.trip(tripId)
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
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
            ),
        )
        error?.let { Text(it, style = FullaType.secondary, color = FullaTheme.colors.danger, modifier = Modifier.padding(horizontal = 20.dp)) }

        val today = remember { LocalDate.now() }
        val budget = trip.budgetMinor
        val rows = remember(view, trip) { view.active.filter { it.tripId == trip.id } }
        val totals = remember(trip, rows) { Trips.totals(trip, rows) }
        val perDay = remember(trip, totals, today) { Trips.perDay(trip, totals, today) }
        val left = totals.leftMinor ?: 0
        val days = maxOf(1, java.time.temporal.ChronoUnit.DAYS.between(trip.startDate, trip.endDate).toInt() + 1)

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (budget != null) {
                item {
                    HeroJar(
                        hero = Hero(budget, totals.spentMinor),
                        figure = f.money(left),
                        countUp = { fraction -> f.money((left * fraction).toLong()) },
                        description = stringResource(R.string.trip_left, f.money(left), f.money(budget)),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        height = 220.dp,
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Figure(stringResource(R.string.money_out), f.money(totals.spentMinor), FullaTheme.colors.moneyOut, Modifier)
                    if (budget != null) {
                        Figure(
                            if (totals.overMinor > 0) stringResource(R.string.trip_over, f.money(totals.overMinor))
                            else stringResource(R.string.budget_left, f.money(left)),
                            "", FullaTheme.colors.ink, Modifier,
                        )
                    }
                    perDay?.let { p ->
                        Figure(stringResource(R.string.trip_per_day, f.money(p.amountMinor), days), "", FullaTheme.colors.ink, Modifier)
                    }
                }
            }
            if (!SharedPot.isShared(view.config.household) && view.config.activeMembers.size >= 2) {
                item { io.github.sirallap.fulla.ui.components.Section(stringResource(R.string.settings_members)) }
                val byMember = remember(rows) {
                    val paid = LinkedHashMap<String, Long>()
                    for (t in rows) {
                        val sign = if (t.kind == TransactionKind.REFUND) -1 else 1
                        t.paidByMemberId?.let { paid[it] = (paid[it] ?: 0) + sign * t.amountMinor }
                    }
                    paid
                }
                items(byMember.entries.toList(), key = { it.key }) { (memberId, amount) ->
                    ListRow(view.config.member(memberId)?.displayName ?: "", end = { AmountText(f.money(amount)) })
                }
            }
            if (rows.isEmpty()) {
                item { EmptyState(Icons.Outlined.Edit, trip.name, stringResource(R.string.empty_period_text)) }
            } else {
                item { io.github.sirallap.fulla.ui.components.Section(stringResource(R.string.where_it_went)) }
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
