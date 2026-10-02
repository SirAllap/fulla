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
import java.time.LocalDate

/**
 * How the period is likely to end, then the fixed costs it is made of, both
 * on the jar's liquid. The first says what would be spent and what would be
 * kept, as a range that narrows with the days; a tap opens how it is worked
 * out. The second is the fixed costs that write themselves: the total, what
 * is still to be charged, what is left of the income once they are paid, and
 * what can be spent per day meanwhile.
 */
@Composable
fun ForecastSection(view: HouseholdView, forecast: PeriodForecast, onFixedCosts: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    var explaining by remember { mutableStateOf(false) }

    Section(stringResource(R.string.forecast), top = 16.dp)
    // What these figures are, so nobody has to guess: the day of the period, what they are made of, and that it is an estimate.
    if (forecast.known) {
        Text(stringResource(R.string.forecast_day, forecast.day, forecast.length) + if (forecast.early) " " + stringResource(R.string.forecast_early) else "",
            style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    }
    if (forecast.known) {
        val spendEnd = forecast.spentEndMinor!!
        TileRow {
            LiquidTile(stringResource(R.string.forecast_spend), "≈ " + f.money(spendEnd), Modifier.weight(1f).clickable { explaining = true },
                context = stringResource(R.string.forecast_between, f.money(forecast.spentEndLowMinor!!), f.money(forecast.spentEndHighMinor!!)),
                level = if (spendEnd > 0) (forecast.spentMinor.toFloat() / spendEnd).coerceIn(0f, 1f) else null, phase = 0.4f)
            val kept = forecast.keptMinor
            if (kept != null) {
                LiquidTile(stringResource(R.string.forecast_kept), "≈ " + f.money(kept), Modifier.weight(1f).clickable { explaining = true },
                    context = stringResource(R.string.forecast_between, f.money(forecast.keptLowMinor!!), f.money(forecast.keptHighMinor!!)),
                    level = (kept.toFloat() / forecast.totalIncomeMinor).coerceIn(0f, 1f), tone = LiquidTone.IN,
                    valueColor = if (kept < 0) c.moneyOut else c.moneyIn, phase = 1.6f)
            } else {
                LiquidTile(stringResource(R.string.forecast_kept), "—", Modifier.weight(1f).clickable { explaining = true },
                    context = stringResource(R.string.forecast_add_income), tone = LiquidTone.IN)
            }
        }
    } else if (forecast.waiting) {
        ListRow(stringResource(R.string.forecast_waiting), context = stringResource(R.string.forecast_waiting_text), divider = false)
    } else {
        ListRow(stringResource(R.string.forecast_no_history), divider = false, onClick = { explaining = true })
    }

    FixedCostsSection(view, forecast, onFixedCosts)

    if (explaining) ForecastSheet(view, forecast) { explaining = false }
}

/**
 * The fixed costs that write themselves: the total, what is still to be
 * charged, what is left of the income once they are paid, what can be spent
 * per day meanwhile, and each charge with its state. On the overview and in
 * the insights.
 */
@Composable
fun FixedCostsSection(view: HouseholdView, forecast: PeriodForecast, onFixedCosts: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val ledger = LocalContainer.current.ledger
    val scope = rememberCoroutineScope()
    val fixed = forecast.fixed
    if (fixed.isNotEmpty()) {
        Section(stringResource(R.string.fixed_costs), top = 16.dp)
        Text(stringResource(R.string.fixed_bars_help), style = FullaType.secondary, color = c.inkMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        val total = forecast.fixedTotalMinor
        val next = fixed.firstOrNull { it.status == FixedStatus.PENDING }
        TileRow {
            LiquidTile(stringResource(R.string.fixed_total_tile), f.money(total), Modifier.weight(1f),
                context = stringResource(R.string.fixed_progress, f.money(forecast.fixedPaidMinor), f.money(forecast.fixedToComeMinor)),
                level = if (total > 0) forecast.fixedPaidMinor.toFloat() / total else null, phase = 2.2f)
            LiquidTile(stringResource(R.string.fixed_to_come_tile), f.money(forecast.fixedToComeMinor), Modifier.weight(1f),
                // No liquid: a full glass here read as "all paid". What is paid is the glass on the left.
                context = next?.let { it.name + " · " + f.day(it.date) }, phase = 3.0f)
        }
        val left = forecast.leftToSpendMinor
        val perDay = forecast.perDayMinor
        if (left != null) {
            TileRow {
                LiquidTile(stringResource(R.string.left_to_spend), f.money(left), Modifier.weight(1f),
                    context = stringResource(R.string.left_to_spend_text, f.money(forecast.totalIncomeMinor), f.money(forecast.spentMinor), f.money(forecast.fixedToComeMinor)),
                    level = (left.toFloat() / forecast.totalIncomeMinor).coerceIn(0f, 1f), tone = LiquidTone.IN,
                    valueColor = if (left < 0) c.moneyOut else c.moneyIn, phase = 3.8f)
                if (perDay != null) {
                    LiquidTile(stringResource(R.string.per_day), f.money(perDay), Modifier.weight(1f),
                        context = stringResource(R.string.per_day_text, forecast.length - forecast.day),
                        tone = LiquidTone.IN, valueColor = if (perDay < 0) c.moneyOut else c.moneyIn, phase = 4.6f)
                } else Spacer(Modifier.weight(1f))
            }
        } else {
            Text(stringResource(R.string.forecast_add_income), style = FullaType.secondary, color = c.inkMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        val today = java.time.LocalDate.now()
        for (item in fixed) {
            val installmentText = item.installment?.let { n -> item.installments?.let { total -> stringResource(R.string.installment_of, n, total) } }
            val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, item.date).toInt()
            val daysText = if (item.status == FixedStatus.PENDING && !item.overdue && daysLeft > 0) stringResource(R.string.in_days, daysLeft) else null
            LiquidBarRow(
                title = item.name,
                amount = f.money(item.amountMinor),
                // The bar fills as the day comes closer, over the length of the period; once paid it is full and takes the colour of money in.
                fraction = item.countdown(today, forecast.length),
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

/** How the forecast is worked out, line by line, so every figure can be checked. */
@Composable
private fun ForecastSheet(view: HouseholdView, forecast: PeriodForecast, onDismiss: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.paper) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Section(stringResource(R.string.forecast_how), top = 0.dp)
            ListRow(stringResource(R.string.forecast_income),
                context = if (forecast.expectedIncomeMinor > 0) stringResource(R.string.forecast_income_context, f.money(forecast.incomeMinor), f.money(forecast.expectedIncomeMinor)) else null,
                end = { AmountText(f.money(forecast.totalIncomeMinor), color = c.moneyIn) })
            ListRow(stringResource(R.string.forecast_spent_so_far), end = { AmountText(f.money(-forecast.spentMinor)) })
            val pending = forecast.fixed.filter { it.status == FixedStatus.PENDING }
            ListRow(stringResource(R.string.forecast_fixed_to_come),
                context = pending.take(4).joinToString(" · ") { it.name + " " + f.day(it.date) }.ifBlank { null },
                end = { AmountText(f.money(-forecast.fixedToComeMinor)) })
            val rest = forecast.everydayRestMinor
            if (rest != null) {
                ListRow(stringResource(R.string.forecast_everyday),
                    context = stringResource(R.string.forecast_between, f.money(forecast.everydayLowMinor!!), f.money(forecast.everydayHighMinor!!)),
                    end = { AmountText(f.money(-rest)) })
                val kept = forecast.keptMinor
                ListRow(stringResource(if (kept != null) R.string.forecast_kept else R.string.forecast_spend),
                    titleColor = c.ink, divider = false,
                    end = { AmountText("≈ " + f.money(kept ?: forecast.spentEndMinor!!), color = if (kept != null && kept < 0) c.moneyOut else c.ink) })
            } else {
                Text(stringResource(R.string.forecast_no_history), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(20.dp))
            }
            Text(stringResource(R.string.forecast_note), style = FullaType.secondary, color = c.inkMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}
