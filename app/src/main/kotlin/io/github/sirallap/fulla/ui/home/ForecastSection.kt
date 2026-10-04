// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import io.github.sirallap.fulla.core.analytics.FixedItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.local.RecurringPlanner
import io.github.sirallap.fulla.core.analytics.FixedStatus
import io.github.sirallap.fulla.core.analytics.PeriodForecast
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.LiquidBarRow
import io.github.sirallap.fulla.ui.components.LiquidTile
import io.github.sirallap.fulla.ui.components.LiquidTone
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.TileRow
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch
import io.github.sirallap.fulla.core.time.LocalDate

/**
 * How the period is likely to end, then the fixed costs it is made of, both
 * on the jar's liquid. The first says what would be spent and what would be
 * kept, as a range that narrows with the days; a tap opens how it is worked
 * out. The second is the fixed costs that write themselves: the total, what
 * is still to be charged, what is left of the income once they are paid, and
 * what can be spent per day meanwhile.
 */
@Composable
fun ForecastSection(view: HouseholdView, forecast: PeriodForecast) {
    val c = FullaTheme.colors
    val f = view.formats

    Section(stringResource(R.string.forecast), top = 16.dp)
    if (forecast.known && forecast.early) {
        // Under a quarter of the period gone, the range is so wide that the figures would only alarm: nothing is shown until they mean something.
        // Plain text, not a row: a row's title is one line, and this is a sentence.
        Text(stringResource(R.string.forecast_too_early, forecast.day, forecast.length), style = FullaType.secondary, color = c.inkMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    } else if (forecast.known) {
        // What these figures are, so nobody has to guess: the day of the period, what they are made of, and that it is an estimate.
        Text(stringResource(R.string.forecast_day, forecast.day, forecast.length),
            style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        val spendEnd = forecast.spentEndMinor!!
        TileRow {
            LiquidTile(stringResource(R.string.forecast_spend), "≈ " + f.money(spendEnd), Modifier.weight(1f),
                context = stringResource(R.string.forecast_between, f.money(forecast.spentEndLowMinor!!), f.money(forecast.spentEndHighMinor!!)),
                level = if (spendEnd > 0) (forecast.spentMinor.toFloat() / spendEnd).coerceIn(0f, 1f) else null, phase = 0.4f)
            val kept = forecast.keptMinor
            if (kept != null) {
                LiquidTile(stringResource(R.string.forecast_kept), "≈ " + f.money(kept), Modifier.weight(1f),
                    context = stringResource(R.string.forecast_between, f.money(forecast.keptLowMinor!!), f.money(forecast.keptHighMinor!!)),
                    level = (kept.toFloat() / forecast.totalIncomeMinor).coerceIn(0f, 1f), tone = LiquidTone.IN,
                    valueColor = if (kept < 0) c.moneyOut else c.moneyIn, phase = 1.6f)
            } else {
                LiquidTile(stringResource(R.string.forecast_kept), "—", Modifier.weight(1f),
                    context = stringResource(R.string.forecast_add_income), tone = LiquidTone.IN)
            }
        }
    } else if (forecast.waiting) {
        ListRow(stringResource(R.string.forecast_waiting), context = stringResource(R.string.forecast_waiting_text), divider = false)
    } else {
        Text(stringResource(R.string.forecast_no_history), style = FullaType.secondary, color = c.inkMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }

    if (!forecast.waiting) ForecastBreakdown(view, forecast)
}

/**
 * The numbers of the fixed costs, for the insights: the total and how much of
 * it is charged, what is left to spend and what that is per day. The charges
 * themselves, each with its bar, are on the overview ([FixedCostsSection]).
 */
@Composable
fun FixedTotalsSection(view: HouseholdView, forecast: PeriodForecast, onFixedCosts: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    if (forecast.fixed.isEmpty()) return
    Section(stringResource(R.string.fixed_costs), top = 16.dp)
    val total = forecast.fixedTotalMinor
    val left = forecast.leftToSpendMinor
    val perDay = forecast.perDayMinor
    TileRow {
        // One tile for the fixed costs: the total, how much is charged and how much is not.
        LiquidTile(stringResource(R.string.fixed_total_tile), f.money(total), Modifier.weight(1f).clickable { onFixedCosts() },
            context = stringResource(R.string.fixed_progress, f.money(forecast.fixedPaidMinor), f.money(forecast.fixedToComeMinor)),
            level = if (total > 0) forecast.fixedPaidMinor.toFloat() / total else null, phase = 2.2f)
        if (left != null) {
            LiquidTile(stringResource(R.string.left_to_spend), f.money(left), Modifier.weight(1f),
                context = stringResource(R.string.left_to_spend_text),
                level = (left.toFloat() / forecast.totalIncomeMinor).coerceIn(0f, 1f), tone = LiquidTone.IN,
                valueColor = if (left < 0) c.moneyOut else c.moneyIn, phase = 3.8f)
        } else Spacer(Modifier.weight(1f))
    }
    if (left != null && perDay != null) {
        TileRow {
            LiquidTile(stringResource(R.string.per_day), f.money(perDay), Modifier.weight(1f),
                context = forecast.everydayPerDayMinor?.let { stringResource(R.string.per_day_text_pace, forecast.daysToGo, f.money(it)) }
                    ?: stringResource(R.string.per_day_text, forecast.daysToGo),
                tone = LiquidTone.IN, valueColor = if (perDay < 0) c.moneyOut else c.moneyIn, phase = 4.6f)
            Spacer(Modifier.weight(1f))
        }
    }
    if (left == null) {
        Text(stringResource(R.string.forecast_add_income), style = FullaType.secondary, color = c.inkMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
}

/**
 * The fixed costs that write themselves, as the overview shows them and
 * nothing more: each charge with its bar, when it is due and whether it has
 * been charged. The figures are in the insights ([FixedTotalsSection]).
 */
@Composable
fun FixedCostsSection(view: HouseholdView, forecast: PeriodForecast, onFixedCosts: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val fixed = forecast.fixed
    // The period is open past its length: the one thing the person can do about it is to note the salary, so the overview still says so.
    if (forecast.waiting) {
        ListRow(stringResource(R.string.forecast_waiting), context = stringResource(R.string.forecast_waiting_text), divider = false)
    }
    if (fixed.isNotEmpty()) {
        Section(stringResource(R.string.fixed_costs), top = 16.dp)
        Text(stringResource(R.string.fixed_bars_help), style = FullaType.secondary, color = c.inkMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        val today = io.github.sirallap.fulla.core.time.LocalDate.now()
        // What is still to come stays in sight; what was already charged this period is one tap away, folded.
        val coming = fixed.filter { it.status == FixedStatus.PENDING }
        val done = fixed.filter { it.status != FixedStatus.PENDING }
        for (item in coming) FixedBarRow(view, item, today, forecast.length, onFixedCosts)
        if (done.isNotEmpty()) {
            var open by rememberSaveable { mutableStateOf(false) }
            val charged = done.filter { it.status == FixedStatus.PAID }
            ListRow(
                stringResource(if (charged.size == done.size) R.string.fixed_charged_group else R.string.fixed_charged_skipped_group, done.size),
                icon = Icons.Outlined.CheckCircle, iconTint = c.moneyIn,
                onClick = { open = !open },
                end = {
                    AmountText(f.money(charged.sumOf { it.amountMinor }), color = c.inkMuted)
                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = c.inkMuted)
                },
            )
            if (open) for (item in done) FixedBarRow(view, item, today, forecast.length, onFixedCosts)
        }
    } else if (view.config.recurringRules.none { it.active }) {
        ListRow(stringResource(R.string.fixed_empty_title), context = stringResource(R.string.fixed_empty_text), onClick = onFixedCosts)
    }
    // What fell due before this period and was never written: the person is told, and decides in Fixed costs.
    val leftOut = remember(view) {
        val today = LocalDate.now()
        runCatching {
            RecurringPlanner.leftOut(view.config, view.rows.map { it.transaction.id }.toSet(), view.active, today,
                RecurringPlanner.currentPeriodStart(view.config, view.active, today))
        }.getOrDefault(emptyList())
    }
    if (leftOut.isNotEmpty()) {
        ListRow(stringResource(R.string.fixed_missed_hint, leftOut.size), context = stringResource(R.string.fixed_missed_hint_text),
            icon = Icons.Outlined.Warning, iconTint = c.warning, onClick = onFixedCosts)
    }
}

/** One fixed cost of the period: its bar, when it is due or was charged, which instalment it is. */
@Composable
private fun FixedBarRow(view: HouseholdView, item: FixedItem, today: LocalDate, periodLength: Int, onFixedCosts: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val ledger = LocalContainer.current.ledger
    val scope = rememberCoroutineScope()
    val installmentText = item.installment?.let { n -> item.installments?.let { total -> stringResource(R.string.installment_of, n, total) } }
    val daysLeft = io.github.sirallap.fulla.core.time.ChronoUnit.DAYS.between(today, item.date).toInt()
    val daysText = if (item.status == FixedStatus.PENDING && !item.overdue && daysLeft > 0) stringResource(R.string.in_days, daysLeft) else null
    LiquidBarRow(
        title = item.name,
        amount = f.money(item.amountMinor),
        // The bar fills as the day comes closer, over the length of the period; once paid it is full and takes the colour of money in.
        fraction = item.countdown(today, periodLength),
        tone = if (item.status == FixedStatus.PAID) LiquidTone.IN else LiquidTone.OUT,
        context = when {
            item.status == FixedStatus.PAID && item.byHand -> stringResource(R.string.fixed_by_hand, f.day(item.date))
            item.status == FixedStatus.PAID -> stringResource(R.string.fixed_paid_on, f.day(item.date))
            item.status == FixedStatus.PENDING && item.overdue -> stringResource(R.string.fixed_overdue, f.day(item.date))
            item.status == FixedStatus.PENDING -> stringResource(R.string.fixed_due_on, f.day(item.date))
            else -> stringResource(R.string.fixed_skipped)
        }.let { base -> listOfNotNull(base, daysText).joinToString(" · ") },
        detail = installmentText,
        phase = item.date.dayOfMonth * 0.7f,
        start = {
            Icon(
                when {
                    item.status == FixedStatus.PAID -> Icons.Outlined.CheckCircle
                    item.status == FixedStatus.PENDING && item.overdue -> Icons.Outlined.Warning
                    item.status == FixedStatus.PENDING -> Icons.Outlined.Schedule
                    else -> Icons.Outlined.RemoveCircleOutline
                },
                null,
                tint = when {
                    item.status == FixedStatus.PAID -> c.moneyIn
                    item.status == FixedStatus.PENDING && item.overdue -> c.warning
                    else -> c.inkMuted
                },
                modifier = Modifier.size(20.dp),
            )
        },
        // A charge whose day has come and that nothing has written: the phone writes it by itself the next time it looks, and this does it now.
        below = if (item.status == FixedStatus.PENDING && item.overdue) ({
            TextButton(onClick = { scope.launch { ledger.applyRecurring(view.id, item.ruleId, item.date) } }) {
                Text(stringResource(R.string.fixed_apply))
            }
        }) else null,
        onClick = onFixedCosts,
    )
}

/** How the forecast is worked out, line by line, so every figure can be checked. */
@Composable
private fun ForecastBreakdown(view: HouseholdView, forecast: PeriodForecast) {
    val c = FullaTheme.colors
    val f = view.formats
    Section(stringResource(R.string.forecast_how), top = 16.dp)
    ListRow(stringResource(R.string.forecast_income),
        context = if (forecast.expectedIncomeMinor > 0) stringResource(R.string.forecast_income_context, f.money(forecast.incomeMinor), f.money(forecast.expectedIncomeMinor)) else null,
        end = { AmountText(f.money(forecast.totalIncomeMinor), color = c.moneyIn) })
    ListRow(stringResource(R.string.forecast_spent_so_far), end = { AmountText(f.money(-forecast.spentMinor)) })
    val pending = forecast.fixed.filter { it.status == FixedStatus.PENDING }
    ListRow(stringResource(R.string.forecast_fixed_to_come),
        context = pending.take(4).joinToString(" · ") { it.name + " " + f.day(it.date) }.ifBlank { null },
        end = { AmountText(f.money(-forecast.fixedToComeMinor)) })
    // What is certain: the income less what was spent and what is still to be charged. Only the everyday spending to come is a guess.
    val free = forecast.leftToSpendMinor
    if (free != null) {
        ListRow(stringResource(R.string.forecast_free), context = stringResource(R.string.forecast_free_text), titleColor = c.ink,
            end = { AmountText(f.money(free), color = if (free < 0) c.moneyOut else c.moneyIn) })
    } else {
        Text(stringResource(R.string.forecast_add_income), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
    val rest = forecast.everydayRestMinor
    if (rest != null && !forecast.early) {
        ListRow(stringResource(R.string.forecast_everyday),
            context = stringResource(R.string.forecast_between, f.money(forecast.everydayLowMinor!!), f.money(forecast.everydayHighMinor!!)),
            end = { AmountText(f.money(-rest)) })
        val kept = forecast.keptMinor
        ListRow(stringResource(if (kept != null) R.string.forecast_kept else R.string.forecast_spend),
            titleColor = c.ink, divider = false,
            end = { AmountText("≈ " + f.money(kept ?: forecast.spentEndMinor!!), color = if (kept != null && kept < 0) c.moneyOut else c.ink) })
    }
    Text(stringResource(if (forecast.ownPace) R.string.forecast_note_own else R.string.forecast_note), style = FullaType.secondary, color = c.inkMuted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}
