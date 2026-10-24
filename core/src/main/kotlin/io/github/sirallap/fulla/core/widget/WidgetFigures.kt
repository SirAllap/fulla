// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.widget

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.model.Transaction
import java.time.LocalDate
import java.time.YearMonth

/**
 * What the home-screen widget shows of the running period, taken from the
 * same figures as the overview so the two never disagree: what was spent
 * ([Analytics.summary]), what is left of the income once the fixed costs to
 * come are paid and how much that is per day ([PeriodForecast.leftToSpendMinor],
 * [PeriodForecast.perDayMinor]). Left and over are never both shown and never
 * negative; without income, or while the salary is awaited, only what was spent.
 */
data class WidgetFigures(
    val spentMinor: Long,
    val leftMinor: Long?,
    val overMinor: Long?,
    val perDayMinor: Long?,
    val daysToGo: Int?,
) {
    companion object {
        fun of(analytics: Analytics, txs: List<Transaction>, period: YearMonth, today: LocalDate, deleted: Set<String> = emptySet()): WidgetFigures {
            val spent = analytics.summary(txs, period).expenseMinor
            val forecast = runCatching { analytics.forecast(txs, period, today, deleted) }.getOrNull()?.takeIf { !it.waiting }
            val left = forecast?.leftToSpendMinor
            return WidgetFigures(
                spentMinor = spent,
                leftMinor = left?.coerceAtLeast(0),
                overMinor = left?.takeIf { it < 0 }?.let { -it },
                perDayMinor = forecast?.perDayMinor?.takeIf { left != null && left > 0 },
                daysToGo = forecast?.takeIf { it.perDayMinor != null }?.daysToGo,
            )
        }
    }
}
