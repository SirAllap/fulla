// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.analytics

import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.recurring.Coverage
import io.github.sirallap.fulla.core.recurring.Occurrence
import io.github.sirallap.fulla.core.recurring.Scheduler
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.split.Allocator
import io.github.sirallap.fulla.core.text.normalizeName
import io.github.sirallap.fulla.core.trips.Trip
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth
import io.github.sirallap.fulla.core.time.ChronoUnit
import kotlin.math.abs

data class PeriodSummary(val period: YearMonth, val incomeMinor: Long, val expenseMinor: Long) {
    val savingsMinor: Long get() = incomeMinor - expenseMinor

    /** Savings as a fraction of income, or null with no income. */
    val savingsRate: Double? get() = if (incomeMinor > 0) savingsMinor.toDouble() / incomeMinor else null
}

data class CategoryRow(
    val categoryId: String,
    val amountMinor: Long,
    /** This category's part of the period's spending, 0..1. */
    val share: Double,
    /** Average of the previous periods (up to three) that have anything written down, for comparison; 0 with none. */
    val previousAverageMinor: Long,
)

/**
 * One line of a category's breakdown: a subcategory (its id), the category's
 * own rows (its own id), or one value of a field (the value, null for rows
 * that do not say).
 */
data class Slice(val key: String?, val amountMinor: Long)

/** The same thing bought again and again, by its note: how often and how much in all. */
data class Place(val name: String, val count: Int, val totalMinor: Long)

/**
 * Everything the insights screen says about one period. Amounts are
 * spending (expenses less refunds) unless named otherwise.
 */
data class PeriodReport(
    val spentMinor: Long,
    /**
     * The period before, or null when nothing was written down in it: no comparison to make. While the period is
     * still running ([partial]) it is what the period before had spent by the same day, never the whole of it.
     */
    val previousSpentMinor: Long?,
    /** The period is still running: what it is compared with is the same days of the earlier ones. */
    val partial: Boolean,
    val incomeMinor: Long,
    /** Savings over income, null without income. */
    val savingsRate: Double?,
    /** Expenses written down (refunds aside). */
    val count: Int,
    /** Per expense, 0 without any. */
    val averageMinor: Long,
    /** Days of the period gone so far (all of them for a past period). */
    val days: Int,
    /** Spending over [days], 0 when none has gone by. */
    val dailyMinor: Long,
    val fixedMinor: Long,
    val variableMinor: Long,
    /** The largest expenses, largest first. */
    val biggest: List<Transaction>,
    /** Variable spending by weekday, Monday first: when the everyday money goes. */
    val weekdays: List<Long>,
    /** Notes seen at least twice, by total. */
    val places: List<Place>,
    val budgets: Int,
    val budgetsOver: Int,
)

enum class FixedStatus { PAID, PENDING, SKIPPED }

/**
 * One charge of a recurring expense that writes itself: [amountMinor] is what
 * was charged once [PAID], what is expected otherwise. [byHand] is a charge
 * somebody wrote down themselves, which stands for the occurrence (Coverage).
 * [overdue] is a charge whose day has come and which nothing has written
 * yet: the phone writes it the next time it looks, or the person applies it.
 */
data class FixedItem(
    val ruleId: String,
    val name: String,
    val categoryId: String?,
    val amountMinor: Long,
    val date: LocalDate,
    val status: FixedStatus,
    val byHand: Boolean = false,
    val overdue: Boolean = false,
    /** Which payment of a financing this is ([installment] of [installments]); null for a fixed cost that never ends. */
    val installment: Int? = null,
    val installments: Int? = null,
) {
    /**
     * How far along the charge is, for its bar: a pending one fills as its day
     * comes closer, over the length of the period (a charge 11 days away in a
     * 31-day period is a third short of full); a paid one is full, and one whose
     * day has come and nothing wrote is full too. A skipped one is empty.
     */
    fun countdown(today: LocalDate, periodLength: Int): Float = when (status) {
        FixedStatus.PAID -> 1f
        FixedStatus.SKIPPED -> 0f
        FixedStatus.PENDING ->
            if (date <= today || periodLength <= 0) 1f
            else ((periodLength - ChronoUnit.DAYS.between(today, date)).toFloat() / periodLength).coerceIn(0f, 1f)
    }
}

/**
 * How a period is likely to end (Analytics.forecast). [everydayRestMinor]
 * and its bounds (about four in five periods land between them) are null
 * when there is no earlier period to learn from.
 */
