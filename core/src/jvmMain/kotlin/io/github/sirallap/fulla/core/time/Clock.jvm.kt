// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.time

internal actual object Clock {
    actual fun nowMillis(): Long = System.currentTimeMillis()
    actual fun today(): LocalDate = java.time.LocalDate.now().let { LocalDate.of(it.year, it.monthValue, it.dayOfMonth) }
}

/** The same day as a java.time date, for the platform's own formatting and pickers. */
fun LocalDate.toJava(): java.time.LocalDate = java.time.LocalDate.of(year, monthValue, dayOfMonth)
fun java.time.LocalDate.toFulla(): LocalDate = LocalDate.of(year, monthValue, dayOfMonth)
fun YearMonth.toJava(): java.time.YearMonth = java.time.YearMonth.of(year, monthValue)
fun java.time.YearMonth.toFulla(): YearMonth = YearMonth.of(year, monthValue)
fun Instant.toJava(): java.time.Instant = java.time.Instant.ofEpochMilli(toEpochMilli())
fun java.time.Instant.toFulla(): Instant = Instant.ofEpochMilli(toEpochMilli())
fun DayOfWeek.toJava(): java.time.DayOfWeek = java.time.DayOfWeek.of(value)
