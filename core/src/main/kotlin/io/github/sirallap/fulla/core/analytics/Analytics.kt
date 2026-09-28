// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.analytics

import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.split.Allocator
import io.github.sirallap.fulla.core.text.normalizeName
import io.github.sirallap.fulla.core.trips.Trip
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
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
    /** Average of the three previous periods, for comparison. */
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
    /** The period before, or null when nothing was written down in it: no comparison to make. */
    val previousSpentMinor: Long?,
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

data class MemberSpending(val memberId: String, val paidMinor: Long, val shareMinor: Long)

data class Projection(val spentSoFarMinor: Long, val projectedMinor: Long, val daysElapsed: Int, val daysInPeriod: Int)

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
        fun key(t: Transaction): String? {
            val c = config.category(t.categoryId) ?: return t.categoryId
            return if (rollUp && c.parentId != null) c.parentId else c.id
        }
        fun totals(p: YearMonth): Map<String, Long> = counted(list, p)
            .filter { it.kind != TransactionKind.INCOME }
            .groupBy { key(it) ?: "" }
            .mapValues { (_, v) -> v.sumOf { spend(it) } }
        val now = totals(period)
        val previous = (1..3).map { totals(period.minusMonths(it.toLong())) }
        val total = now.values.filter { it > 0 }.sum()
        return now.filter { it.value != 0L }.map { (id, amount) ->
            CategoryRow(
                categoryId = id,
                amountMinor = amount,
                share = if (total > 0) amount.toDouble() / total else 0.0,
                previousAverageMinor = previous.sumOf { it[id] ?: 0 } / 3,
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
     * Where variable spending is heading by the end of the period: the daily
     * pace so far times the days left, plus fixed spending so far and fixed
     * spending the previous period had that this one has not had yet.
     */
    fun projection(txs: Iterable<Transaction>, period: YearMonth, today: LocalDate): Projection? {
        val days = rule.daysOf(period)
        if (today < days.start) return null
        val list = txs.toList()
        val daysInPeriod = (ChronoUnit.DAYS.between(days.start, days.endInclusive) + 1).toInt()
        val elapsed = (ChronoUnit.DAYS.between(days.start, minOf(today, days.endInclusive)) + 1).toInt()
        val now = counted(list, period).filter { it.kind != TransactionKind.INCOME }
        val variable = now.filter { it.recurrence == Recurrence.VARIABLE }.sumOf { spend(it) }
        val fixed = now.filter { it.recurrence == Recurrence.FIXED }.sumOf { spend(it) }
        val fixedBefore = counted(list, period.minusMonths(1))
            .filter { it.kind != TransactionKind.INCOME && it.recurrence == Recurrence.FIXED }.sumOf { spend(it) }
        val projectedVariable = if (elapsed > 0) variable * daysInPeriod / elapsed else variable
        return Projection(
            spentSoFarMinor = variable + fixed,
            projectedMinor = projectedVariable + maxOf(fixed, fixedBefore),
            daysElapsed = elapsed,
            daysInPeriod = daysInPeriod,
        )
    }

    /**
     * Categories whose spending moved noticeably against the average of the
     * three previous periods: by at least 20 % and at least [minimumMinor].
     */
    fun trends(txs: Iterable<Transaction>, period: YearMonth, minimumMinor: Long): List<Trend> =
        byCategory(txs, period).mapNotNull { row ->
            val t = Trend(row.categoryId, row.amountMinor, row.previousAverageMinor)
            // No earlier spending is no "usual" to compare with: "100 % more than 0" says nothing.
            if (t.averageMinor > 0 && abs(t.currentMinor - t.averageMinor) >= minimumMinor && abs(t.change) >= 0.2) t else null
        }.sortedByDescending { abs(it.currentMinor - it.averageMinor) }

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
            previousSpentMinor = if (previous.isEmpty()) null else previous.sumOf { spend(it) },
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
                    is String -> v.toBigDecimalOrNull()?.toLong() ?: 0
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