data class PeriodForecast(
    /** Days of the period gone, today included, and its expected length. */
    val day: Int,
    val length: Int,
    val incomeMinor: Long,
    /** Income from recurring items that has not been written yet. */
    val expectedIncomeMinor: Long,
    val spentMinor: Long,
    val fixed: List<FixedItem>,
    val everydayLowMinor: Long?,
    val everydayRestMinor: Long?,
    val everydayHighMinor: Long?,
    /** The period is open past its length: the next salary has not been noted, so there is no end to forecast. */
    val waiting: Boolean = false,
    /** What this period has spent on everyday things so far: marked variable, not written by a recurring item, not a trip's. */
    val everydaySoFarMinor: Long = 0,
    /** The everyday estimate comes from this period's own pace: no earlier period had everyday spending to learn from. */
    val ownPace: Boolean = false,
) {
    /** What the period has spent per day on everyday things so far; the number to set beside [perDayMinor]. */
    val everydayPerDayMinor: Long? get() = if (day > 0 && everydaySoFarMinor > 0) everydaySoFarMinor / day else null

    val fixedPaidMinor: Long get() = fixed.filter { it.status == FixedStatus.PAID }.sumOf { it.amountMinor }
    val fixedToComeMinor: Long get() = fixed.filter { it.status == FixedStatus.PENDING }.sumOf { it.amountMinor }
    /** Every fixed cost of the period that is or will be charged: skipped ones are not. */
    val fixedTotalMinor: Long get() = fixedPaidMinor + fixedToComeMinor
    val totalIncomeMinor: Long get() = incomeMinor + expectedIncomeMinor
    val known: Boolean get() = everydayRestMinor != null

    val spentEndMinor: Long? get() = everydayRestMinor?.let { spentMinor + fixedToComeMinor + it }
    val spentEndLowMinor: Long? get() = everydayLowMinor?.let { spentMinor + fixedToComeMinor + it }
    val spentEndHighMinor: Long? get() = everydayHighMinor?.let { spentMinor + fixedToComeMinor + it }

    /** What would be left: null without income, or without an estimate. */
    val keptMinor: Long? get() = spentEndMinor?.takeIf { totalIncomeMinor > 0 }?.let { totalIncomeMinor - it }
    val keptLowMinor: Long? get() = spentEndHighMinor?.takeIf { totalIncomeMinor > 0 }?.let { totalIncomeMinor - it }
    val keptHighMinor: Long? get() = spentEndLowMinor?.takeIf { totalIncomeMinor > 0 }?.let { totalIncomeMinor - it }

    /** What the income leaves once every fixed cost is paid; null without income. */
    val afterFixedMinor: Long? get() = totalIncomeMinor.takeIf { it > 0 }?.let { it - fixedTotalMinor }

    /**
     * What is left to spend: the income, less what was spent so far, less the
     * fixed costs still to be charged. Null without income. (Unlike
     * [afterFixedMinor], it counts what was already spent.)
     */
    val leftToSpendMinor: Long? get() = totalIncomeMinor.takeIf { it > 0 }?.let { it - spentMinor - fixedToComeMinor }

    /** Days left to spend in, today included: what is left covers the rest of today too (a trip's daily figure counts the same way). */
    val daysToGo: Int get() = length - day + 1

    /**
     * What can be spent per day over [daysToGo]: [leftToSpendMinor] spread over
     * the days left, today included; null without income or once the period is over.
     */
    val perDayMinor: Long? get() = if (!waiting && day <= length) leftToSpendMinor?.let { it / daysToGo } else null

    /** Too early in the period for the estimate to be tight: under a quarter of it has gone. */
    val early: Boolean get() = day * 4 < length
}

data class MemberSpending(val memberId: String, val paidMinor: Long, val shareMinor: Long)


data class Trend(val categoryId: String, val currentMinor: Long, val averageMinor: Long) {
    val change: Double get() = if (averageMinor == 0L) 1.0 else (currentMinor - averageMinor).toDouble() / averageMinor
}

/** A charge that looks recurring and is not set up as a recurring item yet. */
data class DetectedRecurring(val note: String, val typicalMinor: Long, val months: Int, val lastDate: LocalDate)

/**
 * The home screen's main figure: income and spending as parts of a field
 * twice the size of the income, so the space between them is the savings,
 * drawn to scale. With spending above income the two overlap by the deficit.
 */
