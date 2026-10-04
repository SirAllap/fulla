// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.home

import io.github.sirallap.fulla.ui.components.listEndPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import io.github.sirallap.fulla.core.rules.PeriodAnchors
import io.github.sirallap.fulla.ui.components.Chip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Payments
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
    onFixedCosts: () -> Unit,
    onInsights: () -> Unit,
    onBackup: () -> Unit,
    onTrip: (String) -> Unit = {},
    onAccounts: () -> Unit = {},
) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    val f = view.formats
    val current = remember(view) { f.currentPeriod() }
    var periodText by rememberSaveable { mutableStateOf(current.toString()) }
    val period = YearMonth.parse(periodText)
    var expanded by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val summary = remember(view, period) { view.analytics.summary(view.active, period) }
    val hero = remember(view, period) { view.analytics.hero(view.active, period) }
    val categories = remember(view, period) { view.analytics.byCategory(view.active, period) }
    val forecast = remember(view, period) { if (period == current) runCatching { view.analytics.forecast(view.active, period, LocalDate.now(), view.deletedIds) }.getOrNull() else null }
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
    val notSalary by remember(view.id) { container.settings.notSalary(view.id) }.collectAsStateWithLifecycle(initialValue = emptySet())
    val lastBackup by remember(view.id) { container.settings.lastBackup(view.id) }.collectAsStateWithLifecycle(initialValue = -1L)
    // Only a phone-only household with something to lose, and not more than once a month.
    val backupDue = !view.state.connected && lastBackup != -1L && view.rows.size >= 20 &&
        view.active.any { io.github.sirallap.fulla.core.demo.DemoData.TAG !in it.tags } &&
        (lastBackup ?: 0L) < System.currentTimeMillis() - 30L * 24 * 3600 * 1000

    Column(Modifier.fillMaxSize()) {
        // Analysis is a button of its own, to the left of the sync and the gear, so the gear never moves between tabs.
        TabHeader(view.config.household.name, actions = { InsightsButton(onInsights); headerActions() })
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; container.syncAll(); refreshing = false } },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listEndPadding(aboveTabBar = true)) {
                item {
                    PeriodSelector(f.period(period), { periodText = period.minusMonths(1).toString() },
                        { periodText = period.plusMonths(1).toString() }, canGoNext = period < current)
                    val openFrom = stringResource(R.string.period_open_from)
                    f.periodRange(period) { openFrom.format(it) }?.let {
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
                // Most likely this month's salary, imported or written down without the mark.
                val candidate = if (period == current) PeriodAnchors.unmarkedSalary(view.active, notSalary) else null
                if (candidate != null) item {
                    ListRow(stringResource(R.string.salary_question),
                        context = listOf(candidate.note.ifBlank { view.categoryName(candidate.categoryId) ?: "" }, f.money(candidate.amountMinor), f.day(candidate.date))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        icon = Icons.Outlined.Payments, iconTint = c.moneyIn,
                        below = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                Chip(stringResource(R.string.salary_question_yes), selected = true, onClick = {
                                    scope.launch { container.ledger.save(view.id, PeriodAnchors.mark(candidate, true)) }
                                })
                                Chip(stringResource(R.string.salary_question_no), selected = false, onClick = {
                                    scope.launch { container.settings.addNotSalary(view.id, candidate.id) }
                                })
                            }
                        })
                }
                val waiting = if (period == current && candidate == null) f.periodRule.daysWaitingForSalary(today) else null
                if (waiting != null) item {
                    ListRow(stringResource(R.string.salary_overdue), context = stringResource(R.string.salary_overdue_text, waiting),
                        icon = Icons.Outlined.Payments, iconTint = c.warning)
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
                    if (budgets.isNotEmpty()) {
                        val spent = budgetSpend.filterKeys { it in budgets }.values.sum()
                        val total = budgets.values.sum()
                        ListRow(stringResource(R.string.budget_of_period), onClick = onBudgets,
                            context = stringResource(R.string.budget_left_of, f.money(total - spent), f.money(total)),
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
                if (forecast != null) item(key = "fixed") { FixedCostsSection(view, forecast, onFixedCosts) }
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
                        if (expanded == row.categoryId) CategoryBreakdown(view, period, row.categoryId, onOpen)
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
        Figure(stringResource(R.string.saved), rate?.let { "${Math.round(it * 100)} %" } ?: "—", c.ink, Modifier.weight(1f))
    }
}

@Composable
private fun Figure(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = FullaType.label, color = FullaTheme.colors.inkMuted)
        Text(value, style = FullaType.amount, color = color, maxLines = 1)
    }
}

