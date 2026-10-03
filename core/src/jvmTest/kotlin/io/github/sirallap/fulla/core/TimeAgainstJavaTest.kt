// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.time.Instant
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth
import io.github.sirallap.fulla.core.time.toFulla
import io.github.sirallap.fulla.core.time.toJava
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The dates and instants written for the browser must mean what java.time means, over a long run of random days. */
class TimeAgainstJavaTest {

    @Test
    fun `dates agree with java time`() {
        val rnd = Random(2030)
        repeat(20_000) {
            val j = java.time.LocalDate.ofEpochDay(rnd.nextLong(-1_000_000, 1_000_000))
            val mine = j.toFulla()
            assertEquals(j.toString(), mine.toString())
            assertEquals(j.dayOfWeek.value, mine.dayOfWeek.value)
            assertEquals(j.lengthOfMonth(), mine.lengthOfMonth())
            assertEquals(j.toEpochDay(), mine.toEpochDay())
            val n = rnd.nextLong(-5000, 5000)
            assertEquals(j.plusDays(n), mine.plusDays(n).toJava())
            val m = rnd.nextLong(-200, 200)
            assertEquals(j.plusMonths(m), mine.plusMonths(m).toJava())
            assertEquals(j.minusMonths(m), mine.minusMonths(m).toJava())
            assertEquals(j.plusYears(m), mine.plusYears(m).toJava())
            assertEquals(j.with(java.time.DayOfWeek.MONDAY), mine.with(io.github.sirallap.fulla.core.time.DayOfWeek.MONDAY).toJava())
            val other = j.plusDays(rnd.nextLong(-2000, 2000))
            assertEquals(java.time.temporal.ChronoUnit.DAYS.between(j, other), io.github.sirallap.fulla.core.time.ChronoUnit.DAYS.between(mine, other.toFulla()))
            assertEquals(java.time.temporal.ChronoUnit.MONTHS.between(j, other), io.github.sirallap.fulla.core.time.ChronoUnit.MONTHS.between(mine, other.toFulla()))
            assertEquals(java.time.temporal.ChronoUnit.WEEKS.between(j, other), io.github.sirallap.fulla.core.time.ChronoUnit.WEEKS.between(mine, other.toFulla()))
            assertEquals(j.compareTo(other).coerceIn(-1, 1), mine.compareTo(other.toFulla()).coerceIn(-1, 1))
            assertEquals(j.withDayOfMonth(1), mine.withDayOfMonth(1).toJava())
            val ym = java.time.YearMonth.from(j)
            assertEquals(ym.toString(), YearMonth.from(mine).toString())
            assertEquals(ym.plusMonths(m), YearMonth.from(mine).plusMonths(m).toJava())
            assertEquals(ym.atEndOfMonth(), YearMonth.from(mine).atEndOfMonth().toJava())
        }
    }

    @Test
    fun `instants agree with java time`() {
        val rnd = Random(2031)
        repeat(20_000) {
            val j = java.time.Instant.ofEpochMilli(rnd.nextLong(-3_000_000_000_000L, 9_000_000_000_000L))
            val mine = j.toFulla()
            val text = j.toString()
            assertEquals(text, mine.toString())
            assertEquals(j, Instant.parse(text).toJava())
        }
    }
}
