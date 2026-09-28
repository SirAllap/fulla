// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.insights

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
    val projection = remember(view, period) { if (period == current) a.projection(view.active, period, today) else null }
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
            if (top.isNotEmpty()) item(key = "vials") {
                val total = top.sumOf { it.amountMinor }.toFloat()
                val max = top.maxOf { it.amountMinor }.toFloat()
                Section(stringResource(R.string.where_it_went), top = 8.dp())
                SpendingVials(top.map { s ->
                    val cat = s.key?.let { view.config.category(it) }
                    val name = if (s.key == null) others else cat?.name ?: uncategorized
                    Vial(name, if (s.key == null) null else io.github.sirallap.fulla.ui.entry.CategoryIcons.of(cat?.icon ?: "label"),
                        level = s.amountMinor / max, share = s.amountMinor / total, description = "$name, ${f.money(s.amountMinor)}")
                })
            }
            item(key = "summary") {
                Section(stringResource(R.string.period_summary), top = if (top.isEmpty()) 8.dp() else 24.dp())
                val previous = report.previousSpentMinor
                ListRow(stringResource(R.string.spent), context = when {
                    previous == null || previous <= 0 -> null
                    report.spentMinor == previous -> stringResource(R.string.vs_previous_same)
                    else -> {
                        val change = abs((report.spentMinor - previous) * 100.0 / previous).roundToInt()
                        stringResource(if (report.spentMinor > previous) R.string.vs_previous_more else R.string.vs_previous_less, change)
                    }
                }, end = { AmountText(f.money(report.spentMinor)) })
                report.savingsRate?.let { rate ->
                    val pct = abs(rate * 100).roundToInt()
                    ListRow(stringResource(R.string.money_in), context = stringResource(if (rate >= 0) R.string.saving_rate else R.string.overspending_rate, pct),
                        end = { AmountText(f.money(report.incomeMinor), color = c.moneyIn) })
                }
                if (report.days > 0) {
                    ListRow(stringResource(R.string.daily_average), context = stringResource(R.string.daily_average_text, report.days),
                        end = { AmountText(f.money(report.dailyMinor)) })
                }
                projection?.let { p ->
                    ListRow(stringResource(R.string.forecast), context = stringResource(R.string.forecast_text),
                        end = { AmountText(f.money(p.projectedMinor)) })
                }
                if (report.count > 0) {
                    ListRow(stringResource(R.string.movements), context = stringResource(R.string.movements_text, f.money(report.averageMinor)),
                        end = { AmountText("${report.count}", color = c.inkMuted) })
                }
                val both = report.fixedMinor + report.variableMinor
                if (both > 0) {
                    ListRow(stringResource(R.string.fixed_variable),
                        context = stringResource(R.string.fixed_variable_text, f.money(report.fixedMinor), f.money(report.variableMinor)),
                        below = { ProgressLine(report.fixedMinor.toFloat() / both, c.ink) },
                        end = { AmountText("${(report.fixedMinor * 100.0 / both).roundToInt()} %", color = c.inkMuted) })
                }
                if (report.budgets > 0) {
                    ListRow(stringResource(R.string.budgets_over),
                        end = { AmountText(stringResource(R.string.budgets_over_value, report.budgetsOver, report.budgets),
                            color = if (report.budgetsOver > 0) c.moneyOut else c.inkMuted) })
                }
                if (report.days > 0) {
                    ListRow(stringResource(R.string.no_spend_days), context = stringResource(R.string.no_spend_days_text),
                        end = { AmountText("$noSpend", color = c.inkMuted) })
                }
            }
            if (report.weekdays.any { it > 0 }) item(key = "weekdays") {
                val total = report.weekdays.filter { it > 0 }.sum().toFloat()
                val max = report.weekdays.max().toFloat()
                Section(stringResource(R.string.by_weekday))
                SpendingVials(report.weekdays.mapIndexed { i, amount ->
                    val day = java.time.DayOfWeek.of(i + 1).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                    val positive = amount.coerceAtLeast(0)
                    Vial(day, null, level = positive / max, share = positive / total, description = "$day, ${f.money(amount)}")
                })
            }
            if (report.biggest.isNotEmpty()) {
                item { Section(stringResource(R.string.biggest_expenses)) }
                items(report.biggest, key = { "b-" + it.id }) { t ->
                    val category = view.categoryName(t.categoryId)
                    ListRow(t.note.ifBlank { category ?: uncategorized },
                        context = listOfNotNull(category.takeIf { t.note.isNotBlank() }, f.day(t.date)).joinToString(" · "),
                        end = { AmountText(f.money(t.amountMinor)) })
                }
            }
            if (report.places.isNotEmpty()) {
                item { Section(stringResource(R.string.repeated_most)) }
                items(report.places, key = { "p-" + it.name }) { p ->
                    ListRow(p.name, context = stringResource(R.string.times, p.count), end = { AmountText(f.money(p.totalMinor)) })
                }
            }
            if (trends.isNotEmpty()) {
                item { Section(stringResource(R.string.against_usual)) }
                items(trends, key = { "t-" + it.categoryId }) { t ->
                    val up = t.currentMinor > t.averageMinor
                    ListRow(view.categoryName(t.categoryId) ?: stringResource(R.string.uncategorized),
                        context = stringResource(R.string.usually, f.money(t.averageMinor)),
                        detail = stringResource(if (up) R.string.percent_more else R.string.percent_less, abs(t.change * 100).roundToInt()),
                        detailColor = if (up) c.moneyOut else c.moneyIn,
                        end = { AmountText(f.money(t.currentMinor)) })
                }
            }
            // In one shared pot who paid what is nobody's business but the pot's.
            if (byMember.size > 1 && !io.github.sirallap.fulla.core.split.SharedPot.isShared(view.config.household)) {
                item { Section(stringResource(R.string.who_paid_period)) }
                items(byMember, key = { "m-" + it.memberId }) { m ->
                    val member = view.config.member(m.memberId)
                    ListRow(member?.displayName ?: "?", start = { MemberBadge(member?.initials ?: "?", member?.colorIndex ?: 0) },
                        context = stringResource(R.string.their_share, f.money(m.shareMinor)),
                        end = { AmountText(f.money(m.paidMinor)) })
                }
            }
            for (field in dimensions) {
                val totals = a.groupBy(view.active, period, field.key).filterKeys { it.isNotEmpty() }.filterValues { it != 0L }
                if (totals.isEmpty()) continue
                item(key = "d-" + field.key) {
                    Section(field.label(language))
                    for ((value, amount) in totals.entries.sortedByDescending { it.value }) {
                        ListRow(fieldText(view, field, if (field.type == io.github.sirallap.fulla.core.schema.FieldType.BOOLEAN) value.toBooleanStrictOrNull() else value) ?: value,
                            end = { AmountText(f.money(amount)) })
                    }
                }
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
            if (series.isNotEmpty()) {
                item { Section(stringResource(R.string.period_by_period)) }
                items(series, key = { "s-" + it.period }) { s ->
                    ListRow(f.period(s.period),
                        context = stringResource(R.string.in_out, f.money(s.incomeMinor), f.money(s.expenseMinor)),
                        below = if (s.incomeMinor > 0) ({
                            ProgressLine(s.expenseMinor.toFloat() / s.incomeMinor, c.moneyOut, over = s.expenseMinor > s.incomeMinor)
                        }) else null,
                        end = { AmountText(f.money(s.savingsMinor, signed = true), color = if (s.savingsMinor < 0) c.moneyOut else c.moneyIn) })
                }
            }
        }
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(toFloat())

