// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.analytics.Hero
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsTest {
    private val config = Fixtures.config()
    private val jan: YearMonth = YearMonth.of(2030, 1)
    private fun d(day: Int, month: Int = 1) = LocalDate.of(2030, month, day)

    private val rows = listOf(
        Fixtures.income(200_000, d(1)),
        Fixtures.expense(50_000, d(3)),
        Fixtures.expense(1_000, d(5), category = Fixtures.SNACKS),
        Fixtures.expense(9_999, d(6)).copy(status = Status.DELETED),
        Fixtures.expense(700, d(7)).copy(kind = TransactionKind.REFUND),
        Fixtures.expense(10_000, d(8)).copy(kind = TransactionKind.TRANSFER, categoryId = null, split = null, toAccountId = Fixtures.CASH),
        Fixtures.expense(3_000, d(9)).copy(kind = TransactionKind.SETTLEMENT, categoryId = null, split = null,
            paidByMemberId = Fixtures.BOB, toMemberId = Fixtures.ALICE),
    )

    @Test
    fun totals_count_income_and_spending_not_transfers_settlements_or_deleted_rows() {
        val s = Analytics(config, PeriodRule()).summary(rows, jan)
        assertEquals(200_000L, s.incomeMinor)
        assertEquals(50_300L, s.expenseMinor)
        assertEquals(149_700L, s.savingsMinor)
    }

    @Test
    fun the_period_rule_decides_the_month() {
        val salary = Fixtures.income(100_000, d(28))
        assertEquals(100_000L, Analytics(config, PeriodRule(incomeShiftDay = 25)).summary(listOf(salary), YearMonth.of(2030, 2)).incomeMinor)
        assertEquals(0L, Analytics(config, PeriodRule(incomeShiftDay = 25)).summary(listOf(salary), jan).incomeMinor)
        assertEquals(100_000L, Analytics(config, PeriodRule(periodStartDay = 20)).summary(listOf(salary), YearMonth.of(2030, 2)).incomeMinor)
    }

    @Test
    fun subcategories_roll_up_into_their_parent() {
        val a = Analytics(config, PeriodRule())
        val rolled = a.byCategory(rows, jan).associate { it.categoryId to it.amountMinor }
        assertEquals(50_300L, rolled[Fixtures.GROCERIES])
        val flat = a.byCategory(rows, jan, rollUp = false).associate { it.categoryId to it.amountMinor }
        assertEquals(1_000L, flat[Fixtures.SNACKS])
    }

    @Test
    fun account_balances_include_transfers() {
        val b = Analytics(config, PeriodRule()).accountBalances(rows, config.accounts, d(31))
        assertEquals(200_000L - 50_000 - 1_000 + 700 - 10_000, b[Fixtures.MAIN])
        assertEquals(10_000L, b[Fixtures.CASH])
    }

    @Test
    fun opening_balance_date_excludes_movements_dated_before_it() {
        val backdated = config.copy(accounts = config.accounts.map {
            if (it.id == Fixtures.MAIN) it.copy(openingBalanceDate = d(6)) else it
        })
        val b = Analytics(backdated, PeriodRule()).accountBalances(rows, backdated.accounts, d(31))
        // The income on d(1) and the expenses on d(3) and d(5) predate the
        // opening date and are already folded into it, so only the refund on
        // d(7) and the transfer out on d(8) still count.
        assertEquals(700L - 10_000, b[Fixtures.MAIN])
    }

    @Test
    fun a_movement_dated_exactly_on_the_opening_date_counts() {
        val onDate = config.copy(accounts = config.accounts.map {
            if (it.id == Fixtures.MAIN) it.copy(openingBalanceDate = d(3)) else it
        })
        val b = Analytics(onDate, PeriodRule()).accountBalances(rows, onDate.accounts, d(31))
        assertEquals(-50_000L - 1_000 + 700 - 10_000, b[Fixtures.MAIN])
    }

    @Test
    fun a_legacy_account_with_no_opening_balance_date_still_counts_every_movement() {
        val legacy = config.copy(accounts = config.accounts.map {
            if (it.id == Fixtures.MAIN) it.copy(openingBalanceDate = null) else it
        })
        val b = Analytics(legacy, PeriodRule()).accountBalances(rows, legacy.accounts, d(31))
        assertEquals(200_000L - 50_000 - 1_000 + 700 - 10_000, b[Fixtures.MAIN])
    }

    @Test
    fun per_member_spending_splits_shares() {
        val m = Analytics(config, PeriodRule()).byMember(rows, jan).associateBy { it.memberId }
        assertEquals(50_300L, m.getValue(Fixtures.ALICE).paidMinor)
        assertEquals(25_150L, m.getValue(Fixtures.ALICE).shareMinor)
        assertEquals(25_150L, m.getValue(Fixtures.BOB).shareMinor)
    }

    @Test
    fun the_hero_draws_savings_to_scale() {
        val h = Hero(200_000, 50_000)
        assertEquals(0.5, h.incomeFraction, 1e-9)
        assertEquals(0.125, h.expenseFraction, 1e-9)
        assertEquals(0.375, h.gapFraction, 1e-9)
        assertTrue(Hero(100, 150).gapFraction < 0, "a deficit overlaps")
    }

    @Test
    fun trends_and_days_without_spending() {
        val a = Analytics(config, PeriodRule())
        val history = (1..3).map { Fixtures.expense(1_000, LocalDate.of(2029, 12, 1).minusMonths(it - 1L)) } +
            Fixtures.expense(5_000, d(2))
        val t = a.trends(history, jan, minimumMinor = 500).single()
        assertEquals(Fixtures.GROCERIES, t.categoryId)
        // The 11th is not over yet: ten days are, and one of them had spending.
        assertEquals(9, a.noSpendDays(listOf(Fixtures.expense(100, d(3))), jan, d(11)))
        assertEquals(10, a.noSpendDays(emptyList(), jan, d(11)), "a day not spent yet is not a day without spending")
        assertEquals(0, a.noSpendDays(emptyList(), jan, d(1)), "the first day is not over")
        assertEquals(30, a.noSpendDays(listOf(Fixtures.expense(100, d(3))), jan, d(15, 2)), "a period that is over counts all of its days")
    }

    @Test
    fun the_budgets_of_a_period_taken_together_are_left_or_over_never_a_negative_left() {
        val limits = mapOf("a" to 30_000L, "b" to 10_000L)
        assertNull(io.github.sirallap.fulla.core.analytics.Budgets.status(emptyMap(), mapOf("a" to 5L)))
        val within = io.github.sirallap.fulla.core.analytics.Budgets.status(limits, mapOf("a" to 12_000L, "c" to 99_000L))!!
        assertEquals(40_000, within.limitMinor)
        assertEquals(12_000, within.usedMinor, "a category without a budget is not counted against the budgets")
        assertEquals(28_000, within.leftMinor)
        assertEquals(0, within.overMinor)
        val over = io.github.sirallap.fulla.core.analytics.Budgets.status(limits, mapOf("a" to 35_000L, "b" to 10_000L))!!
        assertEquals(0, over.leftMinor)
        assertEquals(5_000, over.overMinor)
    }

    @Test
    fun usual_is_the_average_of_the_earlier_periods_that_have_something_never_a_fixed_third() {
        val a = Analytics(config, PeriodRule())
        val dec = Fixtures.expense(30_000, LocalDate.of(2029, 12, 10))
        val now = Fixtures.expense(5_000, d(2))
        fun usual(rows: List<io.github.sirallap.fulla.core.model.Transaction>) =
            a.byCategory(rows, jan).single { it.categoryId == Fixtures.GROCERIES }.previousAverageMinor
        assertEquals(30_000, usual(listOf(dec, now)), "one month of history is that month, not a third of it")
        val oct = Fixtures.expense(10_000, LocalDate.of(2029, 10, 3))
        assertEquals(20_000, usual(listOf(dec, oct, now)), "November has nothing in it: the app was not in use, which is not a month of zero")
        assertEquals(0, usual(listOf(now)), "no history, no usual")
    }

    @Test
    fun trends_of_a_running_period_hold_the_same_days_against_each_other_not_whole_months() {
        val a = Analytics(config, PeriodRule())
        val dec = (1..31).map { Fixtures.expense(1_000, LocalDate.of(2029, 12, it)) }
        val same = (1..10).map { Fixtures.expense(1_000, d(it)) }
        assertEquals(emptyList(), a.trends(dec + same, jan, minimumMinor = 100, today = d(10)), "the same pace as December: nothing moved")
        val faster = (1..10).map { Fixtures.expense(2_000, d(it)) }
        val t = a.trends(dec + faster, jan, minimumMinor = 100, today = d(10)).single()
        assertEquals(20_000, t.currentMinor)
        assertEquals(10_000, t.averageMinor, "December's first ten days")
        assertEquals(1.0, t.change, 1e-9)
        val wholeMonths = a.trends(dec + same, jan, minimumMinor = 100).single()
        assertTrue(wholeMonths.change < -0.6, "without a day to cut at, ten days read as two thirds below a whole month")
    }

    @Test
    fun recurring_charges_are_spotted() {
        val subs = (0..3).map { Fixtures.expense(1_299, LocalDate.of(2030, 1 + it, 4), split = Split.Equal(listOf(Fixtures.ALICE)))
            .copy(note = "STREAMING SERVICE") }
        val found = Analytics(config, PeriodRule()).detectedRecurring(subs, d(10, 4)).single()
        assertEquals(1_299L, found.typicalMinor)
        assertEquals(4, found.months)
    }

    @Test
    fun grouping_by_any_dimension_custom_fields_included() {
        val tagged = listOf(
            Fixtures.expense(1_000, d(3)).copy(extras = mapOf("method" to "Card"), tags = listOf("trip")),
            Fixtures.expense(2_000, d(4)).copy(extras = mapOf("method" to "Cash"), recurrence = Recurrence.FIXED),
        )
        val a = Analytics(config, PeriodRule())
        assertEquals(mapOf("Card" to 1_000L, "Cash" to 2_000L), a.groupBy(tagged, jan, "method"))
        assertEquals(mapOf("trip" to 1_000L, "" to 2_000L), a.groupBy(tagged, jan, "tag"))
        assertEquals(mapOf("variable" to 1_000L, "fixed" to 2_000L), a.groupBy(tagged, jan, "recurrence"))
    }
}
