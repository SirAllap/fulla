// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.analytics.DayTotals
import io.github.sirallap.fulla.core.analytics.FixedStatus
import io.github.sirallap.fulla.core.analytics.Percent
import io.github.sirallap.fulla.core.balance.Balances
import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.core.model.Budget
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import java.time.LocalDate
import io.github.sirallap.fulla.core.trips.Trip
import io.github.sirallap.fulla.core.trips.Trips
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The figures of the app, read as one set: whatever way they are asked for, they have to add up to each other. A
 * made-up household per seed (three kinds of period, fixed costs that write themselves and some that were written by
 * hand, refunds, trips, transfers, settlements, deleted rows, a budget, accounts), a made-up day, and every identity
 * between two figures that a person could check with a calculator.
 */
class FiguresAddUpTest {
    private fun d(day: Int, month: Int, year: Int) = LocalDate.of(year, month, day)
    private val stamp = "2029-09-01T00:00:00.000Z"

    private fun rule(n: Int, name: String, amount: Long, day: Int, category: String, auto: Boolean) = RecurringRule(
        id = "00000000-0000-4000-8000-0000000007" + n.toString().padStart(2, '0'), name = name,
        template = Fixtures.expense(amount, d(1, 9, 2029), category = category).copy(recurrence = Recurrence.FIXED, note = name),
        schedule = Schedule(Frequency.MONTHLY, byMonthDay = day), startDate = d(1, 9, 2029), autoCreate = auto,
    )

    private class Household(val config: Config, val rows: List<Transaction>, val periodRule: PeriodRule, val today: LocalDate)