data class Hero(val incomeMinor: Long, val expenseMinor: Long) {
    val savingsMinor: Long get() = incomeMinor - expenseMinor
    // Twice the income, so the empty middle is the savings drawn to scale.
    // Only spending beyond that stretches the field, so it still fits.
    private val scale: Long get() = maxOf(2 * incomeMinor, expenseMinor, 1)
    val incomeFraction: Double get() = incomeMinor.toDouble() / scale
    val expenseFraction: Double get() = expenseMinor.toDouble() / scale
    /** Negative when spending exceeds income: the overlap. */
    val gapFraction: Double get() = 1.0 - incomeFraction - expenseFraction
}

/**
 * Figures computed from the phone's own rows. Deleted rows never count, and
 * transfers and settlements are money moving between the household's own
 * pockets and people, so they are not income or spending (except in account
 * balances, where that is exactly what they are).
 */
class Analytics(private val config: Config, private val rule: PeriodRule) {

    private fun periodOf(t: Transaction): YearMonth = rule.periodOf(t.date, t.kind, t.recurrence)

    private fun counted(txs: Iterable<Transaction>, period: YearMonth) =
        txs.filter { it.isActive && it.kind.countsInTotals && periodOf(it) == period }

    /** Spending contribution: expenses count positive, refunds negative. */
    private fun spend(t: Transaction): Long = when (t.kind) {
        TransactionKind.EXPENSE -> t.amountMinor
        TransactionKind.REFUND -> -t.amountMinor
        else -> 0
    }

    /**
     * Days of [period] gone as of [today], today included, while it is still running; null before it starts and once it
     * is over (then there is nothing partial to compare).
     */
    private fun daysGone(period: YearMonth, today: LocalDate): Int? {
        val range = rule.daysOf(period)
        if (today < range.start || today > range.endInclusive) return null
        return (ChronoUnit.DAYS.between(range.start, today) + 1).toInt()
    }

    /**
     * The up to three periods before [period] in which anything was written down: what "usual" is learnt from. A
     * period with nothing in it is a period the app was not used, not a period of zero spending, so it is left out
     * instead of pulling every average down.
     */
    private fun usualPeriods(list: List<Transaction>, period: YearMonth): List<YearMonth> =
        (1..3).map { period.minusMonths(it.toLong()) }.filter { p -> counted(list, p).isNotEmpty() }

    private fun categoryKey(t: Transaction, rollUp: Boolean): String? {
        val c = config.category(t.categoryId) ?: return t.categoryId
        return if (rollUp && c.parentId != null) c.parentId else c.id
    }

    fun summary(txs: Iterable<Transaction>, period: YearMonth): PeriodSummary {
        val rows = counted(txs, period)
        return PeriodSummary(
            period,
            incomeMinor = rows.filter { it.kind == TransactionKind.INCOME }.sumOf { it.amountMinor },
            expenseMinor = rows.sumOf { spend(it) },
        )
    }

    fun hero(txs: Iterable<Transaction>, period: YearMonth): Hero =
        summary(txs, period).let { Hero(it.incomeMinor, it.expenseMinor) }

    fun series(txs: Iterable<Transaction>, lastPeriod: YearMonth, count: Int = 12): List<PeriodSummary> {
        val list = txs.toList()
        return (count - 1 downTo 0).map { summary(list, lastPeriod.minusMonths(it.toLong())) }
    }

    /** Spending per category; with [rollUp], subcategories count in their parent. */
    /**
     * The spending in [categoryId] and its subcategories, split by
     * subcategory; rows in the category itself come under its own id. Empty
     * when the category has no subcategories with spending: the overview
     * then lists the rows straight away.
     */
    fun bySubcategory(txs: Iterable<Transaction>, period: YearMonth, categoryId: String): List<Slice> {
        val rows = spending(txs, period, categoryId, withSubcategories = true)
        if (rows.none { it.categoryId != categoryId }) return emptyList()
        return rows.groupBy { it.categoryId }.map { (id, v) -> Slice(id, v.sumOf { spend(it) }) }
            .filter { it.amountMinor != 0L }.sortedByDescending { it.amountMinor }
    }

