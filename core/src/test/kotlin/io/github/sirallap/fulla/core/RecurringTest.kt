// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import io.github.sirallap.fulla.core.recurring.Scheduler
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecurringTest {
    private fun rule(schedule: Schedule, start: String = "2030-01-01", end: String? = null) = RecurringRule(
        id = "00000000-0000-4000-8000-00000000abcd", name = "Rent", template = Fixtures.expense(),
        schedule = schedule, startDate = LocalDate.parse(start), endDate = end?.let(LocalDate::parse),
    )
    private fun dates(r: RecurringRule, from: String, to: String) =
        Scheduler.occurrences(r, LocalDate.parse(from), LocalDate.parse(to)).map { it.toString() }

    @Test
    fun `the last day of the month is the last day of every month`() {
        assertEquals(listOf("2030-01-31", "2030-02-28", "2030-03-31", "2030-04-30"),
            dates(rule(Schedule(Frequency.MONTHLY, byMonthDay = -1)), "2030-01-01", "2030-04-30"))
    }

    @Test
    fun `a day past the end of a month falls on its last day`() {
        assertEquals(listOf("2032-01-31", "2032-02-29", "2032-03-31"),
            dates(rule(Schedule(Frequency.MONTHLY, byMonthDay = 31), "2032-01-01"), "2032-01-01", "2032-03-31"))
    }

    @Test
    fun `every other week on two days`() {
        val r = rule(Schedule(Frequency.WEEKLY, interval = 2, byWeekday = listOf(1, 5)), start = "2030-01-07")
        assertEquals(listOf("2030-01-07", "2030-01-11", "2030-01-21", "2030-01-25"), dates(r, "2030-01-01", "2030-01-31"))
    }

    @Test
    fun `every three months, yearly, and daily`() {
        assertEquals(listOf("2030-01-15", "2030-04-15", "2030-07-15", "2030-10-15"),
            dates(rule(Schedule(Frequency.MONTHLY, interval = 3, byMonthDay = 15)), "2030-01-01", "2030-12-31"))
        assertEquals(listOf("2030-06-01", "2031-06-01"),
            dates(rule(Schedule(Frequency.YEARLY, byMonthDay = 1, byMonth = 6)), "2030-01-01", "2031-12-31"))
        assertEquals(listOf("2030-01-01", "2030-01-04", "2030-01-07"),
            dates(rule(Schedule(Frequency.DAILY, interval = 3)), "2030-01-01", "2030-01-08"))
    }

    @Test
    fun `nothing before the start, after the end, or while paused`() {
        val r = rule(Schedule(Frequency.MONTHLY, byMonthDay = 10), start = "2030-03-01", end = "2030-05-10")
        assertEquals(listOf("2030-03-10", "2030-04-10", "2030-05-10"), dates(r, "2030-01-01", "2030-12-31"))
        assertTrue(dates(r.copy(active = false), "2030-01-01", "2030-12-31").isEmpty())
    }

    @Test
    fun `uuid5 matches the RFC example`() {
        val dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        assertEquals("2ed6657d-e927-568b-95e1-2665a8aea6a2", DeterministicId.uuid5(dns, "www.example.com"))
    }
}
