// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.analytics

import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.time.YearMonth

object Budgets {
    /** The budget of each category for [period]: a budget set for that period wins over the default. */
    fun forPeriod(config: Config, period: YearMonth): Map<String, Long> {
        val key = period.toString()
        val defaults = config.budgets.filter { it.period == null }.associate { it.categoryId to it.amountMinor }
        val overrides = config.budgets.filter { it.period == key }.associate { it.categoryId to it.amountMinor }
        return (defaults + overrides).filterValues { it > 0 }
    }

    /**
     * The budgets of a period taken together: all they allow and what the
     * budgeted categories used (trips with their own jar already left out of
     * [used]). Null with no budget at all.
     */
    fun status(limits: Map<String, Long>, used: Map<String, Long>): BudgetStatus? =
        if (limits.isEmpty()) null else BudgetStatus(limits.values.sum(), used.filterKeys { it in limits }.values.sum())
}

/** What the budgets allow and what was used of it. Left and over are never negative, one of them is always 0. */
data class BudgetStatus(val limitMinor: Long, val usedMinor: Long) {
    val leftMinor: Long get() = maxOf(limitMinor - usedMinor, 0)
    val overMinor: Long get() = maxOf(usedMinor - limitMinor, 0)
    val fraction: Float get() = if (limitMinor > 0) usedMinor.toFloat() / limitMinor else 0f
}