/**
 * What a category's spending was made of, one level at a time: its
 * subcategories (and its own rows, as "General"), then, where a field is
 * limited to that category, each of its values (Pets › Vet › Rex), then the
 * rows themselves. A level with nothing to split shows the rows directly.
 */
@Composable
private fun CategoryBreakdown(view: HouseholdView, period: YearMonth, categoryId: String, onOpen: (String) -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val subs = remember(view, period, categoryId) { view.analytics.bySubcategory(view.active, period, categoryId) }
    var open by remember(categoryId) { mutableStateOf<String?>(null) }
    if (subs.isEmpty()) {
        FieldBreakdown(view, period, categoryId, 38.dp, onOpen)
        return
    }
    for (s in subs) {
        val id = s.key ?: continue
        val name = if (id == categoryId) stringResource(R.string.subcategory_general) else view.categoryName(id) ?: ""
        ListRow(name, indent = 38.dp, onClick = { open = if (open == id) null else id },
            end = { AmountText(f.money(s.amountMinor), color = c.inkMuted) })
        if (open == id) FieldBreakdown(view, period, id, 56.dp, onOpen)
    }
}

/** Exactly [categoryId]'s rows, split by the first list field limited to it when rows say a value. */
@Composable
private fun FieldBreakdown(view: HouseholdView, period: YearMonth, categoryId: String, indent: androidx.compose.ui.unit.Dp, onOpen: (String) -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val field = io.github.sirallap.fulla.core.schema.SchemaEngine.fieldsForCategory(view.config.fields, TransactionKind.EXPENSE, view.config.category(categoryId))
        .firstOrNull { it.type == io.github.sirallap.fulla.core.schema.FieldType.SELECT }
    val values = remember(view, period, categoryId, field) { field?.let { view.analytics.byFieldValue(view.active, period, categoryId, it.key) }.orEmpty() }
    if (field == null || values.none { it.key != null }) {
        Rows(view, view.analytics.spending(view.active, period, categoryId, withSubcategories = false), indent, onOpen)
        return
    }
    var open by remember(categoryId) { mutableStateOf<String?>(null) }
    val none = "\u0000"
    for (v in values) {
        val id = v.key ?: none
        ListRow(v.key ?: stringResource(R.string.field_value_none), indent = indent, onClick = { open = if (open == id) null else id },
            end = { AmountText(f.money(v.amountMinor), color = c.inkMuted) })
        if (open == id) Rows(view, view.analytics.spending(view.active, period, categoryId, withSubcategories = false, field = field.key to v.key),
            indent + 18.dp, onOpen)
    }
}

@Composable
private fun Rows(view: HouseholdView, rows: List<io.github.sirallap.fulla.core.model.Transaction>, indent: androidx.compose.ui.unit.Dp, onOpen: (String) -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    for (t in rows) {
        ListRow(t.note.ifBlank { view.categoryName(t.categoryId) ?: "" }, indent = indent, context = f.day(t.date),
            onClick = { onOpen(t.id) },
            end = { AmountText(f.money(if (t.kind == TransactionKind.REFUND) -t.amountMinor else t.amountMinor), color = c.inkMuted) })
    }
}

/** The way into the analysis, big enough to be seen: icon and name on a tinted pill. */
@Composable
private fun InsightsButton(onClick: () -> Unit) {
    val c = FullaTheme.colors
    val label = stringResource(R.string.insights)
    // On a narrow phone the name would not fit beside the title: the icon alone, in the same pill.
    val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360
    androidx.compose.foundation.layout.Row(
        Modifier.padding(end = 4.dp).height(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(c.highlight).clickable(onClickLabel = label, onClick = onClick).padding(horizontal = if (narrow) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Insights, if (narrow) label else null, tint = c.onHighlight, modifier = Modifier.size(22.dp))
        if (!narrow) Text(label, style = FullaType.body, color = c.onHighlight, maxLines = 1)
    }
}
