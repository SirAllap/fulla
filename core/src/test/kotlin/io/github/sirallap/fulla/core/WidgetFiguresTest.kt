// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.goals.Goal
import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.core.model.Budget
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.roundup.RoundUps
import io.github.sirallap.fulla.core.widget.WidgetData
import io.github.sirallap.fulla.core.widget.WidgetFigures
import io.github.sirallap.fulla.core.widget.WidgetMode
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class WidgetFiguresTest {
    private val jan = YearMonth.of(2030, 1)
    private val analytics = Analytics(Fixtures.config(), PeriodRule())
    private fun d(day: Int) = LocalDate.of(2030, 1, day)

    @Test
    fun `the widget shows the overview's own figures - spent, left and left per day`() {
        val rows = listOf(Fixtures.income(200_000, d(1)), Fixtures.expense(50_000, d(3)), Fixtures.expense(9_000, d(9)))
        val w = WidgetFigures.of(analytics, rows, jan, today = d(10))
        val forecast = analytics.forecast(rows, jan, d(10))!!
        assertEquals(analytics.summary(rows, jan).expenseMinor, w.spentMinor)
        assertEquals(59_000, w.spentMinor)
        assertEquals(forecast.leftToSpendMinor, w.leftMinor)
        assertEquals(forecast.perDayMinor, w.perDayMinor)
        assertEquals(22, w.daysToGo) // the 10th to the 31st, today included
        assertNull(w.overMinor)
    }

    @Test
    fun `over is said as over, never as a negative left, and there is nothing per day to spend`() {
        val rows = listOf(Fixtures.income(10_000, d(1)), Fixtures.expense(25_000, d(3)))
        val w = WidgetFigures.of(analytics, rows, jan, today = d(10))
        assertEquals(0, w.leftMinor)
        assertEquals(15_000, w.overMinor)
        assertNull(w.perDayMinor)
    }

    @Test
    fun `without income only what was spent is shown`() {
        val w = WidgetFigures.of(analytics, listOf(Fixtures.expense(4_000, d(2))), jan, today = d(10))
        assertEquals(4_000, w.spentMinor)
        assertNull(w.leftMinor); assertNull(w.overMinor); assertNull(w.perDayMinor)
    }

    @Test
    fun `today is what History says of the day, against the overview's per-day figure`() {
        val rows = listOf(Fixtures.income(200_000, d(1)), Fixtures.expense(3_000, d(10)), Fixtures.expense(1_000, d(9)),
            Fixtures.expense(500, d(10)).copy(kind = TransactionKind.REFUND))
        val t = WidgetData.today(analytics, rows, jan, today = d(10))
        assertEquals(2_500, t.spentMinor) // 30,00 - a 5,00 refund; yesterday is not today
        assertEquals(WidgetFigures.of(analytics, rows, jan, d(10)).perDayMinor, t.perDayMinor)
        assertFalse(t.over)
        assertTrue(WidgetData.today(analytics, rows + Fixtures.expense(20_000, d(10)), jan, d(10)).over)
    }

    @Test
    fun `the budget adds up the period's budgets, null without any`() {
        val config = Fixtures.config()
        assertNull(WidgetData.budget(config, analytics, emptyList(), jan))
        val budgeted = config.copy(budgets = listOf(Budget("00000000-0000-4000-8000-000000000501", Fixtures.GROCERIES, null, 10_000)))
        val a = Analytics(budgeted, PeriodRule())
        val b = WidgetData.budget(budgeted, a, listOf(Fixtures.expense(4_000, d(3)), Fixtures.expense(9_999, d(4), category = Fixtures.LEISURE)), jan)!!
        assertEquals(10_000, b.limitMinor)
        assertEquals(6_000, b.leftMinor)
        assertEquals(0, b.overMinor)
    }

    @Test
    fun `the accounts total waits for a starting balance, then is the overview's`() {
        val rows = listOf(Fixtures.expense(4_000, d(3)))
        assertNull(WidgetData.accounts(Fixtures.config(), analytics, rows, d(10)))
        val config = Fixtures.config().copy(accounts = listOf(Account(Fixtures.MAIN, "Main account", AccountType.CHECKING, openingBalanceMinor = 100_000)))
        val a = Analytics(config, PeriodRule())
        assertEquals(a.accountsTotal(rows, config.accounts, d(10)), WidgetData.accounts(config, a, rows, d(10)))
        assertEquals(96_000, WidgetData.accounts(config, a, rows, d(10)))
    }

    @Test
    fun `recent is the newest first, without round-ups or what is dated ahead`() {
        val old = Fixtures.expense(100, d(2))
        val new = Fixtures.expense(200, d(9))
        val ahead = Fixtures.expense(300, d(20))
        val round = Fixtures.expense(50, d(9)).copy(kind = TransactionKind.TRANSFER, tags = listOf(RoundUps.TAG))
        assertEquals(listOf(new, old), WidgetData.recent(listOf(old, ahead, round, new), today = d(10)))
        assertEquals(1, WidgetData.recent(listOf(old, new), d(10), count = 1).size)
    }

    @Test
    fun `the goal is the first open one, with Balances' progress`() {
        assertNull(WidgetData.goal(Fixtures.config(), analytics, emptyList(), d(10)))
        val goal = Goal("00000000-0000-4000-8000-000000000601", "Holidays", 50_000, Fixtures.MAIN)
        val config = Fixtures.config().copy(goals = listOf(goal.copy(id = "00000000-0000-4000-8000-000000000602", archived = true), goal))
        val (g, p) = WidgetData.goal(config, Analytics(config, PeriodRule()), listOf(Fixtures.income(20_000, d(1))), d(10))!!
        assertEquals(goal, g)
        assertEquals(20_000, p.savedMinor)
        assertEquals(30_000, p.leftMinor)
    }

    @Test
    fun `a widget whose choice is unknown shows what is left`() {
        assertEquals(WidgetMode.LEFT, WidgetMode.of(null))
        assertEquals(WidgetMode.LEFT, WidgetMode.of("something-newer"))
        WidgetMode.entries.forEach { assertEquals(it, WidgetMode.of(it.key)) }
    }

    @Test
    fun `the liquid is as full as what is left, empty and marked when over`() {
        val config = Fixtures.config()
        val rows = listOf(Fixtures.income(200_000, d(1)), Fixtures.expense(50_000, d(3)))
        val left = WidgetData.level(WidgetMode.LEFT, config, analytics, rows, jan, d(10))!!
        assertEquals(0.75f, left.fill, 0.001f)
        assertFalse(left.over)
        val over = WidgetData.level(WidgetMode.LEFT, config, analytics, rows + Fixtures.expense(300_000, d(4)), jan, d(10))!!
        assertEquals(0f, over.fill)
        assertTrue(over.over)
        assertNull(WidgetData.level(WidgetMode.LEFT, config, analytics, listOf(Fixtures.expense(100, d(2))), jan, d(10)), "no income, no level")
        assertNull(WidgetData.level(WidgetMode.RECENT, config, analytics, rows, jan, d(10)))
    }

    @Test
    fun `the shortcuts are the categories written down most lately, topped up from the list`() {
        val config = Fixtures.config()
        val rows = listOf(Fixtures.expense(100, d(5), category = Fixtures.SNACKS), Fixtures.expense(100, d(6), category = Fixtures.SNACKS),
            Fixtures.expense(100, d(7), category = Fixtures.LEISURE), Fixtures.expense(100, LocalDate.of(2029, 6, 1), category = Fixtures.GROCERIES))
        val quick = WidgetData.quickCategories(config, rows, d(10))
        assertEquals(listOf(Fixtures.SNACKS, Fixtures.LEISURE), quick.take(2).map { it.id })
        assertEquals(quick.size, quick.map { it.id }.distinct().size)
        assertTrue(quick.none { it.id == Fixtures.SALARY }, "income categories are not offered")
    }
}
