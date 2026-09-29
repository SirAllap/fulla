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
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.analytics.FixedStatus
import io.github.sirallap.fulla.core.analytics.PeriodForecast
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.LiquidBarRow
import io.github.sirallap.fulla.ui.components.LiquidTile
import io.github.sirallap.fulla.ui.components.LiquidTone
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.TileRow
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType

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
    } else {
        ListRow(stringResource(R.string.forecast_no_history), divider = false, onClick = { explaining = true })
    }

    val fixed = forecast.fixed
    if (fixed.isNotEmpty()) {
        Section(stringResource(R.string.fixed_costs), top = 16.dp)
        val total = forecast.fixedTotalMinor
        val next = fixed.firstOrNull { it.status == FixedStatus.PENDING }
        TileRow {
            LiquidTile(stringResource(R.string.fixed_total_tile), f.money(total), Modifier.weight(1f),
                context = stringResource(R.string.fixed_progress, f.money(forecast.fixedPaidMinor), f.money(forecast.fixedToComeMinor)),
                level = if (total > 0) forecast.fixedPaidMinor.toFloat() / total else null, phase = 2.2f)
            LiquidTile(stringResource(R.string.fixed_to_come_tile), f.money(forecast.fixedToComeMinor), Modifier.weight(1f),
                context = next?.let { it.name + " · " + f.day(it.date) },
                level = if (total > 0) forecast.fixedToComeMinor.toFloat() / total else null, phase = 3.0f)
        }
        val after = forecast.afterFixedMinor
        val perDay = forecast.perDayMinor
        if (after != null) {
            TileRow {
                LiquidTile(stringResource(R.string.fixed_after), f.money(after), Modifier.weight(1f),
                    context = stringResource(R.string.fixed_of_income, f.money(forecast.totalIncomeMinor)),
                    level = (after.toFloat() / forecast.totalIncomeMinor).coerceIn(0f, 1f), tone = LiquidTone.IN,
                    valueColor = if (after < 0) c.moneyOut else c.moneyIn, phase = 3.8f)
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
        val largest = fixed.maxOf { it.amountMinor }.coerceAtLeast(1).toFloat()
        for (item in fixed) {
            LiquidBarRow(
                title = item.name,
                amount = f.money(item.amountMinor),
                fraction = if (item.status == FixedStatus.SKIPPED) 0f else item.amountMinor / largest,
                context = when (item.status) {
                    FixedStatus.PAID -> stringResource(R.string.fixed_paid_on, f.day(item.date))
                    FixedStatus.PENDING -> stringResource(R.string.fixed_due_on, f.day(item.date))
                    FixedStatus.SKIPPED -> stringResource(R.string.fixed_skipped)
                },
                phase = item.date.dayOfMonth * 0.7f,
                start = {
                    Icon(
                        when (item.status) {
                            FixedStatus.PAID -> Icons.Outlined.CheckCircle
                            FixedStatus.PENDING -> Icons.Outlined.Schedule
                            FixedStatus.SKIPPED -> Icons.Outlined.RemoveCircleOutline
                        },
                        null, tint = if (item.status == FixedStatus.PAID) c.moneyIn else c.inkMuted, modifier = Modifier.size(20.dp),
                    )
                },
                onClick = onFixedCosts,
            )
        }
    } else if (view.config.recurringRules.none { it.active }) {
        ListRow(stringResource(R.string.fixed_empty_title), context = stringResource(R.string.fixed_empty_text), onClick = onFixedCosts)
    }

    if (explaining) ForecastSheet(view, forecast) { explaining = false }
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
