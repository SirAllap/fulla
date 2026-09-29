// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.insights

import androidx.compose.ui.graphics.Color
import io.github.sirallap.fulla.ui.components.TileRow
import io.github.sirallap.fulla.ui.components.LiquidTone
import io.github.sirallap.fulla.ui.components.LiquidTile
import io.github.sirallap.fulla.ui.components.LiquidBarRow
import io.github.sirallap.fulla.ui.components.Vial
import io.github.sirallap.fulla.ui.components.SpendingVials
import io.github.sirallap.fulla.ui.components.listEndPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.schema.SchemaEngine
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.BackHeader
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.MemberBadge
import io.github.sirallap.fulla.ui.components.ProgressLine
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.entry.fieldText
import io.github.sirallap.fulla.ui.theme.FullaTheme
import java.time.LocalDate
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The longer view of one period, any period: where the money went (vials of
 * the jar's liquid), how the period went in figures, which weekday the
 * everyday money goes, the largest expenses, what is bought again and again,
 * what moved against the usual, who paid, and how each period compares.
 */
@Composable
fun InsightsScreen(view: HouseholdView, onBack: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val a = view.analytics
    val today = LocalDate.now()
    val current = remember(view) { f.currentPeriod(today) }
    var periodText by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(current.toString()) }
    val period = java.time.YearMonth.parse(periodText)
    val report = remember(view, period) { a.report(view.active, period, today, view.config.trips) }
    val forecast = remember(view, period) { if (period == current) a.forecast(view.active, period, today, view.deletedIds) else null }
    val series = remember(view, period) { a.series(view.active, period, 12).reversed().filter { it.incomeMinor != 0L || it.expenseMinor != 0L } }
    val unit = remember(view) { (0 until f.currency.minorUnits).fold(1L) { acc, _ -> acc * 10 } }
    val trends = remember(view, period) { a.trends(view.active, period, minimumMinor = 10 * unit) }
    val repeating = remember(view) { a.detectedRecurring(view.active, today) }
    val byMember = remember(view, period) { a.byMember(view.active, period).filter { it.paidMinor != 0L || it.shareMinor != 0L } }
    val noSpend = remember(view, period) { a.noSpendDays(view.active, period, today) }
    val top = remember(view, period) { a.topCategories(view.active, period) }
    val others = stringResource(R.string.other_categories)
    val uncategorized = stringResource(R.string.uncategorized)
    val dimensions = remember(view) { SchemaEngine.dimensions(view.config.fields) }
    val language = Locale.getDefault().language
    val container = io.github.sirallap.fulla.ui.LocalContainer.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val canEdit = view.me?.let { io.github.sirallap.fulla.core.roles.Permissions.canEditStructure(it) } == true
    val made = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<String>() }

    /** A charge found repeating becomes a recurring item, from its latest occurrence. */
    fun makeRecurring(r: io.github.sirallap.fulla.core.analytics.DetectedRecurring) {
        val latest = view.active.filter { it.note == r.note }.maxByOrNull { it.date } ?: return
        val rule = io.github.sirallap.fulla.core.recurring.RecurringRule(
            id = java.util.UUID.randomUUID().toString(), name = r.note,
            template = latest.copy(amountMinor = r.typicalMinor, recurrence = io.github.sirallap.fulla.core.model.Recurrence.FIXED,
                importFingerprint = null, recurringRuleId = null, occurrenceDate = null),
            schedule = io.github.sirallap.fulla.core.recurring.Schedule(io.github.sirallap.fulla.core.recurring.Frequency.MONTHLY, byMonthDay = r.lastDate.dayOfMonth),
            // Starts after the last one seen, so nothing already written down is written again.
            startDate = r.lastDate.plusDays(1), autoCreate = true,
        )
        scope.launch {
            val api = if (view.state.connected) container.api() else null
            runCatching {
                container.ledger.upsert(view.id, io.github.sirallap.fulla.client.remote.Structure.RECURRING,
                    io.github.sirallap.fulla.client.wire.Wire.recurring(rule), api)
            }.onSuccess { made += r.note }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackHeader(stringResource(R.string.insights), onBack)
        io.github.sirallap.fulla.ui.components.PeriodSelector(f.period(period), { periodText = period.minusMonths(1).toString() },
            { periodText = period.plusMonths(1).toString() }, canGoNext = period < current)
        LazyColumn(Modifier.weight(1f), contentPadding = listEndPadding()) {
            item(key = "figures") {
                Section(stringResource(R.string.period_summary), top = 8.dp())
                FigureTiles(view, period, report, forecast, noSpend)
            }
            if (top.isNotEmpty()) item(key = "vials") {
                val total = top.sumOf { it.amountMinor }.toFloat()
                val max = top.maxOf { it.amountMinor }.toFloat()
                Section(stringResource(R.string.where_it_went))
                SpendingVials(top.map { s ->
                    val cat = s.key?.let { view.config.category(it) }
                    val name = if (s.key == null) others else cat?.name ?: uncategorized
                    Vial(name, if (s.key == null) null else io.github.sirallap.fulla.ui.entry.CategoryIcons.of(cat?.icon ?: "label"),
                        level = s.amountMinor / max, share = s.amountMinor / total, description = "$name, ${f.money(s.amountMinor)}")
                })
            }
            if (report.weekdays.any { it > 0 }) item(key = "weekdays") {
                val total = report.weekdays.filter { it > 0 }.sum().toFloat()
                val max = report.weekdays.max().toFloat()
                Section(stringResource(R.string.by_weekday))
                SpendingVials(report.weekdays.mapIndexed { i, amount ->
                    val day = java.time.DayOfWeek.of(i + 1).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                        .replaceFirstChar { it.titlecase(Locale.getDefault()) }.trimEnd('.')
                    val positive = amount.coerceAtLeast(0)
                    Vial(day, null, level = positive / max, share = positive / total, description = "$day, ${f.money(amount)}")
                })
            }
            if (report.biggest.isNotEmpty()) {
                item { Section(stringResource(R.string.biggest_expenses)) }
                val largest = report.biggest.first().amountMinor.toFloat()
                items(report.biggest, key = { "b-" + it.id }) { t ->
                    val category = view.categoryName(t.categoryId)
                    LiquidBarRow(t.note.ifBlank { category ?: uncategorized }, f.money(t.amountMinor), t.amountMinor / largest,
                        context = listOfNotNull(category.takeIf { t.note.isNotBlank() }, f.day(t.date)).joinToString(" · "),
                        phase = t.amountMinor % 7 * 0.9f)
                }
            }
            if (report.places.isNotEmpty()) {
                item { Section(stringResource(R.string.repeated_most)) }
                val largest = report.places.first().totalMinor.toFloat()
                items(report.places, key = { "p-" + it.name }) { p ->
                    LiquidBarRow(p.name, f.money(p.totalMinor), p.totalMinor / largest, context = stringResource(R.string.times, p.count),
                        phase = p.count * 1.3f)
                }
            }
            if (trends.isNotEmpty()) {
                item { Section(stringResource(R.string.against_usual)) }
                val largest = trends.maxOf { maxOf(it.currentMinor, it.averageMinor) }.toFloat()
                items(trends, key = { "t-" + it.categoryId }) { t ->
                    val up = t.currentMinor > t.averageMinor
                    LiquidBarRow(view.categoryName(t.categoryId) ?: uncategorized, f.money(t.currentMinor), t.currentMinor / largest,
                        context = stringResource(R.string.usually, f.money(t.averageMinor)) + " · " +
                            stringResource(if (up) R.string.percent_more else R.string.percent_less, abs(t.change * 100).roundToInt()),
                        phase = t.currentMinor % 5 * 1.1f)
                }
            }
            // In one shared pot who paid what is nobody's business but the pot's.
            if (byMember.size > 1 && !io.github.sirallap.fulla.core.split.SharedPot.isShared(view.config.household)) {
                item { Section(stringResource(R.string.who_paid_period)) }
                val largest = byMember.maxOf { it.paidMinor }.coerceAtLeast(1).toFloat()
                items(byMember, key = { "m-" + it.memberId }) { m ->
                    val member = view.config.member(m.memberId)
                    LiquidBarRow(member?.displayName ?: "?", f.money(m.paidMinor), m.paidMinor / largest,
                        context = stringResource(R.string.their_share, f.money(m.shareMinor)))
                }
            }
            for (field in dimensions) {
                val totals = a.groupBy(view.active, period, field.key).filterKeys { it.isNotEmpty() }.filterValues { it > 0L }
                if (totals.isEmpty()) continue
                item(key = "d-" + field.key) {
                    Section(field.label(language))
                    val largest = totals.values.max().toFloat()
                    for ((value, amount) in totals.entries.sortedByDescending { it.value }) {
                        LiquidBarRow(fieldText(view, field, if (field.type == io.github.sirallap.fulla.core.schema.FieldType.BOOLEAN) value.toBooleanStrictOrNull() else value) ?: value,
                            f.money(amount), amount / largest)
                    }
                }
            }
            if (series.size > 1) item(key = "series") {
                val shown = series.take(6).reversed()
                val max = shown.maxOf { it.expenseMinor }.coerceAtLeast(1).toFloat()
                Section(stringResource(R.string.period_by_period))
                SpendingVials(shown.map { s ->
                    Vial(f.shortPeriod(s.period), null, level = s.expenseMinor.coerceAtLeast(0) / max, share = 0f,
                        description = f.period(s.period) + ", " + stringResource(R.string.in_out, f.money(s.incomeMinor), f.money(s.expenseMinor)),
                        top = f.whole(s.expenseMinor))
                })
                for (s in series.take(6)) {
                    ListRow(f.period(s.period),
                        context = stringResource(R.string.in_out, f.money(s.incomeMinor), f.money(s.expenseMinor)),
                        end = { AmountText(f.money(s.savingsMinor, signed = true), color = if (s.savingsMinor < 0) c.moneyOut else c.moneyIn) })
                }
            } else if (series.size == 1) item(key = "series") {
                val s = series.first()
                Section(stringResource(R.string.period_by_period))
                ListRow(f.period(s.period), context = stringResource(R.string.in_out, f.money(s.incomeMinor), f.money(s.expenseMinor)),
                    end = { AmountText(f.money(s.savingsMinor, signed = true), color = if (s.savingsMinor < 0) c.moneyOut else c.moneyIn) })
            }
            if (repeating.isNotEmpty()) {
                item { Section(stringResource(R.string.looks_recurring)) }
                items(repeating, key = { "r-" + it.note }) { r ->
                    val done = r.note in made
                    ListRow(r.note, context = stringResource(R.string.seen_in_months, r.months),
                        detail = if (done) stringResource(R.string.now_recurring)
                            else if (canEdit) stringResource(R.string.tap_to_make_recurring)
                            else stringResource(R.string.last_seen, f.day(r.lastDate)),
                        onClick = if (canEdit && !done) ({ makeRecurring(r) }) else null,
                        end = { AmountText(f.money(r.typicalMinor)) })
                }
            }
        }
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(toFloat())

/**
 * The period in figures, two glass tiles to a row, each filled with the jar's
 * liquid to what it measures: spent out of what came in, the part kept, how
 * far through the period, where it is heading, the fixed share, the days
 * without spending, the budgets over. A tile with nothing to say is left out.
 */
@Composable
private fun FigureTiles(
    view: HouseholdView,
    period: java.time.YearMonth,
    report: io.github.sirallap.fulla.core.analytics.PeriodReport,
    forecast: io.github.sirallap.fulla.core.analytics.PeriodForecast?,
    noSpend: Int,
) {
    val c = FullaTheme.colors
    val f = view.formats
    val range = f.periodRule.daysOf(period)
    val length = (java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1).toInt().coerceAtLeast(1)
    val tiles = mutableListOf<@Composable (Modifier) -> Unit>()

    val previous = report.previousSpentMinor
    val vsPrevious = when {
        previous == null || previous <= 0 -> null
        report.spentMinor == previous -> stringResource(R.string.vs_previous_same)
        else -> stringResource(if (report.spentMinor > previous) R.string.vs_previous_more else R.string.vs_previous_less,
            abs((report.spentMinor - previous) * 100.0 / previous).roundToInt())
    }
    tiles.add { m -> LiquidTile(stringResource(R.string.spent), f.money(report.spentMinor), m, context = vsPrevious,
        level = if (report.incomeMinor > 0) report.spentMinor.toFloat() / report.incomeMinor else null, phase = 0.2f) }
    report.savingsRate?.let { rate ->
        tiles.add { m -> LiquidTile(stringResource(R.string.savings), "${(rate * 100).roundToInt()} %", m,
            context = stringResource(R.string.of_income, f.money(report.incomeMinor)), level = rate.toFloat().coerceIn(0f, 1f),
            tone = LiquidTone.IN, valueColor = if (rate < 0) c.moneyOut else c.moneyIn, phase = 1.4f) }
    }
    if (report.days > 0) {
        tiles.add { m -> LiquidTile(stringResource(R.string.daily_average), f.money(report.dailyMinor), m,
            context = stringResource(R.string.day_of, report.days, length), level = report.days.toFloat() / length, phase = 2.1f) }
    }
    forecast?.takeIf { it.known }?.let { p ->
        val end = p.spentEndMinor!!
        tiles.add { m -> LiquidTile(stringResource(R.string.forecast), "≈ " + f.money(end), m,
            context = stringResource(R.string.forecast_between, f.money(p.spentEndLowMinor!!), f.money(p.spentEndHighMinor!!)),
            level = if (end > 0) (p.spentMinor.toFloat() / end).coerceIn(0f, 1f) else null, phase = 2.9f) }
    }
    if (report.count > 0) {
        tiles.add { m -> LiquidTile(stringResource(R.string.movements), "${report.count}", m,
            context = stringResource(R.string.movements_text, f.money(report.averageMinor))) }
    }
    val both = report.fixedMinor + report.variableMinor
    if (both > 0) {
        tiles.add { m -> LiquidTile(stringResource(R.string.fixed_variable), "${(report.fixedMinor * 100.0 / both).roundToInt()} %", m,
            context = stringResource(R.string.fixed_variable_text, f.money(report.fixedMinor), f.money(report.variableMinor)),
            level = report.fixedMinor.toFloat() / both, phase = 3.6f) }
    }
    if (report.days > 0) {
        tiles.add { m -> LiquidTile(stringResource(R.string.no_spend_days), "$noSpend", m,
            context = stringResource(R.string.of_days, report.days), level = noSpend.toFloat() / report.days, tone = LiquidTone.IN, phase = 4.4f) }
    }
    if (report.budgets > 0) {
        tiles.add { m -> LiquidTile(stringResource(R.string.budgets_over), stringResource(R.string.budgets_over_value, report.budgetsOver, report.budgets), m,
            level = report.budgetsOver.toFloat() / report.budgets, valueColor = if (report.budgetsOver > 0) c.moneyOut else Color.Unspecified, phase = 5.2f) }
    }
    for (pair in tiles.chunked(2)) {
        TileRow {
            for (tile in pair) tile(Modifier.weight(1f))
            if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        }
    }
}
