// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.analytics.DailyBasis
import io.github.sirallap.fulla.core.analytics.DayMark
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.rules.PeriodRule
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpendingCalendarTest {
    private val a = Analytics(Fixtures.config(), PeriodRule())
    private val january = YearMonth.of(2030, 1)
    private fun d(day: Int, month: Int = 1) = LocalDate.of(2030, month, day)

    // 3.100,00 in, a rent of 1.000,00: 2.100,00 over 31 days is 67,74 a day.
    private val rows = listOf(
        Fixtures.income(310_000, d(1)),
        Fixtures.expense(100_000, d(1)).copy(recurrence = Recurrence.FIXED),
        Fixtures.expense(5_000, d(2)),
        Fixtures.expense(8_000, d(3)),
    )

    @Test
    fun `a day is under or over what the income leaves per day once the fixed costs are paid`() {
        val c = a.calendar(rows, january, today = d(10, 2))
        assertEquals(DailyBasis.AFTER_FIXED, c.basis)
        assertEquals(6_774L, c.dailyMinor)
        assertEquals(31, c.days.size)
        assertEquals(DayMark.UNDER, c.days[1].mark)
        assertEquals(DayMark.OVER, c.days[2].mark)
        assertEquals(DayMark.UNDER, c.days[3].mark, "a day without spending is under")
        assertEquals(0L, c.days[0].spentMinor, "the rent is a fixed cost, not a day's spending")
        assertEquals(13_000L, c.days.sumOf { it.spentMinor })
        assertEquals(30, c.under)
        assertEquals(1, c.over)
    }

    @Test
    fun `today is not under until it ends, the days to come are neither, but a day already over is over`() {
        val c = a.calendar(rows, january, today = d(2))
        assertEquals(DayMark.UNDER, c.days[0].mark)
        assertEquals(DayMark.TO_COME, c.days[1].mark, "today, under so far")
        assertEquals(DayMark.TO_COME, c.days[2].mark, "tomorrow, though something is written down for it")
        val over = a.calendar(rows, january, today = d(3))
        assertEquals(DayMark.OVER, over.days[2].mark, "today, already over")
    }

    @Test
    fun `without income the usual everyday spending per day stands in, and without either there is nothing to compare`() {
        val february = YearMonth.of(2030, 2)
        val spending = rows.filter { it.kind != io.github.sirallap.fulla.core.model.TransactionKind.INCOME } +
            Fixtures.expense(2_000, d(5, 2))
        val c = a.calendar(spending, february, today = d(20, 3))
        assertEquals(DailyBasis.USUAL, c.basis)
        assertEquals(13_000L / 31, c.dailyMinor, "January's everyday spending over its days")
        assertEquals(DayMark.OVER, c.days[4].mark)
        val none = a.calendar(listOf(Fixtures.expense(2_000, d(5, 2))), february, today = d(20, 3))
        assertNull(none.dailyMinor)
        assertEquals(DayMark.NONE, none.days[4].mark)
    }
}