    private fun household(seed: Long): Household {
        val rnd = Random(seed)
        val today = d(1, 1, 2030).plusDays(rnd.nextInt(70).toLong())
        // Salaries on about the 25th of each month before today.
        val salaryDates = (9..13).map { m -> if (m <= 12) d(25 - rnd.nextInt(3), m, 2029) else d(25 - rnd.nextInt(3), m - 12, 2030) }.filter { it <= today }.let { all -> if (seed % 4 == 3L && all.size > 2) all.dropLast(1) else all }
        // One household in six starts using the app two weeks ago: nothing earlier to learn from.
        val first = if (seed % 6 == 5L) today.minusDays(14) else d(1, 9, 2029)
        val periodRule = when (seed % 3) {
            0L -> PeriodRule()
            1L -> PeriodRule(periodStartDay = 20)
            else -> PeriodRule(anchors = salaryDates)
        }
        val rules = listOf(
            rule(1, "Rent", 80_000, 1, Fixtures.LEISURE, true), rule(2, "Phone", 3_000, 12, Fixtures.SNACKS, true),
            rule(3, "Gym", 3_500, 20, Fixtures.LEISURE, rnd.nextBoolean()),
        )
        val trips = listOf(
            Trip("00000000-0000-4000-8000-000000000801", "Porto", d(3, 1, 2030), d(9, 1, 2030), budgetMinor = 30_000),
            Trip("00000000-0000-4000-8000-000000000802", "Wedding", d(22, 12, 2029), d(24, 12, 2029), budgetMinor = null, inCategoryBudgets = true),
        )
        val config = Fixtures.config().copy(
            accounts = listOf(Account(Fixtures.MAIN, "Main", AccountType.CHECKING, 100_000, d(1, 9, 2029)), Account(Fixtures.CASH, "Cash", AccountType.CASH)),
            budgets = listOf(Budget("b1", Fixtures.GROCERIES, null, 40_000), Budget("b2", Fixtures.LEISURE, null, 30_000), Budget("b3", Fixtures.GROCERIES, "2030-01", 35_000)),
            recurringRules = rules, trips = trips,
        )
        val rows = mutableListOf<Transaction>()
        fun add(t: Transaction) {
            // A twentieth of everything is deleted; a few rows are dated after today.
            rows += if (rnd.nextInt(20) == 0) t.copy(status = Status.DELETED) else t
        }
        for (date in salaryDates.filter { it >= first }) add(Fixtures.income(240_000L + rnd.nextInt(30_000), date))
        val people = listOf(Fixtures.ALICE, Fixtures.BOB)
        var day = first
        while (day <= today.plusDays(3)) {
            for (r in rules) if (r.schedule.byMonthDay == day.dayOfMonth && r.active) {
                val amount = if (rnd.nextInt(5) == 0) r.template.amountMinor * (97 + rnd.nextInt(7)) / 100 else r.template.amountMinor
                when {
                    day > today -> Unit
                    r.autoCreate && rnd.nextInt(10) != 0 ->
                        add(r.template.copy(id = DeterministicId.occurrence(r.id, day), date = day, amountMinor = amount, recurringRuleId = r.id, occurrenceDate = day,
                            createdAt = stamp, clientUpdatedAt = stamp))
                    rnd.nextInt(8) == 0 -> add(Fixtures.expense(amount, day.plusDays((rnd.nextInt(5) - 2).toLong()), category = r.template.categoryId!!).copy(note = r.name, recurrence = Recurrence.FIXED))
                }
            }
            repeat(if (rnd.nextInt(10) < 8) 1 + rnd.nextInt(3) else 0) {
                val big = rnd.nextInt(25) == 0
                val amount = if (big) 5_000L + rnd.nextInt(30_000) else 200L + rnd.nextInt(3_000)
                val category = listOf(Fixtures.GROCERIES, Fixtures.SNACKS, Fixtures.LEISURE, null)[rnd.nextInt(4)]
                val payer = people[rnd.nextInt(2)]
                val trip = trips.firstOrNull { it.startDate <= day && day <= it.endDate && rnd.nextInt(3) > 0 }
                val kind = if (rnd.nextInt(30) == 0) TransactionKind.REFUND else TransactionKind.EXPENSE
                add(Fixtures.expense(if (kind == TransactionKind.REFUND) amount / 2 + 1 else amount, day, payer = payer,
                    split = if (rnd.nextInt(10) < 7) Split.Equal(people) else null, category = category ?: Fixtures.GROCERIES)
                    .copy(kind = kind, categoryId = category, tripId = trip?.id, note = listOf("GROCERY STORE 01", "CAFE 02", "TAXI", "")[rnd.nextInt(4)],
                        recurrence = if (rnd.nextInt(7) == 0) Recurrence.FIXED else Recurrence.VARIABLE))
            }
            if (rnd.nextInt(40) == 0) add(Fixtures.expense(5_000, day).copy(kind = TransactionKind.TRANSFER, toAccountId = Fixtures.CASH, split = null, categoryId = null))
            if (rnd.nextInt(60) == 0) add(Fixtures.expense(2_500, day).copy(kind = TransactionKind.SETTLEMENT, paidByMemberId = Fixtures.ALICE, toMemberId = Fixtures.BOB, split = null, categoryId = null))
            day = day.plusDays(1)
        }
        return Household(config, rows, periodRule, today)
    }

    private fun spend(t: Transaction): Long = when (t.kind) { TransactionKind.EXPENSE -> t.amountMinor; TransactionKind.REFUND -> -t.amountMinor; else -> 0 }

