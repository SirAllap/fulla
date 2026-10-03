// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.money.DecimalText
import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.text.Hashes
import io.github.sirallap.fulla.core.time.ChronoUnit
import io.github.sirallap.fulla.core.time.DateTimeException
import io.github.sirallap.fulla.core.time.DateTimeFormatter
import io.github.sirallap.fulla.core.time.DayOfWeek
import io.github.sirallap.fulla.core.time.Instant
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What every platform must agree on: these run on the JVM and in JavaScript. */
class TimeTest {

    @Test
    fun days_count_from_the_epoch_and_come_back() {
        assertEquals(0L, LocalDate.of(1970, 1, 1).toEpochDay())
        assertEquals(LocalDate.of(2030, 2, 28), LocalDate.ofEpochDay(LocalDate.of(2030, 2, 28).toEpochDay()))
        assertEquals(DayOfWeek.THURSDAY, LocalDate.of(1970, 1, 1).dayOfWeek)
        assertEquals(DayOfWeek.TUESDAY, LocalDate.of(2030, 1, 1).dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2032, 2, 29).dayOfWeek)
        for (day in -800_000L..800_000L step 997) assertEquals(day, LocalDate.ofEpochDay(day).toEpochDay())
    }

    @Test
    fun months_keep_the_day_or_fall_back_to_the_last_one() {
        assertEquals(LocalDate.of(2030, 2, 28), LocalDate.of(2030, 1, 31).plusMonths(1))
        assertEquals(LocalDate.of(2032, 2, 29), LocalDate.of(2032, 1, 31).plusMonths(1))
        assertEquals(LocalDate.of(2029, 11, 30), LocalDate.of(2030, 1, 30).minusMonths(2))
        assertEquals(LocalDate.of(2031, 1, 15), LocalDate.of(2030, 1, 15).plusYears(1))
        assertEquals(LocalDate.of(2030, 1, 29), LocalDate.of(2030, 1, 31).minusDays(2))
        assertEquals(LocalDate.of(2030, 3, 1), LocalDate.of(2030, 2, 28).plusDays(1))
        assertEquals(LocalDate.of(2030, 1, 7), LocalDate.of(2030, 1, 10).with(DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2030, 1, 13), LocalDate.of(2030, 1, 10).with(DayOfWeek.SUNDAY))
    }

    @Test
    fun a_date_that_does_not_exist_is_refused_and_text_round_trips() {
        assertFailsWith<DateTimeException> { LocalDate.of(2030, 2, 29) }
        assertFailsWith<DateTimeException> { LocalDate.of(2030, 13, 1) }
        assertFailsWith<DateTimeException> { LocalDate.parse("2030-02-30") }
        assertFailsWith<DateTimeException> { LocalDate.parse("2030-2-3") }
        assertEquals("2030-01-05", LocalDate.parse("2030-01-05").toString())
        assertTrue(LocalDate.of(2030, 1, 5) < LocalDate.of(2030, 1, 6))
        assertEquals("2030-07", YearMonth.of(2030, 7).toString())
        assertEquals(YearMonth.of(2031, 1), YearMonth.of(2030, 11).plusMonths(2))
        assertEquals(YearMonth.of(2029, 12), YearMonth.of(2030, 1).minusMonths(1))
        assertEquals(LocalDate.of(2032, 2, 29), YearMonth.of(2032, 2).atEndOfMonth())
        assertEquals(5L, YearMonth.of(2030, 1).until(YearMonth.of(2030, 6), ChronoUnit.MONTHS))
    }

    @Test
    fun whole_days_weeks_and_months_between_two_days() {
        assertEquals(30L, ChronoUnit.DAYS.between(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31)))
        assertEquals(-3L, ChronoUnit.DAYS.between(LocalDate.of(2030, 1, 4), LocalDate.of(2030, 1, 1)))
        assertEquals(2L, ChronoUnit.WEEKS.between(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 15)))
        assertEquals(1L, ChronoUnit.MONTHS.between(LocalDate.of(2030, 1, 15), LocalDate.of(2030, 2, 15)))
        assertEquals(0L, ChronoUnit.MONTHS.between(LocalDate.of(2030, 1, 15), LocalDate.of(2030, 2, 14)))
        assertEquals(-1L, ChronoUnit.MONTHS.between(LocalDate.of(2030, 2, 15), LocalDate.of(2030, 1, 15)))
        assertEquals(0L, ChronoUnit.MONTHS.between(LocalDate.of(2030, 2, 15), LocalDate.of(2030, 1, 16)))
    }

    @Test
    fun instants_read_and_write_the_way_every_stored_timestamp_is_written() {
        val t = Instant.parse("2030-01-15T12:00:00Z")
        assertEquals("2030-01-15T12:00:00Z", t.toString())
        assertEquals("2030-01-15T12:00:00.250Z", t.plusMillis(250).toString())
        assertEquals("2030-01-15T13:00:00Z", t.plusSeconds(3600).toString())
        assertEquals(t, Instant.parse("2030-01-15T14:00:00+02:00"))
        assertEquals(t, Instant.parse("2030-01-15T12:00:00.000Z"))
        assertEquals(t.plusMillis(123), Instant.parse("2030-01-15T12:00:00.123456Z"))
        assertEquals(1_894_708_800_000L, t.toEpochMilli())
        assertTrue(t.isBefore(t.plusMillis(1)) && t.plusMillis(1).isAfter(t))
        assertEquals(LocalDate.of(2030, 1, 15), t.toLocalDate())
        assertEquals(LocalDate.of(2030, 1, 14), Instant.parse("2030-01-15T01:00:00Z").toLocalDate(-2 * 3600))
        assertFailsWith<DateTimeException> { Instant.parse("2030-01-15 12:00:00") }
        assertFailsWith<DateTimeException> { Instant.parse("2030-01-15T25:00:00Z") }
    }

    @Test
    fun a_banks_layout_is_read_the_way_java_time_reads_it() {
        fun read(pattern: String, text: String) = LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern))
        assertEquals(LocalDate.of(2030, 1, 5), read("dd/MM/yyyy", "05/01/2030"))
        assertEquals(LocalDate.of(2030, 1, 5), read("MM/dd/yyyy", "01/05/2030"))
        assertEquals(LocalDate.of(2030, 1, 5), read("d/M/yyyy", "5/1/2030"))
        assertEquals(LocalDate.of(2030, 1, 5), read("dd.MM.yyyy", "05.01.2030"))
        assertEquals(LocalDate.of(2030, 1, 5), read("yyyyMMdd", "20300105"))
        assertEquals(LocalDate.of(2030, 4, 30), read("dd/MM/yyyy", "31/04/2030"), "a day past the end of the month falls on its last day")
        assertFailsWith<DateTimeException> { read("dd/MM/yyyy", "5/1/2030") }
        assertFailsWith<DateTimeException> { read("dd/MM/yyyy", "05/13/2030") }
        assertFailsWith<DateTimeException> { read("dd/MM/yyyy", "05/01/2030 ") }
    }

    @Test
    fun hashes_match_the_published_vectors() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Hashes.hex(Hashes.sha1("abc".encodeToByteArray())))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Hashes.hex(Hashes.sha1(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashes.hex(Hashes.sha256("abc".encodeToByteArray())))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashes.hex(Hashes.sha256(ByteArray(0))))
        val long = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()
        assertEquals("84983e441c3bd26ebaae4aa1f95129e5e54670f1", Hashes.hex(Hashes.sha1(long)))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", Hashes.hex(Hashes.sha256(long)))
        assertEquals("2ed6657d-e927-568b-95e1-2665a8aea6a2", DeterministicId.uuid5("6ba7b810-9dad-11d1-80b4-00c04fd430c8", "www.example.com"))
    }

    @Test
    fun decimal_text_is_written_plainly() {
        assertEquals("12.5", DecimalText.plain("12.50"))
        assertEquals("100", DecimalText.plain("100"))
        assertEquals("100", DecimalText.plain("100.000"))
        assertEquals("1000", DecimalText.plain("1.0E3"))
        assertEquals("0.00015", DecimalText.plain("1.5e-4"))
        assertEquals("0", DecimalText.plain("0.0"))
        assertEquals("-1.5", DecimalText.plain("-1.50"))
        assertEquals("7", DecimalText.plain("007"))
        assertEquals("0.5", DecimalText.plain(".5"))
        assertNull(DecimalText.plain("abc"))
        assertNull(DecimalText.plain(""))
        assertNull(DecimalText.plain("."))
    }
}
