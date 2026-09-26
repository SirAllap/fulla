// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.guide.MonthStart
import io.github.sirallap.fulla.core.model.Household
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.rules.PeriodRule
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull

class MonthStartTest {

    // ── construction ─────────────────────────────────────────────────────

    @Test
    fun `Payday and SalaryNextMonth validate their range like PeriodRule does`() {
        assertFailsWith<IllegalArgumentException> { MonthStart.Payday(1) }
        assertFailsWith<IllegalArgumentException> { MonthStart.Payday(29) }
        assertFailsWith<IllegalArgumentException> { MonthStart.SalaryNextMonth(0) }
        assertFailsWith<IllegalArgumentException> { MonthStart.SalaryNextMonth(32) }
        MonthStart.Payday(2); MonthStart.Payday(28)
        // 1 is a real SalaryNextMonth state (every fixed income shifts), matching PeriodRule
        // and the database's own `income_shift_day between 1 and 31`.
        MonthStart.SalaryNextMonth(1); MonthStart.SalaryNextMonth(2); MonthStart.SalaryNextMonth(31)
    }

    @Test
    fun `of reads a household's current choice back`() {
        val base = Fixtures.config().household
        assertEquals(MonthStart.Calendar, MonthStart.of(base))
        assertEquals(MonthStart.Payday(15), MonthStart.of(base.copy(periodStartDay = 15)))
        assertEquals(MonthStart.SalaryNextMonth(25), MonthStart.of(base.copy(incomeShiftDay = 25)))
        // A shift day set wins even if period_start_day were somehow also non-default,
        // matching PeriodRule's own precedence (the two are mutually exclusive in practice).
        assertEquals(MonthStart.SalaryNextMonth(25), MonthStart.of(base.copy(periodStartDay = 1, incomeShiftDay = 25)))
    }

    @Test
    fun `income_shift_day of 1 is a real household state and does not crash of`() {
        // The database allows income_shift_day down to 1 (0001_schema.sql); a
        // household in that state must still read back cleanly instead of
        // MonthStart.of crashing on an invalid SalaryNextMonth(1).
        val household = Fixtures.config().household.copy(incomeShiftDay = 1)
        assertEquals(MonthStart.SalaryNextMonth(1), MonthStart.of(household))
        assertEquals(PeriodRule(incomeShiftDay = 1), MonthStart.of(household).toRule())
    }

    // ── toPatch ───────────────────────────────────────────────────────────

    @Test
    fun `toPatch always carries both keys, one explicitly null, and round-trips through PeriodRule`() {
        val calendar = MonthStart.Calendar.toPatch()
        assertEquals(setOf("period_start_day", "income_shift_day"), calendar.keys)
        assertEquals(1, calendar["period_start_day"])
        assertNull(calendar["income_shift_day"])
        assertEquals(PeriodRule(), MonthStart.Calendar.toRule())

        for (day in 2..28) {
            val patch = MonthStart.Payday(day).toPatch()
            assertEquals(setOf("period_start_day", "income_shift_day"), patch.keys)
            assertEquals(day, patch["period_start_day"])
            assertNull(patch["income_shift_day"])
            assertEquals(PeriodRule(periodStartDay = day), MonthStart.Payday(day).toRule())
        }
        for (day in 1..31) {
            val patch = MonthStart.SalaryNextMonth(day).toPatch()
            assertEquals(setOf("period_start_day", "income_shift_day"), patch.keys)
            assertEquals(1, patch["period_start_day"])
            assertEquals(day, patch["income_shift_day"])
            assertEquals(PeriodRule(incomeShiftDay = day), MonthStart.SalaryNextMonth(day).toRule())
        }
    }

    // ── preview ───────────────────────────────────────────────────────────

    @Test
    fun `Payday(28) preview around the boundary, invented dates in 2030`() {
        val payday28 = MonthStart.Payday(28)

        val early = payday28.preview(LocalDate.of(2030, 3, 10))
        assertEquals(YearMonth.of(2030, 3), early.label)
        assertEquals(LocalDate.of(2030, 2, 28)..LocalDate.of(2030, 3, 27), early.days)
        // Salary on the 28th of March falls on-or-after the period's own start day (28),
        // so it lands in the period that starts that day: April.
        assertEquals(LocalDate.of(2030, 3, 28) to YearMonth.of(2030, 4), early.salaryExample)

        val onBoundary = payday28.preview(LocalDate.of(2030, 3, 28))
        assertEquals(YearMonth.of(2030, 4), onBoundary.label)
        assertEquals(LocalDate.of(2030, 3, 28)..LocalDate.of(2030, 4, 27), onBoundary.days)
        assertEquals(LocalDate.of(2030, 3, 28) to YearMonth.of(2030, 4), onBoundary.salaryExample)
    }

