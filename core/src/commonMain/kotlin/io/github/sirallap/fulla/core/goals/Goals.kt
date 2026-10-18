// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.goals

import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth

/**
 * A savings goal: an amount to reach, kept in one account, by a date if there is one. What has been saved is that
 * account's balance; nothing else is written down for it, so the goal and the account can never disagree.
 */
data class Goal(
    val id: String,
    val name: String,
    val targetMinor: Long,
    val accountId: String,
    val targetDate: LocalDate? = null,
    val archived: Boolean = false,
)

/**
 * Where a goal stands. [savedMinor] is the account's balance, never below zero; [leftMinor] what is still missing,
 * never below zero; [perMonthMinor] what to put aside each month to arrive on the date (null without a date, once
 * reached, or when the date has gone by: [late]).
 */
data class GoalProgress(
    val savedMinor: Long,
    val leftMinor: Long,
    val fraction: Double,
    val perMonthMinor: Long?,
    val monthsLeft: Int?,
    val late: Boolean,
) {
    val reached: Boolean get() = leftMinor == 0L
}

object Goals {
    /**
     * [balanceMinor] is the goal's account balance today. The months left count the current one and the date's own,
     * so a goal for the end of next month has two months to go, and what is missing is spread over them rounded up:
     * putting aside [GoalProgress.perMonthMinor] each month always gets there.
     */
    fun progress(goal: Goal, balanceMinor: Long, today: LocalDate): GoalProgress {
        val saved = balanceMinor.coerceAtLeast(0)
        val left = (goal.targetMinor - saved).coerceAtLeast(0)
        val fraction = (saved.toDouble() / goal.targetMinor).coerceIn(0.0, 1.0)
        val date = goal.targetDate
        val late = date != null && date < today && left > 0
        val months = date?.takeIf { it >= today }?.let { monthsBetween(YearMonth.from(today), YearMonth.from(it)) + 1 }
        val perMonth = if (months != null && left > 0) (left + months - 1) / months else null
        return GoalProgress(saved, left, fraction, perMonth, months, late)
    }

    private fun monthsBetween(from: YearMonth, to: YearMonth): Int = (to.year - from.year) * 12 + (to.monthValue - from.monthValue)
}
