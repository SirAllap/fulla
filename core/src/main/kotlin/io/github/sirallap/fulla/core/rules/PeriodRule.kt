// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.rules

import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * The period (YYYY-MM) a transaction counts in.
 *
 * By default the calendar month. A household can choose instead:
 *
 * - [periodStartDay] S from 2 to 28: a period runs from day S of one month to
 *   day S-1 of the next and is named after the month it ends in. With S = 20,
 *   20 March to 19 April is "April".
 * - [incomeShiftDay] D, only with calendar months: fixed income dated on or
 *   after day D counts in the next month, for a salary paid at the end of a
 *   month to fund the next one. Only income moves, and only fixed income.
 * - [anchors]: the dates the salary actually came in (see [PeriodAnchors]).
 *   Each one starts a period that runs until the day before the next, so a
 *   salary paid early or late moves the boundary with it, and the last
 *   period stays open until the next salary is written down. A period is
 *   named after the month the salary funds: its own month when it came
 *   before the [ANCHOR_NEXT_MONTH_FROM]th, the next one from then on. Only
 *   the first salary of each name starts a period, so a bonus in the same
 *   stretch does not split it. Days before the first salary follow the two
 *   rules above, capped to end just before it.
 *
 * The same rule is fulla.period_of and fulla.anchored_period_of in the
 * database. Both pass testdata/vectors/period.json and period_anchors.json.
 */
data class PeriodRule(
    val periodStartDay: Int = 1,
    val incomeShiftDay: Int? = null,
    val anchors: List<LocalDate> = emptyList(),
) {

    init {
        require(periodStartDay in 1..28) { "periodStartDay must be 1..28" }
        require(incomeShiftDay == null || incomeShiftDay in 1..31) { "incomeShiftDay must be 1..31" }
        require(periodStartDay == 1 || incomeShiftDay == null) { "A mid-month period and shifted income exclude each other" }
    }

    /** The salaries that start a period, oldest first, one per period name. */
    private val starts: List<Pair<LocalDate, YearMonth>> =
        anchors.map { it to nameOf(it) }.groupBy { it.second }.map { (_, same) -> same.minBy { it.first } }.sortedBy { it.first }

    /** Whether salaries decide the periods; false until the first one is written down. */
    val anchored: Boolean get() = starts.isNotEmpty()

    /** The day the latest period started on, if salaries decide the periods. */
    val lastStart: LocalDate? get() = starts.lastOrNull()?.first

    /** The period still waiting for its next salary: its end is not known yet. */
    fun isOpen(period: YearMonth): Boolean = starts.lastOrNull()?.second == period

    /**
     * Days since the latest salary, once more than [SALARY_OVERDUE_DAYS] have
     * gone by: the open period may be waiting for one nobody wrote down.
     */
    fun daysWaitingForSalary(today: LocalDate): Int? {
        val last = lastStart ?: return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(last, today).toInt()
        return days.takeIf { it > SALARY_OVERDUE_DAYS }
    }

    fun periodOf(date: LocalDate, kind: TransactionKind, recurrence: Recurrence): YearMonth {
        if (starts.isEmpty()) return fixedPeriodOf(date, kind, recurrence)
        starts.lastOrNull { it.first <= date }?.let { return it.second }
        return minOf(fixedPeriodOf(date, kind, recurrence), starts.first().second.minusMonths(1))
    }

    fun label(date: LocalDate, kind: TransactionKind, recurrence: Recurrence): String =
        periodOf(date, kind, recurrence).toString()

    /**
     * The calendar days a period covers, first and last inclusive. The period
     * still waiting for its next salary ends, for now, a month after it began.
     */
    fun daysOf(period: YearMonth): ClosedRange<LocalDate> {
        if (starts.isEmpty()) return fixedDaysOf(period)
        val first = starts.first()
        if (period < first.second) {
            val fixed = fixedDaysOf(period)
            val end = if (period == first.second.minusMonths(1)) first.first.minusDays(1) else minOf(fixed.endInclusive, first.first.minusDays(1))
            return fixed.start..maxOf(end, fixed.start)
        }
        val at = starts.indexOfLast { it.second <= period }
        val (start, name) = starts[at]
        if (name == period) {
            val end = starts.getOrNull(at + 1)?.first?.minusDays(1) ?: start.plusMonths(1).minusDays(1)
            return start..end
        }
        // A month no salary has started (yet): guessed from the last one that did.
        val guess = start.plusMonths(name.until(period, java.time.temporal.ChronoUnit.MONTHS))
        return guess..guess.plusMonths(1).minusDays(1)
    }

    private fun fixedPeriodOf(date: LocalDate, kind: TransactionKind, recurrence: Recurrence): YearMonth {
        val month = YearMonth.from(date)
        val shifts = when {
            periodStartDay > 1 -> date.dayOfMonth >= periodStartDay
            incomeShiftDay != null -> kind == TransactionKind.INCOME &&
                recurrence == Recurrence.FIXED && date.dayOfMonth >= incomeShiftDay
            else -> false
        }
        return if (shifts) month.plusMonths(1) else month
    }

    private fun fixedDaysOf(period: YearMonth): ClosedRange<LocalDate> =
        if (periodStartDay == 1) {
            period.atDay(1)..period.atEndOfMonth()
        } else {
            period.minusMonths(1).atDay(periodStartDay)..period.atDay(periodStartDay - 1)
        }

    companion object {
        /** A salary from this day of the month on funds the next month. */
        const val ANCHOR_NEXT_MONTH_FROM = 15

        /** After this many days without a salary, the overview asks whether it came in. */
        const val SALARY_OVERDUE_DAYS = 35

        /** The period a salary paid on [date] starts. */
        fun nameOf(date: LocalDate): YearMonth =
            YearMonth.from(date).let { if (date.dayOfMonth >= ANCHOR_NEXT_MONTH_FROM) it.plusMonths(1) else it }

        /** The household's rule, with the salaries marked among [transactions]. */
        fun of(config: Config, transactions: Iterable<Transaction>): PeriodRule =
            PeriodRule(config.household.periodStartDay, config.household.incomeShiftDay, PeriodAnchors.from(transactions))
    }
}