    /** The spending in exactly [categoryId], split by the value of the field [fieldKey]; null for rows without one. */
    fun byFieldValue(txs: Iterable<Transaction>, period: YearMonth, categoryId: String, fieldKey: String): List<Slice> =
        spending(txs, period, categoryId, withSubcategories = false).groupBy { it.extras[fieldKey]?.toString() }
            .map { (value, v) -> Slice(value, v.sumOf { spend(it) }) }
            .filter { it.amountMinor != 0L }.sortedWith(compareBy<Slice> { it.key == null }.thenByDescending { it.amountMinor })

    /**
     * The expenses and refunds of [period] in [categoryId] (and its
     * subcategories when [withSubcategories]), newest first; only those whose
     * [field] has the value given, when one is ([field] = key to value, null
     * value for rows without one).
     */
    fun spending(
        txs: Iterable<Transaction>,
        period: YearMonth,
        categoryId: String,
        withSubcategories: Boolean,
        field: Pair<String, String?>? = null,
    ): List<Transaction> = counted(txs, period).filter { t ->
        t.kind != TransactionKind.INCOME &&
            (t.categoryId == categoryId || (withSubcategories && config.category(t.categoryId)?.parentId == categoryId)) &&
            (field == null || t.extras[field.first]?.toString() == field.second)
    }.sortedByDescending { it.date }

    fun byCategory(txs: Iterable<Transaction>, period: YearMonth, rollUp: Boolean = true): List<CategoryRow> {
        val list = txs.toList()
        fun totals(p: YearMonth): Map<String, Long> = counted(list, p)
            .filter { it.kind != TransactionKind.INCOME }
            .groupBy { categoryKey(it, rollUp) ?: "" }
            .mapValues { (_, v) -> v.sumOf { spend(it) } }
        val now = totals(period)
        // "Usual" is over the earlier periods that have anything in them: with one month of history it is that month, not a third of it.
        val previous = usualPeriods(list, period).map { totals(it) }
        val total = now.values.filter { it > 0 }.sum()
        return now.filter { it.value != 0L }.map { (id, amount) ->
            CategoryRow(
                categoryId = id,
                amountMinor = amount,
                share = if (total > 0) amount.toDouble() / total else 0.0,
                previousAverageMinor = if (previous.isEmpty()) 0 else previous.sumOf { it[id] ?: 0 } / previous.size,
            )
        }.sortedByDescending { it.amountMinor }
    }

    /**
     * Spending per category as [byCategory], but leaving out rows that belong
     * to a trip whose `inCategoryBudgets` is off: the trip already has its
     * own jar, and counting it again would turn a category red every time
     * someone travels. Rows on a trip that counts stay in, exactly like any
     * other row; the "Where it went" list, unlike this one, never filters.
     */
    fun budgetSpend(txs: Iterable<Transaction>, period: YearMonth, trips: List<Trip>, rollUp: Boolean = true): List<CategoryRow> {
        val excluded = trips.filter { !it.inCategoryBudgets }.map { it.id }.toSet()
        val filtered = txs.filter { it.tripId == null || it.tripId !in excluded }
        return byCategory(filtered, period, rollUp)
    }

    /** Per member: what they paid, and what their share of spending was. */
    fun byMember(txs: Iterable<Transaction>, period: YearMonth): List<MemberSpending> {
        val paid = HashMap<String, Long>()
        val share = HashMap<String, Long>()
        for (t in counted(txs, period)) {
            if (t.kind == TransactionKind.INCOME) continue
            val sign = if (t.kind == TransactionKind.REFUND) -1 else 1
            t.paidByMemberId?.let { paid[it] = (paid[it] ?: 0) + sign * t.amountMinor }
            val parts = t.split?.let { Allocator.allocate(t.amountMinor, it) }
                ?: t.paidByMemberId?.let { mapOf(it to t.amountMinor) } ?: emptyMap()
            parts.forEach { (id, part) -> share[id] = (share[id] ?: 0) + sign * part }
        }
        return config.members.map { MemberSpending(it.id, paid[it.id] ?: 0, share[it.id] ?: 0) }
    }

