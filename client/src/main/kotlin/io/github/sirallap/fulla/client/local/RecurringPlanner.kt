// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.local

import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.recurring.Coverage
import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.Occurrence
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import io.github.sirallap.fulla.core.recurring.Scheduler
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.split.SharedPot
import java.time.LocalDate

/**
 * The occurrences of recurring items that are due and not yet written.
 *
 * Each occurrence's id is derived from its rule and date, so two phones that
 * both generate it write the same row and the sync merges them. An occurrence
 * somebody deleted keeps its id as a tombstone, is in [existingIds], and is
 * never generated again. In one shared pot an occurrence is the payer's
 * alone, whatever split the rule's template carries (SharedPot.forNew).
 *
 * Three things keep it from writing what the person does not expect:
 * - an occurrence somebody already wrote down by hand is not written again
 *   ([Coverage]);
 * - a rule that has never written a row starts with the current period, not
 *   with the day it says it starts on: a rule made in the middle of a month
 *   (or one an older version never ran) does not reach back into periods
 *   nobody is looking at. From then on it catches up from the first thing it
 *   wrote, so what was left out the first time is not written the second;
 * - nothing is ever written for a day that has not come.
 */
object RecurringPlanner {

    /** How far back a rule that was just created, or a phone that was off, catches up. */
    const val LOOKBACK_DAYS = 62L

    /**
     * What is due, given what the phone holds: the household's [config], the
     * ids of every row it holds ([existingIds], deleted ones too) and, when
     * the caller has them, the rows themselves ([rows]) and the day the
     * current period began ([periodStart]). Without those two, only the ids
     * decide, which is what a caller that does not read rows gets.
     */
    fun due(
        config: Config,
        existingIds: Set<String>,
        today: LocalDate,
        rows: List<Transaction> = emptyList(),
        periodStart: LocalDate? = null,
    ): List<Transaction> {
        val slots = unwritten(config, existingIds, today, periodStart)
        val byHand = Coverage.byHand(slots, rows)
        return slots.filter { it !in byHand }.map { occurrence(it.rule, it.date, config) }
    }

    /**
     * What the first look left out: the occurrences of the last [LOOKBACK_DAYS]
     * days that nothing wrote and nobody wrote by hand, because they fell in an
     * earlier period than the one a rule that had never run started with. The
     * person decides: write them, or let them go (the screen's "Not written").
     */
    fun leftOut(config: Config, existingIds: Set<String>, rows: List<Transaction>, today: LocalDate, periodStart: LocalDate): List<Occurrence> {
        val kept = unwritten(config, existingIds, today, periodStart).map { it.id }.toSet()
        val all = unwritten(config, existingIds, today, null)
        val byHand = Coverage.byHand(all, rows)
        return all.filter { it.id !in kept && it !in byHand }.sortedWith(compareBy({ it.date }, { it.rule.name }))
    }

    private fun unwritten(config: Config, existingIds: Set<String>, today: LocalDate, periodStart: LocalDate?): List<Occurrence> =
        config.recurringRules.filter { it.active && it.autoCreate }.flatMap { rule ->
            Scheduler.occurrences(rule, firstDay(rule, existingIds, today, periodStart), today)
                .map { Occurrence(rule, it) }
                .filter { it.id !in existingIds }
        }

    /**
     * What to write for a household, reading its rows only when something may
     * be due: most calls (the app looks every time it opens) find nothing.
     * [existingIds] are all the ids held; [rows] reads every row, deleted ones
     * included, and is called at most once.
     */
    suspend fun plan(config: Config, existingIds: Set<String>, today: LocalDate, rows: suspend () -> List<Transaction>): List<Transaction> {
        if (due(config, existingIds, today).isEmpty()) return emptyList()
        val all = rows()
        return due(config, existingIds, today, all, currentPeriodStart(config, all, today))
    }

    /** The row for one occurrence, as the phone writes it: the same row every phone writes for it. */
    fun occurrence(rule: RecurringRule, date: LocalDate, config: Config): Transaction =
        SharedPot.forNew(rule.template.copy(
            id = DeterministicId.occurrence(rule.id, date),
            date = date,
            status = Status.ACTIVE,
            recurringRuleId = rule.id,
            occurrenceDate = date,
            createdByMemberId = config.meMemberId,
        ), config.household)

    /** The day the period that [today] belongs to began, by the household's own rule. */
    fun currentPeriodStart(config: Config, rows: List<Transaction>, today: LocalDate): LocalDate {
        val rule = PeriodRule.of(config, rows.filter { it.isActive })
        return rule.daysOf(rule.periodOf(today, TransactionKind.EXPENSE, Recurrence.VARIABLE)).start
    }

    /**
     * Where a rule's catch-up begins: [LOOKBACK_DAYS] back, never before the
     * rule's own start and, when the caller knows where the current period
     * began, never before the first day the rule wrote (it has not written
     * yet: the current period). Counting from what it wrote keeps the answer
     * the same on the next look: what was left out the first time stays out.
     */
    private fun firstDay(rule: RecurringRule, existingIds: Set<String>, today: LocalDate, periodStart: LocalDate?): LocalDate {
        val lookback = today.minusDays(LOOKBACK_DAYS)
        if (periodStart == null) return lookback
        val firstWritten = Scheduler.occurrences(rule, rule.startDate, today).firstOrNull { DeterministicId.occurrence(rule.id, it) in existingIds }
        return maxOf(lookback, firstWritten ?: periodStart)
    }

    /**
     * The day a rule begins on, when it is saved.
     *
     * A new rule applies from the start of the current period, so this
     * period's charges that have already come are written, and nothing
     * before them. A rule that is changed keeps its start, unless it is
     * resumed or its schedule changes: then it applies from today on, never
     * back (what it already wrote stays, a pause is not charged for, and a
     * new day does not write the old one again).
     *
     * "Every N months from [cycleMonth]" counts its months from the start, so
     * the start is moved to the first month of the cycle that is not behind.
     */
    fun startFor(
        existing: RecurringRule?,
        schedule: Schedule,
        active: Boolean,
        cycleMonth: Int?,
        periodStart: LocalDate,
        today: LocalDate,
    ): LocalDate {
        val cycle = schedule.frequency == Frequency.MONTHLY && schedule.interval > 1 && cycleMonth != null
        fun onCycle(date: LocalDate) = (date.monthValue - cycleMonth!!).mod(schedule.interval) == 0
        val base = when {
            existing == null -> minOf(periodStart, today)
            (!existing.active && active) || existing.schedule != schedule || (cycle && !onCycle(existing.startDate)) -> maxOf(existing.startDate, today)
            else -> existing.startDate
        }
        if (!cycle || onCycle(base)) return base
        return generateSequence(base.withDayOfMonth(1)) { it.plusMonths(1) }.first { onCycle(it) }
    }
}