    @Test
    fun `the figures agree with each other across many made up households`() {
        var forecasts = 0; var known = 0; var withKept = 0; var ownPace = 0; var waiting = 0; var running = 0; var onTrips = 0; var pending = 0
        for (seed in 1L..240L) {
            val h = household(seed)
            val a = Analytics(h.config, h.periodRule)
            val rule = h.periodRule
            val today = h.today
            val period = rule.periodOf(today, TransactionKind.EXPENSE, Recurrence.VARIABLE)
            fun why(what: String) = "seed $seed ($period, today $today): $what"
            val active = h.rows.filter { it.isActive }
            val counted = active.filter { it.kind.countsInTotals && rule.periodOf(it.date, it.kind, it.recurrence) == period }
            val spent = counted.sumOf(::spend)
            val income = counted.filter { it.kind == TransactionKind.INCOME }.sumOf { it.amountMinor }

            // One spent, one income, wherever it is asked.
            val summary = a.summary(h.rows, period)
            assertEquals(spent, summary.expenseMinor, why("summary spent"))
            assertEquals(income, summary.incomeMinor, why("summary income"))
            // The days of the history say what was spent and received; together they are the period.
            val days = counted.groupBy { it.date }.values.map { DayTotals.of(it.map { t -> LocalTransaction(t, SyncState.SYNCED) }) }
            assertTrue(days.all { it.spentMinor >= 0 && it.receivedMinor >= 0 }, why("a day is never negative"))
            assertEquals(income - spent, days.sumOf { it.receivedMinor - it.spentMinor }, why("the days add up to the period"))
            val report = a.report(h.rows, period, today, h.config.trips)
            assertEquals(spent, report.spentMinor, why("report spent"))
            assertEquals(income, report.incomeMinor, why("report income"))
            val hero = a.hero(h.rows, period)
            assertEquals(income - spent, hero.savingsMinor, why("hero savings"))
            assertEquals(a.series(h.rows, period, 3).last(), summary, why("series ends on the period"))

            // Where it went, however it is cut.
            assertEquals(spent, a.byCategory(h.rows, period).sumOf { it.amountMinor }, why("categories add up to spent"))
            assertEquals(spent, a.byCategory(h.rows, period, rollUp = false).sumOf { it.amountMinor }, why("categories, not rolled up"))
            val positive = a.byCategory(h.rows, period).filter { it.amountMinor > 0 }.sumOf { it.amountMinor }
            assertEquals(positive, a.topCategories(h.rows, period).sumOf { it.amountMinor }, why("the chart shows every positive category"))
            assertEquals(report.spentMinor, report.fixedMinor + report.variableMinor, why("fixed and variable add up to spent"))
            assertEquals(report.variableMinor, report.weekdays.sum(), why("weekdays add up to the variable spending"))
            assertEquals(spent, a.groupBy(h.rows, period, "category").values.sum(), why("grouped by category"))
            assertEquals(spent, a.groupBy(h.rows, period, "recurrence").values.sum(), why("grouped by recurrence"))
            val excluded = h.config.trips.filter { !it.inCategoryBudgets }.map { it.id }.toSet()
            val onTripsSpent = counted.filter { it.tripId in excluded }.sumOf(::spend)
            if (onTripsSpent != 0L) onTrips++
            assertEquals(spent - onTripsSpent, a.budgetSpend(h.rows, period, h.config.trips).sumOf { it.amountMinor }, why("budgets leave out the trips that have their own jar"))
            assertTrue(report.budgetsOver <= report.budgets, why("budgets over"))
            assertTrue(a.noSpendDays(h.rows, period, today) in 0..report.days, why("days without spending are days of the period"))

            // Who paid and who owes.
            val paidRows = counted.filter { it.kind != TransactionKind.INCOME && it.paidByMemberId != null }
            val members = a.byMember(h.rows, period)
            assertEquals(paidRows.sumOf(::spend), members.sumOf { it.paidMinor }, why("what members paid"))
            assertEquals(counted.filter { it.kind != TransactionKind.INCOME && (it.split != null || it.paidByMemberId != null) }.sumOf(::spend), members.sumOf { it.shareMinor }, why("what members' shares come to"))
            val balances = Balances.of(active, h.config.members.map { it.id })
            assertEquals(0L, balances.sumOf { it.balanceMinor }, why("balances add up to zero"))

            // The accounts.
            val accounts = a.accountBalances(h.rows, h.config.accounts, today)
            val moved = active.filter { it.date <= today && it.accountId != null && (it.date >= (h.config.account(it.accountId)?.openingBalanceDate ?: it.date)) }.sumOf {
                when (it.kind) { TransactionKind.INCOME, TransactionKind.REFUND -> it.amountMinor; TransactionKind.EXPENSE -> -it.amountMinor; else -> 0L }
            }
            assertEquals(h.config.accounts.sumOf { it.openingBalanceMinor } + moved, accounts.values.sum(), why("the accounts hold what they opened with plus what moved"))

            // Trips.
            for (trip in h.config.trips) {
                val totals = Trips.totals(trip, h.rows)
                assertEquals(active.filter { it.tripId == trip.id }.sumOf(::spend), totals.spentMinor, why("trip spent"))
                val budget = trip.budgetMinor
                if (budget != null) {
                    assertEquals(budget - totals.spentMinor, totals.leftMinor!! - totals.overMinor, why("trip left less over is budget less spent"))
                    assertTrue(totals.leftMinor!! >= 0 && totals.overMinor >= 0 && (totals.leftMinor == 0L || totals.overMinor == 0L), why("trip is left or over, never both"))
                    Trips.perDay(trip, totals, today)?.let { p -> assertTrue(p.amountMinor * p.days <= (totals.leftMinor ?: 0) || (totals.leftMinor ?: 0) == 0L, why("trip per day")) }
                }
            }

            // How the period is likely to end.
            val forecast = a.forecast(h.rows, period, today, h.rows.filter { !it.isActive }.map { it.id }.toSet())
            if (report.partial) running++
            if (forecast != null) {
                forecasts++
                if (forecast.waiting) waiting++
                if (forecast.known) known++
                if (forecast.keptMinor != null) withKept++
                if (forecast.ownPace) ownPace++
                if (forecast.fixedToComeMinor > 0) pending++
                assertEquals(spent, forecast.spentMinor, why("the forecast has spent what the report says"))
                assertEquals(income, forecast.incomeMinor, why("and the same income"))
                assertEquals(forecast.fixedPaidMinor + forecast.fixedToComeMinor, forecast.fixedTotalMinor, why("fixed total"))
                assertTrue(forecast.fixed.none { it.status == FixedStatus.PAID && it.amountMinor <= 0 }, why("a paid charge has an amount"))
                val writtenByRules = counted.filter { it.kind == TransactionKind.EXPENSE && it.recurringRuleId != null && h.config.recurringRules.any { r -> r.id == it.recurringRuleId && r.autoCreate } }.sumOf { it.amountMinor }
                assertTrue(forecast.fixedPaidMinor >= writtenByRules, why("every charge a recurring item wrote is among the ones paid"))
                if (forecast.totalIncomeMinor > 0) {
                    assertEquals(forecast.totalIncomeMinor - spent - forecast.fixedToComeMinor, forecast.leftToSpendMinor, why("what is left to spend"))
                    assertEquals(forecast.totalIncomeMinor - forecast.fixedTotalMinor, forecast.afterFixedMinor, why("after the fixed"))
                }
                if (!forecast.waiting) {
                    assertTrue(forecast.daysToGo >= 1, why("a day to go at least"))
                    val left = forecast.leftToSpendMinor
                    val perDay = forecast.perDayMinor
                    if (left != null && perDay != null && left >= 0) assertTrue(perDay * forecast.daysToGo <= left && left < (perDay + 1) * forecast.daysToGo, why("per day times the days is what is left"))
                }
                if (forecast.known) {
                    val rest = forecast.everydayRestMinor!!
                    assertEquals(spent + forecast.fixedToComeMinor + rest, forecast.spentEndMinor, why("spent at the end"))
                    assertTrue(forecast.everydayLowMinor!! <= rest && rest <= forecast.everydayHighMinor!!, why("everyday range"))
                    assertTrue(forecast.spentEndLowMinor!! <= forecast.spentEndMinor!! && forecast.spentEndMinor!! <= forecast.spentEndHighMinor!!, why("spent range"))
                    forecast.keptMinor?.let { kept ->
                        assertEquals(forecast.totalIncomeMinor - forecast.spentEndMinor!!, kept, why("kept"))
                        assertTrue(forecast.keptLowMinor!! <= kept && kept <= forecast.keptHighMinor!!, why("kept range"))
                    }
                }
            }
        }
        // The made-up households reach every branch of the figures that are checked, not just the easy ones.
        val seen = "forecasts $forecasts, known $known, with income $withKept, own pace $ownPace, waiting $waiting, running $running, trips $onTrips, fixed to come $pending"
        assertTrue(forecasts > 150 && known > 150 && withKept > 60 && ownPace > 5 && waiting > 10 && running > 150 && onTrips > 40 && pending > 100, seen)
    }

