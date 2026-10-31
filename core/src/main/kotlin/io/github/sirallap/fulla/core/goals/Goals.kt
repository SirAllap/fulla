// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.goals

import java.time.LocalDate
import java.time.YearMonth

/**
 * One movement of a goal's own jar: money put in (positive) or taken out (negative), on a day, by someone. Taken back
 * by [deleted], never removed: the server merges a goal's entries by id (0024_goal_jars.sql), so an entry another
 * phone has not heard of yet is never lost.
 */
data class GoalEntry(
    val id: String,
    val date: LocalDate,
    val amountMinor: Long,
    val note: String? = null,
    val memberId: String? = null,
    val deleted: Boolean = false,
)

/**
 * A savings goal: an amount to reach, by a date if there is one. What has been saved is either the goal's own jar
 * (no [accountId]: the sum of its [contributions]) or, for a goal that names an account, that account's balance;
 * either way nothing else is written down for it, so the goal never disagrees with what it is made of.
 */
data class Goal(
    val id: String,
    val name: String,
    val targetMinor: Long,
    val accountId: String? = null,
    val targetDate: LocalDate? = null,
    val archived: Boolean = false,
    val contributions: List<GoalEntry> = emptyList(),
) {
    /** A jar of its own, rather than an account's balance. */
    val ownJar: Boolean get() = accountId == null

    /** The entries that count, newest first. */
    val entries: List<GoalEntry> get() = contributions.filter { !it.deleted }.sortedWith(compareByDescending<GoalEntry> { it.date }.thenByDescending { it.id })

    /** What the jar holds: everything put in, less everything taken out. */
    val jarMinor: Long get() = contributions.filter { !it.deleted }.sumOf { it.amountMinor }
}

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

    /** What [goal] has saved: its own jar, or its account's balance from [balances]. */
    fun saved(goal: Goal, balances: Map<String, Long>): Long =
        goal.accountId?.let { balances[it] ?: 0L } ?: goal.jarMinor

    /**
     * The quarter of the way (25, 50, 75 or 100 %) that putting money in has just crossed, going from [beforeMinor]
     * to [afterMinor] of [targetMinor]; null when none was crossed, or when money was taken out.
     */
    fun milestone(beforeMinor: Long, afterMinor: Long, targetMinor: Long): Int? {
        if (afterMinor <= beforeMinor || targetMinor <= 0) return null
        return listOf(100, 75, 50, 25).firstOrNull { q -> beforeMinor * 100 < targetMinor * q && afterMinor * 100 >= targetMinor * q }
    }

    private fun monthsBetween(from: YearMonth, to: YearMonth): Int = (to.year - from.year) * 12 + (to.monthValue - from.monthValue)
}