    /** Balance of each account on [asOf]: opening balance plus everything through it, transfers included, settlements not. */
    fun accountBalances(txs: Iterable<Transaction>, accounts: List<Account>, asOf: LocalDate): Map<String, Long> {
        val balance = accounts.associate { it.id to it.openingBalanceMinor }.toMutableMap()
        val openingDates = accounts.associate { it.id to it.openingBalanceDate }
        fun add(id: String?, amount: Long, date: LocalDate) {
            if (id == null || id !in balance) return
            val openingDate = openingDates[id]
            if (openingDate != null && date < openingDate) return
            balance[id] = balance.getValue(id) + amount
        }
        for (t in txs) {
            if (!t.isActive || t.date > asOf) continue
            when (t.kind) {
                TransactionKind.INCOME, TransactionKind.REFUND -> add(t.accountId, t.amountMinor, t.date)
                TransactionKind.EXPENSE -> add(t.accountId, -t.amountMinor, t.date)
                TransactionKind.TRANSFER -> { add(t.accountId, -t.amountMinor, t.date); add(t.toAccountId, t.amountMinor, t.date) }
                // Money between two members of the household: it moves between
                // people, and the household's accounts do not see it.
                TransactionKind.SETTLEMENT -> Unit
            }
        }
        return balance
    }

