// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.insights

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * The longer view, in plain rows: how each period went, what moved against
 * the usual, what looks like it repeats, and who paid. Deliberately no
 * charts: the jar on the overview is the one picture.
 */
@Composable
fun InsightsScreen(view: HouseholdView, onBack: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val a = view.analytics
    val today = LocalDate.now()
    val period = remember(view) { f.currentPeriod(today) }
    val series = remember(view) { a.series(view.active, period, 12).reversed().filter { it.incomeMinor != 0L || it.expenseMinor != 0L } }
    val unit = remember(view) { (0 until f.currency.minorUnits).fold(1L) { acc, _ -> acc * 10 } }
    val trends = remember(view) { a.trends(view.active, period, minimumMinor = 10 * unit) }
    val repeating = remember(view) { a.detectedRecurring(view.active, today) }
    val byMember = remember(view) { a.byMember(view.active, period).filter { it.paidMinor != 0L || it.shareMinor != 0L } }
    val noSpend = remember(view) { a.noSpendDays(view.active, period, today) }
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
        LazyColumn(Modifier.weight(1f)) {
            item {
                Section(f.period(period), top = 8.dp())
                ListRow(stringResource(R.string.no_spend_days), context = stringResource(R.string.no_spend_days_text),
                    end = { AmountText("$noSpend", color = c.inkMuted) })
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
            if (byMember.size > 1) {
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

