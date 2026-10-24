// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.widget

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.analytics.BudgetStatus
import io.github.sirallap.fulla.core.analytics.Budgets
import io.github.sirallap.fulla.core.analytics.DayTotals
import io.github.sirallap.fulla.core.goals.Goal
import io.github.sirallap.fulla.core.goals.GoalProgress
import io.github.sirallap.fulla.core.goals.Goals
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.roundup.RoundUps
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

/** What a widget shows; each one is chosen when it is placed, and can be changed by reconfiguring it. */
enum class WidgetMode(val key: String) {
    /** What is left of the period, per day, and what was spent ([WidgetFigures]). */
    LEFT("left"),
    /** What was spent today, against what can be spent per day. */
    TODAY("today"),
    /** The period's budget: left or over. */
    BUDGET("budget"),
    /** What the accounts hold today. */
    ACCOUNTS("accounts"),
    /** The last few things written down. */
    RECENT("recent"),
    /** How far the first savings goal has got. */
    GOAL("goal");

    companion object {
        fun of(key: String?): WidgetMode = entries.firstOrNull { it.key == key } ?: LEFT
    }
}

/**
 * The other widgets' figures, each the same one a screen of the app shows
 * (the overview's budget line and accounts total, History's rows, the goals in
 * Balances), so a widget never says something the app does not.
 */
object WidgetData {
    /** Today's spending, counted as History counts a day, and the overview's per-day figure (null without income). */
    data class Today(val spentMinor: Long, val perDayMinor: Long?) {
        val over: Boolean get() = perDayMinor != null && spentMinor > perDayMinor
    }

    fun today(analytics: Analytics, txs: List<Transaction>, period: YearMonth, today: LocalDate, deleted: Set<String> = emptySet()): Today {
        val spent = DayTotals.ofCounted(txs.filter { it.date == today }).spentMinor
        val left = WidgetFigures.of(analytics, txs, period, today, deleted)
        return Today(spent, left.perDayMinor)
    }

    /** The period's budgets added up, as the overview's "Budget of the period"; null when none is set. */
    fun budget(config: Config, analytics: Analytics, txs: List<Transaction>, period: YearMonth): BudgetStatus? =
        Budgets.status(Budgets.forPeriod(config, period),
            analytics.budgetSpend(txs, period, config.trips).associate { it.categoryId to it.amountMinor })

    /** What the open accounts hold today, as the overview's "Accounts total"; null until a starting balance is set. */
    fun accounts(config: Config, analytics: Analytics, txs: List<Transaction>, today: LocalDate): Long? {
        val open = config.accounts.filter { !it.archived }
        return if (open.none { it.openingBalanceMinor != 0L }) null else analytics.accountsTotal(txs, open, today)
    }

    /**
     * The last [count] things written down, newest first by date and then by
     * when they were written; a round-up is left out, as History folds it under
     * its purchase, and so is anything dated in the future.
     */
    fun recent(txs: List<Transaction>, today: LocalDate, count: Int = 3): List<Transaction> =
        txs.filter { !RoundUps.isRoundUp(it) && it.date <= today }
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.createdAt })
            .take(count)

    /** The first savings goal still open, with its progress as Balances shows it; null without one. */
    fun goal(config: Config, analytics: Analytics, txs: List<Transaction>, today: LocalDate): Pair<Goal, GoalProgress>? {
        val goal = config.goals.firstOrNull { !it.archived } ?: return null
        val balance = analytics.accountBalances(txs, config.accounts, today)[goal.accountId] ?: 0
        return goal to Goals.progress(goal, balance, today)
    }
}
