// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.recurring.Coverage
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.Occurrence
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An occurrence somebody already wrote down by hand is not written, or
 * waited for, a second time.
 */
class CoverageTest {
    private fun d(day: Int, month: Int = 1) = LocalDate.of(2030, month, day)

    private fun rule(
        n: Int, schedule: Schedule, amount: Long = 80_000, category: String? = Fixtures.GROCERIES,
        kind: TransactionKind = TransactionKind.EXPENSE,
    ) = RecurringRule(
        id = "00000000-0000-4000-8000-0000000007%02d".format(n), name = "Rent",
        template = Fixtures.expense(amount, d(1), category = category ?: Fixtures.GROCERIES).copy(categoryId = category, kind = kind, recurrence = Recurrence.FIXED),
        schedule = schedule, startDate = d(1), autoCreate = true,
    )

    private val monthly = Schedule(Frequency.MONTHLY, byMonthDay = 1)
    private fun byHand(amount: Long, date: LocalDate, category: String = Fixtures.GROCERIES) = Fixtures.expense(amount, date, category = category)
    private fun cover(rule: RecurringRule, day: LocalDate, vararg rows: Transaction) = Coverage.byHand(listOf(Occurrence(rule, day)), rows.toList())

    @Test
    fun `a row of the same kind, category and about the same amount, close to the day, stands for it`() {
        val rent = rule(1, monthly)
        val row = byHand(80_000, d(2))
        assertEquals(mapOf(Occurrence(rent, d(1)) to row), cover(rent, d(1), row))
        assertTrue(cover(rent, d(1)).isEmpty(), "no rows, nothing stands for it")
    }

    @Test
    fun `the amount may differ by 5 percent, the day by 3`() {
        val rent = rule(1, monthly)
        assertTrue(cover(rent, d(1), byHand(83_900, d(1))).isNotEmpty(), "4.9 % more")
        assertTrue(cover(rent, d(1), byHand(76_100, d(1))).isNotEmpty(), "4.9 % less")
        assertTrue(cover(rent, d(1), byHand(84_100, d(1))).isEmpty(), "5.1 % more")
        assertTrue(cover(rent, d(1), byHand(80_000, d(4))).isNotEmpty(), "three days late")
        assertTrue(cover(rent, d(1), byHand(80_000, d(5))).isEmpty(), "four days late")
        assertTrue(cover(rent, d(10), byHand(80_000, d(7))).isNotEmpty(), "three days early")
        assertTrue(cover(rent, d(10), byHand(80_000, d(6))).isEmpty(), "four days early")
    }

    @Test
    fun `another category, kind or a rule without a category is never covered`() {
        val rent = rule(1, monthly)
        assertTrue(cover(rent, d(1), byHand(80_000, d(1), category = Fixtures.LEISURE)).isEmpty())
        assertTrue(cover(rent, d(1), byHand(80_000, d(1)).copy(kind = TransactionKind.INCOME)).isEmpty())
        assertTrue(cover(rule(2, monthly, category = null), d(1), byHand(80_000, d(1))).isEmpty(), "nothing to tell it by")
    }

    @Test
    fun `rows a recurring item wrote, a trip's, and deleted ones do not stand for anything`() {
        val rent = rule(1, monthly)
        assertTrue(cover(rent, d(1), byHand(80_000, d(1)).copy(recurringRuleId = rent.id, occurrenceDate = d(1))).isEmpty())
        assertTrue(cover(rent, d(1), byHand(80_000, d(1)).copy(tripId = "trip")).isEmpty())
        assertTrue(cover(rent, d(1), byHand(80_000, d(1)).copy(status = Status.DELETED)).isEmpty())
    }

    @Test
    fun `a row stands for one occurrence, the nearest, however they are listed`() {
        val lottery = rule(3, Schedule(Frequency.WEEKLY, byWeekday = listOf(1)), amount = 250)
        val monday1 = d(7)   // 2030-01-07 is a Monday
        val monday2 = d(14)
        val row = byHand(250, d(8)) // the Tuesday after the first Monday
        val slots = listOf(Occurrence(lottery, monday2), Occurrence(lottery, monday1))
        val found = Coverage.byHand(slots, listOf(row))
        assertEquals(mapOf(Occurrence(lottery, monday1) to row), found)
        // Two rows, two occurrences: each takes the nearest.
        val other = byHand(250, d(15))
        val both = Coverage.byHand(slots, listOf(other, row))
        assertEquals(row, both[Occurrence(lottery, monday1)])
        assertEquals(other, both[Occurrence(lottery, monday2)])
    }

    @Test
    fun `a rule that falls due every day, or on several days a week, leaves no room to tell the days apart`() {
        assertEquals(0, Coverage.toleranceDays(Schedule(Frequency.DAILY)))
        assertEquals(0, Coverage.toleranceDays(Schedule(Frequency.WEEKLY, byWeekday = listOf(1, 3))))
        assertEquals(1, Coverage.toleranceDays(Schedule(Frequency.WEEKLY, byWeekday = listOf(1))))
        assertEquals(3, Coverage.toleranceDays(monthly))
        assertEquals(3, Coverage.toleranceDays(Schedule(Frequency.YEARLY, byMonthDay = 1, byMonth = 3)))
        val daily = rule(4, Schedule(Frequency.DAILY), amount = 250)
        assertTrue(cover(daily, d(5), byHand(250, d(6))).isEmpty())
        assertTrue(cover(daily, d(5), byHand(250, d(5))).isNotEmpty())
    }

    @Test
    fun `two rules do not both claim one row`() {
        val a = rule(5, monthly)
        val b = rule(6, monthly)
        val row = byHand(80_000, d(1))
        val found = Coverage.byHand(listOf(Occurrence(b, d(1)), Occurrence(a, d(1))), listOf(row))
        assertEquals(1, found.size, "one row, one occurrence")
        assertNull(found[Occurrence(b, d(1))], "the first by rule id takes it")
    }
}