/**
 * Which rows start a period: active income somebody marked "starts the
 * month", with the reserved tag [TAG] (hidden from every list of tags). The
 * mark is on the row itself, so a second salary in the same category never
 * moves the month unless it is marked too. A row a recurring item wrote on
 * its own does not count until somebody has saved it, since the day it was
 * scheduled for is exactly the guess this replaces. The database applies the
 * same filter (fulla.period_summary).
 */
object PeriodAnchors {

    /** Kept in `tags` so it syncs like any tag; never shown as one. */
    const val TAG = "fulla:starts-period"

    fun marked(t: Transaction): Boolean = TAG in t.tags

    fun counts(t: Transaction): Boolean =
        t.isActive && t.kind == TransactionKind.INCOME && marked(t) && confirmed(t)

    /** Written or saved by a person, not only generated by a recurring item. */
    fun confirmed(t: Transaction): Boolean =
        t.recurringRuleId == null || runCatching { Instant.parse(t.clientUpdatedAt) != Instant.parse(t.createdAt) }.getOrDefault(true)

    fun from(transactions: Iterable<Transaction>): List<LocalDate> = transactions.filter(::counts).map { it.date }

    /** [t] with the mark set or cleared, its other tags untouched. */
    fun mark(t: Transaction, starts: Boolean): Transaction =
        t.copy(tags = if (starts) (t.tags - TAG) + TAG else t.tags - TAG)

    /** Tags as a person sees them. */
    fun visibleTags(tags: List<String>): List<String> = tags - TAG

    /** The latest salary that starts a period, the model for the next one. */
    fun latest(transactions: Iterable<Transaction>): Transaction? = transactions.filter(::counts).maxByOrNull { it.date }

    /**
     * Whether an income looks like the salary that started the last period:
     * same category and same account. The entry screen proposes the mark for
     * it; the person confirms or turns it off.
     */
    fun looksLikeSalary(t: Transaction, latest: Transaction?): Boolean =
        latest != null && t.kind == TransactionKind.INCOME && t.categoryId != null &&
            t.categoryId == latest.categoryId && t.accountId == latest.accountId

    /**
     * An income that arrived after the last salary, looks like it and is not
     * marked: most likely this month's salary, imported or written down
     * without the mark. The overview asks about the oldest one, unless the
     * person already said it is not ([dismissed]).
     */
    fun unmarkedSalary(transactions: Iterable<Transaction>, dismissed: Set<String> = emptySet()): Transaction? {
        val list = transactions.toList()
        val latest = latest(list) ?: return null
        return list.filter {
            it.isActive && !marked(it) && it.id !in dismissed && it.date > latest.date.plusDays(MIN_DAYS_BETWEEN) &&
                looksLikeSalary(it, latest)
        }.minByOrNull { it.date }
    }

    /** Two salaries closer than this are not two months: a bonus, a refund of payroll, a correction. */
    const val MIN_DAYS_BETWEEN = 14L
}