    @Test
    fun `SalaryNextMonth(25) contrasted with Calendar - fixed income moves, the calendar month never does`() {
        val today = LocalDate.of(2030, 1, 20)

        val calendar = MonthStart.Calendar.preview(today)
        assertEquals(YearMonth.of(2030, 1), calendar.label)
        assertEquals(LocalDate.of(2030, 1, 1)..LocalDate.of(2030, 1, 31), calendar.days)
        // Calendar's boundary day is the 1st, which never shifts anything.
        assertEquals(LocalDate.of(2030, 1, 1) to YearMonth.of(2030, 1), calendar.salaryExample)

        val shifted = MonthStart.SalaryNextMonth(25).preview(today)
        // The period itself is still the plain calendar month...
        assertEquals(YearMonth.of(2030, 1), shifted.label)
        assertEquals(LocalDate.of(2030, 1, 1)..LocalDate.of(2030, 1, 31), shifted.days)
        // ...but fixed income paid on day 25 counts for next month's period.
        assertEquals(LocalDate.of(2030, 1, 25) to YearMonth.of(2030, 2), shifted.salaryExample)
    }

    @Test
    fun `31 Jan 2030 and Feb 2030 - a shift day that does not exist in a short month never fires`() {
        val salary31 = MonthStart.SalaryNextMonth(31)

        val jan = salary31.preview(LocalDate.of(2030, 1, 15))
        assertEquals(YearMonth.of(2030, 1), jan.label)
        assertEquals(LocalDate.of(2030, 1, 1)..LocalDate.of(2030, 1, 31), jan.days)
        // 31 January exists: income on it shifts to February.
        assertEquals(LocalDate.of(2030, 1, 31) to YearMonth.of(2030, 2), jan.salaryExample)

        val feb = salary31.preview(LocalDate.of(2030, 2, 15))
        assertEquals(YearMonth.of(2030, 2), feb.label)
        // 2030 is not a leap year: February has 28 days.
        assertEquals(LocalDate.of(2030, 2, 1)..LocalDate.of(2030, 2, 28), feb.days)
        // Day 31 does not exist in February; the example clamps to the last real day (28),
        // which never reaches day 31, so it never shifts.
        assertEquals(LocalDate.of(2030, 2, 28) to YearMonth.of(2030, 2), feb.salaryExample)
    }

    // ── shared vectors ───────────────────────────────────────────────────

    /**
     * Every case in testdata/vectors/period.json sets at most one of
     * period_start_day/income_shift_day away from its default, so every one
     * of them is representable as a MonthStart (Calendar, Payday or
     * SalaryNextMonth) and none needs to be skipped.
     */
    @Test
    fun `MonthStart-derived rules agree with period json`() {
        for (v in Fixtures.json("testdata/vectors/period.json").jsonObject["vectors"]!!.jsonArray) {
            val i = v.jsonObject["input"]!!.jsonObject
            val startDay = i["period_start_day"]!!.jsonPrimitive.int
            val shiftDay = i["income_shift_day"]?.jsonPrimitive?.intOrNull
            val monthStart = when {
                shiftDay != null -> MonthStart.SalaryNextMonth(shiftDay)
                startDay > 1 -> MonthStart.Payday(startDay)
                else -> MonthStart.Calendar
            }
            val got = monthStart.toRule().label(
                LocalDate.parse(i["date"]!!.jsonPrimitive.content),
                TransactionKind.of(i["kind"]!!.jsonPrimitive.content)!!,
                Recurrence.of(i["recurrence"]!!.jsonPrimitive.content),
            )
            assertEquals(v.jsonObject["expected"]!!.jsonPrimitive.content, got, v.jsonObject["why"]!!.jsonPrimitive.content)
        }
    }
}
