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
import io.github.sirallap.fulla.core.time.LocalDate
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
        id = "00000000-0000-4000-8000-0000000007" + n.toString().padStart(2, '0'), name = "Rent",
        template = Fixtures.expense(amount, d(1), category = category ?: Fixtures.GROCERIES).copy(categoryId = category, kind = kind, recurrence = Recurrence.FIXED),
        schedule = schedule, startDate = d(1), autoCreate = true,
    )

    private val monthly = Schedule(Frequency.MONTHLY, byMonthDay = 1)
    private fun byHand(amount: Long, date: LocalDate, category: String = Fixtures.GROCERIES) = Fixtures.expense(amount, date, category = category)
    private fun cover(rule: RecurringRule, day: LocalDate, vararg rows: Transaction) = Coverage.byHand(listOf(Occurrence(rule, day)), rows.toList())

    @Test
    fun a_row_of_the_same_kind_category_and_about_the_same_amount_close_to_the_day_stands_for_it() {
        val rent = rule(1, monthly)
        val row = byHand(80_000, d(2))
        assertEquals(mapOf(Occurrence(rent, d(1)) to row), cover(rent, d(1), row))
        assertTrue(cover(rent, d(1)).isEmpty(), "no rows, nothing stands for it")
    }

    @Test
    fun the_amount_may_differ_by_5_percent_the_day_by_3() {
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
    fun another_category_kind_or_a_rule_without_a_category_is_never_covered() {
        val rent = rule(1, monthly)
        assertTrue(cover(rent, d(1), byHand(80_000, d(1), category = Fixtures.LEISURE)).isEmpty())
        assertTrue(cover(rent, d(1), byHand(80_000, d(1)).copy(kind = TransactionKind.INCOME)).isEmpty())
        assertTrue(cover(rule(2, monthly, category = null), d(1), byHand(80_000, d(1))).isEmpty(), "nothing to tell it by")
    }

    @Test
    fun the_rent_typed_under_another_category_is_still_the_rent_when_its_note_reads_like_the_item_s_name() {
        val rent = rule(1, monthly)                       // named "Rent"
        val elsewhere = byHand(80_000, d(1), category = Fixtures.LEISURE)
        assertTrue(cover(rent, d(1), elsewhere.copy(note = "rent")).isNotEmpty(), "case does not matter")
        assertTrue(cover(rent, d(1), elsewhere.copy(note = "  RENT ")).isNotEmpty(), "nor spaces")
        assertTrue(cover(rent, d(1), elsewhere.copy(note = "Rent of the flat")).isEmpty(), "a note that only contains it is not the same thing")
        assertTrue(cover(rent, d(1), elsewhere.copy(note = "")).isEmpty(), "no note, no name to go by")
        assertTrue(cover(rent, d(1), elsewhere.copy(note = "Rent", amountMinor = 90_000)).isEmpty(), "the amount has to be about the same too")
        assertTrue(cover(rent, d(1), elsewhere.copy(note = "Rent", date = d(9))).isEmpty(), "and so does the day")
        // And an item without a category can be recognised by its name alone.
        val noCategory = rule(2, monthly, category = null)
        assertTrue(cover(noCategory, d(1), byHand(80_000, d(1)).copy(note = "Rent")).isNotEmpty())
    }

    @Test
    fun rows_a_recurring_item_wrote_a_trip_s_and_deleted_ones_do_not_stand_for_anything() {
        val rent = rule(1, monthly)
        assertTrue(cover(rent, d(1), byHand(80_000, d(1)).copy(recurringRuleId = rent.id, occurrenceDate = d(1))).isEmpty())
        assertTrue(cover(rent, d(1), byHand(80_000, d(1)).copy(tripId = "trip")).isEmpty())
        assertTrue(cover(rent, d(1), byHand(80_000, d(1)).copy(status = Status.DELETED)).isEmpty())
    }

    @Test
    fun a_row_stands_for_one_occurrence_the_nearest_however_they_are_listed() {
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
    fun a_rule_that_falls_due_every_day_or_on_several_days_a_week_leaves_no_room_to_tell_the_days_apart() {
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
    fun two_rules_do_not_both_claim_one_row() {
        val a = rule(5, monthly)
        val b = rule(6, monthly)
        val row = byHand(80_000, d(1))
        val found = Coverage.byHand(listOf(Occurrence(b, d(1)), Occurrence(a, d(1))), listOf(row))
        assertEquals(1, found.size, "one row, one occurrence")
        assertNull(found[Occurrence(b, d(1))], "the first by rule id takes it")
    }
}
