// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.analytics.Place
import io.github.sirallap.fulla.core.model.Budget
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.rules.PeriodRule
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeriodReportTest {
    private fun d(day: Int, month: Int = 1) = LocalDate.of(2030, month, day)
    private val jan = YearMonth.of(2030, 1)
    private val config = Fixtures.config().copy(budgets = listOf(
        Budget("b1", Fixtures.GROCERIES, null, 5_000), Budget("b2", Fixtures.LEISURE, null, 10_000)))
    private val a = Analytics(config, PeriodRule())
    private fun note(t: io.github.sirallap.fulla.core.model.Transaction, n: String) = t.copy(note = n)

    // 2030-01-07 is a Monday.
    private val rows = listOf(
        Fixtures.income(200_000, d(1)),
        note(Fixtures.expense(80_000, d(1)), "Rent").copy(recurrence = Recurrence.FIXED, categoryId = Fixtures.LEISURE),
        note(Fixtures.expense(3_000, d(7)), "Grocery store 01"),
        note(Fixtures.expense(2_500, d(14)), "GROCERY STORE 01"),
        note(Fixtures.expense(1_000, d(12)), "Cinema"),
        note(Fixtures.expense(500, d(12)), "Cinema").copy(kind = TransactionKind.REFUND),
    )

    @Test
    fun `a period in progress, as of a day in it`() {
        val r = a.report(rows, jan, today = d(10))
        assertEquals(86_000, r.spentMinor)
        assertNull(r.previousSpentMinor, "nothing in December: no comparison")
        assertEquals(200_000, r.incomeMinor)
        assertEquals(0.57, r.savingsRate!!, 0.001)
        assertEquals(4, r.count)
        assertEquals(21_625, r.averageMinor)
        assertEquals(10, r.days)
        assertEquals(8_600, r.dailyMinor)
        assertEquals(80_000, r.fixedMinor)
        assertEquals(6_000, r.variableMinor)
        assertEquals(listOf(80_000L, 3_000L), r.biggest.take(2).map { it.amountMinor })
        assertEquals(3_000 + 2_500, r.weekdays[0], "Mondays")
        assertEquals(500, r.weekdays[5], "a Saturday's cinema, less its refund")
        assertEquals(listOf(Place("GROCERY STORE 01", 2, 5_500)), r.places, "seen twice, named as last written")
        assertEquals(2, r.budgets)
        assertEquals(2, r.budgetsOver)
    }

    @Test
    fun `a past period counts all its days, and compares with the one before`() {
        val feb = rows.map { it.copy(id = it.id + "f", date = it.date.plusMonths(1)) }
        val r = a.report(rows + feb, YearMonth.of(2030, 2), today = d(1, 6))
        assertEquals(28, r.days)
        assertEquals(86_000, r.previousSpentMinor)
        assertEquals(0, a.report(emptyList(), jan, today = d(1)).averageMinor)
        assertNull(a.report(emptyList(), jan, today = d(1)).savingsRate)
        assertEquals(0, a.report(rows, YearMonth.of(2030, 3), today = d(10)).days, "a period not started yet")
    }
}