    /** Every number the screens show for one period, as plain values: two runs that agree here show the same screens. */
    private fun fingerprint(h: Household, rows: List<Transaction>): List<Any?> {
        val a = Analytics(h.config, h.periodRule)
        val period = h.periodRule.periodOf(h.today, TransactionKind.EXPENSE, Recurrence.VARIABLE)
        val summary = a.summary(rows, period)
        val r = a.report(rows, period, h.today, h.config.trips)
        val f = a.forecast(rows, period, h.today, rows.filter { !it.isActive }.map { it.id }.toSet())
        return listOf(
            summary, r.spentMinor, r.previousSpentMinor, r.incomeMinor, r.savingsRate, r.count, r.averageMinor, r.days, r.dailyMinor, r.fixedMinor, r.variableMinor,
            r.weekdays, r.budgets, r.budgetsOver, r.noSpendDays, r.noSpendOf, r.partial, r.biggest.map { it.amountMinor }, r.places,
            a.byCategory(rows, period).associate { it.categoryId to (it.amountMinor to it.previousAverageMinor) },
            a.topCategories(rows, period).map { it.key to it.amountMinor }.sortedBy { it.first }.toSet(),
            a.trends(rows, period, 1, h.today).map { Triple(it.categoryId, it.currentMinor, it.averageMinor) }.toSet(),
            a.byMember(rows, period), a.series(rows, period, 4), a.accountBalances(rows, h.config.accounts, h.today),
            Balances.of(rows.filter { it.isActive }, h.config.members.map { it.id }).map { Triple(it.memberId, it.paidMinor, it.shareMinor) }.toSet(),
            a.noSpendDays(rows, period, h.today), a.detectedRecurring(rows, h.today).map { it.note to it.typicalMinor }.toSet(),
            f?.let {
                listOf(it.day, it.length, it.incomeMinor, it.expectedIncomeMinor, it.spentMinor, it.bookedAheadMinor, it.fixed.sortedWith(compareBy({ x -> x.date }, { x -> x.ruleId })),
                    it.everydayLowMinor, it.everydayRestMinor, it.everydayHighMinor, it.waiting, it.everydaySoFarMinor, it.ownPace, it.leftToSpendMinor, it.perDayMinor, it.keptMinor)
            },
        )
    }

