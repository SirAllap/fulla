// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.analytics.Budgets
import io.github.sirallap.fulla.core.guide.TourStop
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.trips.Trips
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.guide.guideTarget
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.HeroJar
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.MenuItem
import io.github.sirallap.fulla.ui.components.PeriodSelector
import io.github.sirallap.fulla.ui.components.ProgressLine
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.TabHeader
import io.github.sirallap.fulla.ui.components.tripKindIcon
import io.github.sirallap.fulla.ui.entry.CategoryIcons
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * The period at a glance: the jar, then what it is made of. Only the jar is
 * loud; everything under it is plain rows.
 */
@Composable
fun HomeScreen(
    view: HouseholdView,
    headerActions: @Composable () -> Unit,
    onOpen: (String) -> Unit,
    onBudgets: () -> Unit,
    onInsights: () -> Unit,
    onBackup: () -> Unit,
    onTrip: (String) -> Unit = {},
    onAccounts: () -> Unit = {},
) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    val f = view.formats
    val current = remember(view.config.household) { f.currentPeriod() }
    var periodText by rememberSaveable { mutableStateOf(current.toString()) }
    val period = YearMonth.parse(periodText)
    var expanded by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val summary = remember(view, period) { view.analytics.summary(view.active, period) }
    val hero = remember(view, period) { view.analytics.hero(view.active, period) }
    val categories = remember(view, period) { view.analytics.byCategory(view.active, period) }
    val projection = remember(view, period) { if (period == current) view.analytics.projection(view.active, period, LocalDate.now()) else null }
    val budgets = remember(view, period) { Budgets.forPeriod(view.config, period) }
    val budgetSpend = remember(view, period) {
        view.analytics.budgetSpend(view.active, period, view.config.trips).associate { it.categoryId to it.amountMinor }
    }
    val today = remember { LocalDate.now() }
    val homeTrip = remember(view, today) {
        // The active trip always wins; only when none is active do we look ahead
        // for the soonest one starting within a week (bundle order is start_date
        // desc, so a plain firstOrNull would show an upcoming trip over an
        // active one that started earlier).
        Trips.activeOn(view.config.trips, today)
            ?: view.config.trips.filter { !it.archived && it.startDate in today..today.plusDays(7) }.minByOrNull { it.startDate }
    }
    val homeTripTotals = remember(view, homeTrip) { homeTrip?.let { Trips.totals(it, view.active) } }
    val lastBackup by remember(view.id) { container.settings.lastBackup(view.id) }.collectAsStateWithLifecycle(initialValue = -1L)
    // Only a phone-only household with something to lose, and not more than once a month.
    val backupDue = !view.state.connected && lastBackup != -1L && view.rows.size >= 20 &&
        view.active.any { io.github.sirallap.fulla.core.demo.DemoData.TAG !in it.tags } &&
        (lastBackup ?: 0L) < System.currentTimeMillis() - 30L * 24 * 3600 * 1000

    Column(Modifier.fillMaxSize()) {
        TabHeader(view.config.household.name, actions = { headerActions() },
            menu = listOf(MenuItem(stringResource(R.string.insights), Icons.Outlined.Insights, onClick = onInsights)))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; container.syncAll(); refreshing = false } },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    PeriodSelector(f.period(period), { periodText = period.minusMonths(1).toString() },
                        { periodText = period.plusMonths(1).toString() }, canGoNext = period < current)
                    f.periodRange(period)?.let {
                        Text(it, style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                item {
                    val description = stringResource(R.string.hero_description, f.period(period), f.money(summary.incomeMinor),
                        f.money(summary.expenseMinor), f.money(summary.savingsMinor))
                    HeroJar(
                        hero = hero,
                        figure = f.money(hero.savingsMinor),
                        countUp = { fraction -> f.money((hero.savingsMinor * fraction).toLong()) },
                        description = description,
                        modifier = Modifier.guideTarget(TourStop.JAR).padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
                if (backupDue) item {
                    ListRow(stringResource(R.string.backup_due), context = stringResource(R.string.backup_due_text),
                        icon = Icons.Outlined.SaveAlt, iconTint = c.warning, onClick = onBackup)
                }
                // What the accounts hold today, once someone has told Fulla their starting balances:
                // the month's figures above never include money that was already there.
                val accounts = view.config.accounts.filter { !it.archived }
                if (accounts.any { it.openingBalanceMinor != 0L }) item {
                    val total = view.analytics.accountBalances(view.active, accounts, java.time.LocalDate.now()).values.sum()
                    ListRow(stringResource(R.string.accounts_total), detail = f.money(total),
                        context = stringResource(R.string.accounts_total_help),
                        icon = Icons.Outlined.AccountBalance, onClick = onAccounts)
                }
                item {
                    Figures(view, summary.incomeMinor, summary.expenseMinor, summary.savingsRate)
                    projection?.takeIf { it.daysElapsed < it.daysInPeriod }?.let { p ->
                        ListRow(stringResource(R.string.heading_for), context = stringResource(R.string.day_of, p.daysElapsed, p.daysInPeriod),
                            end = { AmountText(f.money(p.projectedMinor), color = c.inkMuted) })
                    }
                    if (budgets.isNotEmpty()) {
                        val spent = budgetSpend.filterKeys { it in budgets }.values.sum()
                        val total = budgets.values.sum()
                        ListRow(stringResource(R.string.budget_of_period), onClick = onBudgets,
                            context = stringResource(R.string.budget_left, f.money(total - spent)),
                            below = { ProgressLine(if (total > 0) spent.toFloat() / total else 0f, c.moneyOut, over = spent > total) },
                            end = { Icon(Icons.Outlined.ChevronRight, null, tint = c.inkMuted) })
                    }
                    if (homeTrip != null && homeTripTotals != null) {
                        val left = homeTripTotals.leftMinor
                        val budget = homeTrip.budgetMinor
                        ListRow(
                            title = homeTrip.name,
                            icon = tripKindIcon(homeTrip.kind),
                            context = if (budget != null && left != null) {
                                stringResource(R.string.trip_left, f.money(left), f.money(budget))
                            } else f.money(homeTripTotals.spentMinor),
                            below = if (budget != null) ({
                                ProgressLine(homeTripTotals.spentMinor.toFloat() / budget, c.moneyOut, over = homeTripTotals.overMinor > 0)
                            }) else null,
                            onClick = { onTrip(homeTrip.id) },
                            end = { Icon(Icons.Outlined.ChevronRight, null, tint = c.inkMuted) },
                        )
                    }
                }
                if (categories.isEmpty()) {
                    item {
                        EmptyState(Icons.Outlined.Opacity, stringResource(R.string.empty_period_title), stringResource(R.string.empty_period_text))
                    }
                } else {
                    item { Section(stringResource(R.string.where_it_went)) }
                    items(categories, key = { it.categoryId }) { row ->
                        val cat = view.config.category(row.categoryId)
                        val budget = budgets[row.categoryId]
                        val forBudget = budgetSpend[row.categoryId] ?: 0L
                        val onTrips = row.amountMinor - forBudget
                        ListRow(
                            title = cat?.name ?: stringResource(R.string.uncategorized),
                            icon = CategoryIcons.of(cat?.icon ?: "label"),
                            iconTint = c.category(cat?.colorIndex ?: 0),
                            context = when {
                                budget != null && onTrips > 0 ->
                                    stringResource(R.string.of_budget, f.money(budget)) + " · " + stringResource(R.string.on_trips, f.money(onTrips))
                                budget != null -> stringResource(R.string.of_budget, f.money(budget))
                                row.previousAverageMinor > 0 -> stringResource(R.string.usually, f.money(row.previousAverageMinor))
                                else -> null
                            },
                            below = if (budget != null) ({ ProgressLine(forBudget.toFloat() / budget, c.moneyOut, over = forBudget > budget) }) else null,
                            onClick = { expanded = if (expanded == row.categoryId) null else row.categoryId },
                            end = { AmountText(f.money(row.amountMinor)) },
                        )
                        if (expanded == row.categoryId) {
                            val rows = view.active.filter {
                                it.kind != TransactionKind.INCOME && it.kind.countsInTotals &&
                                    (it.categoryId == row.categoryId || view.config.category(it.categoryId)?.parentId == row.categoryId) &&
                                    f.periodRule.periodOf(it.date, it.kind, it.recurrence) == period
                            }.sortedByDescending { it.date }
                            for (t in rows) {
                                ListRow(t.note.ifBlank { cat?.name ?: "" }, indent = 38.dp, context = f.day(t.date),
                                    onClick = { onOpen(t.id) },
                                    end = { AmountText(f.money(if (t.kind == TransactionKind.REFUND) -t.amountMinor else t.amountMinor), color = c.inkMuted) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Figures(view: HouseholdView, income: Long, expense: Long, rate: Double?) {
    val c = FullaTheme.colors
    val f = view.formats
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Figure(stringResource(R.string.money_in), f.money(income, signed = true), c.moneyIn, Modifier.weight(1f))
        Figure(stringResource(R.string.money_out), f.money(expense), c.moneyOut, Modifier.weight(1f))
        Figure(stringResource(R.string.saved), rate?.let { "${(it * 100).toInt()} %" } ?: "—", c.ink, Modifier.weight(1f))
    }
}

@Composable
private fun Figure(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = FullaType.label, color = FullaTheme.colors.inkMuted)
        Text(value, style = FullaType.amount, color = color, maxLines = 1)
    }
}