    /**
     * How the period is likely to end, as of [today]; null when it has not
     * started, or when it is open past its expected length (waiting for the
     * salary that closes it), where there is no end to forecast to.
     *
     * Three parts, each the most exact it can be:
     * - what was spent so far, which is a fact;
     * - the fixed costs still to be charged, read off the recurring items
     *   that write themselves (date and amount are known, so nothing is
     *   estimated), and the income due from recurring income;
     * - the everyday spending still to come: what the previous three periods
     *   spent per day from this day on (their median, so one odd period
     *   hardly moves it), adjusted by how this one is going against them,
     *   more so as the days pass. Rows written by a recurring item, rows of a
     *   trip and rows that look like a recurring item are not everyday
     *   spending: the first are counted exactly, the others are one-offs.
     *
     * An open period past its length (the next salary not noted yet) has no
     * end to forecast: the fixed costs are still listed, [PeriodForecast.waiting]
     * is set, and nothing about the end is guessed.
     *
     * With no earlier period that had everyday spending to learn from, the
     * estimate is this period's own pace once a week of it has passed
     * ([PeriodForecast.ownPace], a wider range); before that, or with nothing
     * spent, the everyday part is left out (null) rather than guessed: the
     * fixed part is still exact.
     *
     * [deleted] are the ids of rows written off, so a fixed cost skipped for
     * a month is not waited for.
     */
    fun forecast(txs: Iterable<Transaction>, period: YearMonth, today: LocalDate, deleted: Set<String> = emptySet()): PeriodForecast? {
        val range = rule.daysOf(period)
        if (today < range.start) return null
        val waiting = rule.isOpen(period) && today > range.endInclusive
        val list = txs.toList()
        val length = (ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1).toInt()
        val day = (ChronoUnit.DAYS.between(range.start, minOf(today, range.endInclusive)) + 1).toInt()
        val byId = list.associateBy { it.id }

        val rules = config.recurringRules.filter { it.active && !it.archived && it.autoCreate }
        val slots = rules.flatMap { r -> Scheduler.occurrences(r, range.start, if (waiting) today else range.endInclusive).map { Occurrence(r, it) } }
        // What nothing has written yet may still be on the books: somebody wrote it down by hand.
        val byHand = Coverage.byHand(slots.filter { byId[it.id] == null && it.id !in deleted }, list)
        val fixed = mutableListOf<FixedItem>()
        var expectedIncome = 0L
        for (o in slots) {
            val r = o.rule
            val written = byId[o.id]
            val covering = byHand[o]
            if (r.template.kind == TransactionKind.INCOME) {
                if (written == null && o.id !in deleted && covering == null) expectedIncome += r.template.amountMinor
                continue
            }
            if (r.template.kind != TransactionKind.EXPENSE) continue
            val nth = Scheduler.installment(r, o.date)
            val item = when {
                written != null && written.isActive -> FixedItem(r.id, r.name, r.template.categoryId, written.amountMinor, o.date, FixedStatus.PAID)
                o.id in deleted || written != null -> FixedItem(r.id, r.name, r.template.categoryId, r.template.amountMinor, o.date, FixedStatus.SKIPPED)
                covering != null -> FixedItem(r.id, r.name, r.template.categoryId, covering.amountMinor, o.date, FixedStatus.PAID, byHand = true)
                else -> FixedItem(r.id, r.name, r.template.categoryId, r.template.amountMinor, o.date, FixedStatus.PENDING, overdue = o.date <= today)
            }
            fixed += item.copy(installment = nth?.first, installments = nth?.second)
        }

        // Everyday spending: not written by a recurring item, not a trip's, not like a recurring item.
        fun everyday(t: Transaction) = t.recurringRuleId == null && t.tripId == null && rules.none { r ->
            r.template.kind == TransactionKind.EXPENSE && r.template.categoryId != null && t.categoryId == r.template.categoryId &&
                abs(t.amountMinor - r.template.amountMinor) * 100 <= r.template.amountMinor * LIKE_RULE_PERCENT
        }
        val now = counted(list, period)
        val spent = now.filter { it.kind != TransactionKind.INCOME }.sumOf { spend(it) }
        val income = now.filter { it.kind == TransactionKind.INCOME }.sumOf { it.amountMinor }
        val everydaySoFar = now.filter { it.kind != TransactionKind.INCOME && everyday(it) }.sumOf { spend(it) }
        // What the person calls day-to-day spending: marked variable, on top of what everyday() leaves out.
        val variableRows = now.filter { it.kind != TransactionKind.INCOME && everyday(it) && it.recurrence == Recurrence.VARIABLE }
        val variableSoFar = variableRows.sumOf { spend(it) }

        // What earlier periods spent per day from this day on, and up to it.
        val earlier = (1..3).map { period.minusMonths(it.toLong()) }.mapNotNull { p ->
            val r = rule.daysOf(p)
            val rows = counted(list, p).filter { it.kind != TransactionKind.INCOME }
            val everydayRows = rows.filter { everyday(it) }
            // A period of fixed costs only has nothing to say about everyday spending: it is not a pace of zero.
            if (everydayRows.isEmpty()) return@mapNotNull null
            val len = (ChronoUnit.DAYS.between(r.start, r.endInclusive) + 1).toInt()
            val cut = r.start.plusDays(day.toLong() - 1)
            val before = everydayRows.filter { it.date <= cut }.sumOf { spend(it) }
            val after = everydayRows.filter { it.date > cut }.sumOf { spend(it) }
            // Per day of what is left of that period; the whole period's pace when too little is left to tell.
            val rate = if (len - day >= MIN_DAYS_LEFT) after.toDouble() / (len - day) else (before + after).toDouble() / len
            rate to before
        }
        var lowRest: Long? = null
        var midRest: Long? = null
        var highRest: Long? = null
        var ownPace = false
        if (!waiting && earlier.isNotEmpty() && day <= length) {
            val rate = median(earlier.map { it.first })
            val usualSoFar = median(earlier.map { it.second.toDouble() })
            val ratio = if (usualSoFar > 0) (everydaySoFar / usualSoFar).coerceIn(0.5, 2.0) else 1.0
            val weight = day.toDouble() / (day + SHRINK_DAYS)
            val rest = (rate * (length - day) * (1 + weight * (ratio - 1))).coerceAtLeast(0.0)
            midRest = rest.toLong()
            lowRest = (rest * LOW_FACTOR).toLong()
            highRest = (rest * HIGH_FACTOR).toLong()
        } else if (!waiting && day <= length && day >= MIN_OWN_DAYS) {
            // Nothing earlier to learn from: this period's own pace, with a wider range, because it is only a week or two.
            // Only what is marked variable counts (a rent written by hand is not a pace), and one big purchase is held
            // to three times the usual row, so it does not turn into a daily habit.
            val bought = variableRows.filter { it.kind == TransactionKind.EXPENSE }.map { it.amountMinor.toDouble() }
            if (bought.size >= MIN_OWN_ROWS) {
                val cap = median(bought) * OWN_CAP_TIMES
                val total = variableRows.sumOf { if (it.kind == TransactionKind.EXPENSE) minOf(it.amountMinor.toDouble(), cap) else -it.amountMinor.toDouble() }
                val rest = (total / day * (length - day)).coerceAtLeast(0.0)
                ownPace = true
                midRest = rest.toLong()
                lowRest = (rest * OWN_LOW_FACTOR).toLong()
                highRest = (rest * OWN_HIGH_FACTOR).toLong()
            }
        }
        return PeriodForecast(day, length, income, expectedIncome, spent, fixed.sortedBy { it.date }, lowRest, midRest, highRest, waiting, variableSoFar, ownPace)
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * Categories whose spending moved noticeably against the average of the
     * earlier periods (up to three, those with anything written down): by at
     * least 20 % and at least [minimumMinor]. With [today] inside the period
     * it is still running, so every earlier period is cut at the same day:
     * eleven days of this month are held against eleven days of the others,
     * never against whole months.
     */
    fun trends(txs: Iterable<Transaction>, period: YearMonth, minimumMinor: Long, today: LocalDate? = null): List<Trend> {
        val list = txs.toList()
        val gone = today?.let { daysGone(period, it) }
        val usual = usualPeriods(list, period)
        if (usual.isEmpty()) return emptyList()
        fun totals(p: YearMonth): Map<String, Long> {
            val cut = gone?.let { rule.daysOf(p).start.plusDays(it - 1L) }
            return counted(list, p).filter { it.kind != TransactionKind.INCOME && (cut == null || it.date <= cut) }
                .groupBy { categoryKey(it, true) ?: "" }.mapValues { (_, v) -> v.sumOf { spend(it) } }
        }
        val now = totals(period)
        val before = usual.map { totals(it) }
        return now.filter { it.value != 0L }.mapNotNull { (id, amount) ->
            val t = Trend(id, amount, before.sumOf { it[id] ?: 0 } / before.size)
            // No earlier spending is no "usual" to compare with: "100 % more than 0" says nothing.
            if (t.averageMinor > 0 && abs(t.currentMinor - t.averageMinor) >= minimumMinor && abs(t.change) >= 0.2) t else null
        }.sortedByDescending { abs(it.currentMinor - it.averageMinor) }
    }

    /**
     * Where the period's spending went, for a chart: the [top] categories by
     * spending (subcategories rolled up), then everything else together under
     * a null key. Only positive totals: a category that was all refunds is
     * not drawn below zero.
     */
    fun topCategories(txs: Iterable<Transaction>, period: YearMonth, top: Int = 5): List<Slice> {
        val rows = byCategory(txs, period).filter { it.amountMinor > 0 }.sortedByDescending { it.amountMinor }
        val shown = rows.take(top).map { Slice(it.categoryId, it.amountMinor) }
        val rest = rows.drop(top).sumOf { it.amountMinor }
        return if (rest > 0) shown + Slice(null, rest) else shown
    }

    /** What the insights screen shows for [period], as of [today]. */
    fun report(txs: Iterable<Transaction>, period: YearMonth, today: LocalDate, trips: List<Trip> = emptyList(), top: Int = 5): PeriodReport {
        val list = txs.toList()
        val rows = counted(list, period)
        val out = rows.filter { it.kind == TransactionKind.EXPENSE || it.kind == TransactionKind.REFUND }
        val expenses = out.filter { it.kind == TransactionKind.EXPENSE }
        val spent = out.sumOf { spend(it) }
        val income = rows.filter { it.kind == TransactionKind.INCOME }.sumOf { it.amountMinor }
        val previous = counted(list, period.minusMonths(1)).filter { it.kind != TransactionKind.INCOME }
        val gone = daysGone(period, today)
        // While the period runs, the one before is held to the same days: ten days against a whole month say nothing.
        val previousCut = gone?.let { rule.daysOf(period.minusMonths(1)).start.plusDays(it - 1L) }
        val range = rule.daysOf(period)
        val days = when {
            today < range.start -> 0
            today > range.endInclusive -> (ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1).toInt()
            else -> (ChronoUnit.DAYS.between(range.start, today) + 1).toInt()
        }
        val weekdays = LongArray(7)
        for (t in out) if (t.recurrence == Recurrence.VARIABLE) weekdays[t.date.dayOfWeek.value - 1] += spend(t)
        val places = expenses.filter { it.note.isNotBlank() }.groupBy { it.note.normalizeName() }
            .filter { (_, v) -> v.size >= 2 }
            .map { (_, v) -> Place(v.maxBy { it.date }.note.trim(), v.size, v.sumOf { it.amountMinor }) }
            .sortedByDescending { it.totalMinor }.take(top)
        val limits = Budgets.forPeriod(config, period)
        val used = budgetSpend(list, period, trips).associate { it.categoryId to it.amountMinor }
        return PeriodReport(
            spentMinor = spent,
            previousSpentMinor = if (previous.isEmpty()) null else previous.filter { previousCut == null || it.date <= previousCut }.sumOf { spend(it) },
            partial = gone != null,
            incomeMinor = income,
            savingsRate = if (income > 0) (income - spent).toDouble() / income else null,
            count = expenses.size,
            averageMinor = if (expenses.isEmpty()) 0 else expenses.sumOf { it.amountMinor } / expenses.size,
            days = days,
            dailyMinor = if (days > 0) spent / days else 0,
            fixedMinor = out.filter { it.recurrence == Recurrence.FIXED }.sumOf { spend(it) },
            variableMinor = out.filter { it.recurrence == Recurrence.VARIABLE }.sumOf { spend(it) },
            biggest = expenses.sortedByDescending { it.amountMinor }.take(top),
            weekdays = weekdays.toList(),
            places = places,
            budgets = limits.size,
            budgetsOver = limits.count { (category, limit) -> (used[category] ?: 0) > limit },
        )
    }

    /** Days in the period, up to [today], with no variable spending at all. */
    fun noSpendDays(txs: Iterable<Transaction>, period: YearMonth, today: LocalDate): Int {
        val days = rule.daysOf(period)
        val last = minOf(today, days.endInclusive)
        if (last < days.start) return 0
        val spent = counted(txs, period)
            .filter { it.kind == TransactionKind.EXPENSE && it.recurrence == Recurrence.VARIABLE }
            .map { it.date }.toSet()
        return generateSequence(days.start) { it.plusDays(1) }.takeWhile { it <= last }.count { it !in spent }
    }

    /**
     * Expenses that come back month after month for about the same amount
     * (within 10 % of their median) under the same note, in at least three of
     * the last [lookbackMonths] months, and are not already recurring items.
     */
    fun detectedRecurring(txs: Iterable<Transaction>, today: LocalDate, lookbackMonths: Int = 6): List<DetectedRecurring> {
        val since = today.minusMonths(lookbackMonths.toLong())
        return txs.filter {
            it.isActive && it.kind == TransactionKind.EXPENSE && it.recurringRuleId == null &&
                it.date > since && it.note.isNotBlank()
        }.groupBy { it.note.normalizeName() }.mapNotNull { (_, group) ->
            val amounts = group.map { it.amountMinor }.sorted()
            val median = amounts[amounts.size / 2]
            val close = group.filter { abs(it.amountMinor - median) * 10 <= median }
            val months = close.map { YearMonth.from(it.date) }.toSet()
            if (months.size >= 3) {
                DetectedRecurring(close.maxBy { it.date }.note, median, months.size, close.maxOf { it.date })
            } else null
        }.sortedByDescending { it.typicalMinor }
    }

    /**
     * Totals grouped by any dimension: "category", "paid_by", "account",
     * "recurrence", "tag", or the key of a custom field. The measure is the
     * amount, or the key of a custom number or money field.
     */
    fun groupBy(txs: Iterable<Transaction>, period: YearMonth, dimension: String, measure: String = "amount"): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (t in counted(txs, period).filter { it.kind != TransactionKind.INCOME }) {
            val value: Long = when (measure) {
                "amount" -> spend(t)
                else -> when (val v = t.extras[measure]) {
                    is Number -> v.toLong()
                    is String -> v.toDoubleOrNull()?.toLong() ?: 0
                    else -> 0
                }
            }
            val keys: List<String> = when (dimension) {
                "category" -> listOf(t.categoryId ?: "")
                "paid_by" -> listOf(t.paidByMemberId ?: "")
                "account" -> listOf(t.accountId ?: "")
                "recurrence" -> listOf(t.recurrence.key)
                "tag" -> io.github.sirallap.fulla.core.rules.PeriodAnchors.visibleTags(t.tags).ifEmpty { listOf("") }
                else -> when (val v = t.extras[dimension]) {
                    is List<*> -> v.map { it.toString() }.ifEmpty { listOf("") }
                    null -> listOf("")
                    else -> listOf(v.toString())
                }
            }
            for (k in keys) out[k] = (out[k] ?: 0) + value
        }
        return out
    }
}

private const val LIKE_RULE_PERCENT = 5L
private const val MIN_DAYS_LEFT = 3
private const val SHRINK_DAYS = 10
/** The everyday spending still to come lands between these times the estimate in about four periods of five. */
private const val LOW_FACTOR = 0.6
private const val HIGH_FACTOR = 1.6
private const val MIN_OWN_DAYS = 7
private const val OWN_LOW_FACTOR = 0.5
private const val OWN_HIGH_FACTOR = 1.8
private const val MIN_OWN_ROWS = 5
private const val OWN_CAP_TIMES = 3.0