    @Test
    fun `the order the rows come in changes no figure`() {
        for (seed in 1L..80L) {
            val h = household(seed)
            val shuffled = h.rows.shuffled(Random(seed * 7919))
            assertEquals(fingerprint(h, h.rows), fingerprint(h, shuffled), "seed $seed: the same rows in another order show other figures")
        }
    }

    @Test
    fun `a row added and a row deleted move the figures by exactly that row`() {
        for (seed in 1L..80L) {
            val h = household(seed)
            val a = Analytics(h.config, h.periodRule)
            val period = h.periodRule.periodOf(h.today, TransactionKind.EXPENSE, Recurrence.VARIABLE)
            fun why(what: String) = "seed $seed ($period, today ${h.today}): $what"
            val before = a.forecast(h.rows, period, h.today, h.rows.filter { !it.isActive }.map { it.id }.toSet()) ?: continue
            val base = a.report(h.rows, period, h.today, h.config.trips)
            val x = Fixtures.expense(4_321, h.today, category = Fixtures.GROCERIES).copy(recurrence = Recurrence.VARIABLE, note = "ONE-OFF")
            fun forecastOf(rows: List<Transaction>) = a.forecast(rows, period, h.today, rows.filter { !it.isActive }.map { it.id }.toSet())!!
            val withX = h.rows + x
            val f = forecastOf(withX)
            assertEquals(before.spentMinor + 4_321, f.spentMinor, why("spent moves by the amount"))
            assertEquals(a.report(withX, period, h.today, h.config.trips).spentMinor, base.spentMinor + 4_321, why("so does the report"))
            assertEquals(before.incomeMinor, f.incomeMinor, why("income does not move"))
            assertEquals(before.fixedToComeMinor, f.fixedToComeMinor, why("nor the fixed costs to come"))
            assertEquals(before.bookedAheadMinor, f.bookedAheadMinor, why("nor what is booked ahead"))
            before.leftToSpendMinor?.let { assertEquals(it - 4_321, f.leftToSpendMinor, why("what is left moves by the amount")) }
            assertEquals(a.summary(h.rows, period).expenseMinor + 4_321, a.summary(withX, period).expenseMinor, why("the summary too"))
            assertEquals(a.hero(h.rows, period).savingsMinor - 4_321, a.hero(withX, period).savingsMinor, why("the jar too"))
            // Written off, it is as if it never was.
            val gone = h.rows + x.copy(status = Status.DELETED)
            assertEquals(fingerprint(h, h.rows), fingerprint(h, gone), why("a deleted row changes nothing"))
            // A refund of the same amount takes the spending back where it was.
            val refund = x.copy(id = Fixtures.newId(), kind = TransactionKind.REFUND)
            assertEquals(before.spentMinor, forecastOf(withX + refund).spentMinor, why("a refund of the same amount cancels it"))
            // Income moves what is left, nothing else.
            val pay = Fixtures.income(10_000, h.today).copy(recurrence = Recurrence.VARIABLE)
            val richer = forecastOf(h.rows + pay)
            assertEquals(before.spentMinor, richer.spentMinor, why("income is not spending"))
            before.leftToSpendMinor?.let { assertEquals(it + 10_000, richer.leftToSpendMinor, why("income adds to what is left")) }
        }
    }

    @Test
    fun `usual is between the smallest and the largest of the periods it is the average of`() {
        for (seed in 1L..240L) {
            val h = household(seed)
            val a = Analytics(h.config, h.periodRule)
            val period = h.periodRule.periodOf(h.today, TransactionKind.EXPENSE, Recurrence.VARIABLE)
            val before = (1..3).map { period.minusMonths(it.toLong()) }.filter { p ->
                h.rows.any { it.isActive && it.kind.countsInTotals && h.periodRule.periodOf(it.date, it.kind, it.recurrence) == p }
            }
            val totals = before.map { p -> a.byCategory(h.rows, p).associate { it.categoryId to it.amountMinor } }
            for (row in a.byCategory(h.rows, period)) {
                val own = totals.map { it[row.categoryId] ?: 0L }
                val low = own.minOrNull() ?: 0L
                val high = own.maxOrNull() ?: 0L
                assertTrue(row.previousAverageMinor in low..high, "seed $seed ${row.categoryId}: usual ${row.previousAverageMinor} is not between $low and $high")
            }
        }
    }

    @Test
    fun `a recurring item is not suggested again as something that looks recurring`() {
        val a = Analytics(Fixtures.config().copy(recurringRules = listOf(rule(1, "Streaming service", 1_299, 4, Fixtures.LEISURE, true))), PeriodRule())
        val subs = (0..3).map { Fixtures.expense(1_299, LocalDate.of(2030, 1 + it, 4)).copy(note = "STREAMING SERVICE") }
        assertEquals(emptyList(), a.detectedRecurring(subs, d(10, 4, 2030)), "there is a recurring item with that name already")
        assertEquals(1, Analytics(Fixtures.config(), PeriodRule()).detectedRecurring(subs, d(10, 4, 2030)).size, "and without it, it is suggested")
    }

    @Test
    fun `percentages of one chart add up to exactly one hundred`() {
        val rnd = Random(7)
        repeat(500) {
            val parts = List(1 + rnd.nextInt(8)) { if (rnd.nextInt(6) == 0) 0L else 1L + rnd.nextInt(100_000) }
            val pct = Percent.split(parts)
            if (parts.any { it > 0 }) {
                assertEquals(100, pct.sum(), "parts $parts gave $pct")
                parts.forEachIndexed { i, p -> assertTrue(if (p > 0) pct[i] in 0..100 else pct[i] == 0) }
                // Never further than one point from the exact share.
                val total = parts.sum().toDouble()
                parts.forEachIndexed { i, p -> assertTrue(kotlin.math.abs(pct[i] - p * 100 / total) < 1.0 + 1e-9, "parts $parts gave $pct") }
            } else assertEquals(parts.map { 0 }, pct)
        }
        assertEquals(listOf(34, 33, 33), Percent.split(listOf(1, 1, 1)))
        assertEquals(listOf(67, 25, 6, 2), Percent.split(listOf(6_700, 2_500, 640, 160)))
    }
}
