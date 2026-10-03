// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.time

/*
 * Dates and instants the rules need, written once so they behave the same on
 * the JVM and in a browser. The names follow java.time on purpose: the rules
 * were written against it, and the tests pin what they mean.
 *
 * Calendar days only (proleptic ISO calendar, no time zones). A zone appears
 * in exactly one place: [LocalDate.now], through [Clock].
 */

enum class DayOfWeek(val value: Int) {
    MONDAY(1), TUESDAY(2), WEDNESDAY(3), THURSDAY(4), FRIDAY(5), SATURDAY(6), SUNDAY(7);

    companion object {
        fun of(value: Int): DayOfWeek {
            require(value in 1..7) { "Invalid value for DayOfWeek: $value" }
            return entries[value - 1]
        }
    }
}

/** A unit for [between]: the whole units from one day (or month) to another. */
enum class ChronoUnit {
    DAYS, WEEKS, MONTHS;

    fun between(from: LocalDate, to: LocalDate): Long = when (this) {
        DAYS -> to.toEpochDay() - from.toEpochDay()
        WEEKS -> (to.toEpochDay() - from.toEpochDay()) / 7
        // Whole months, counted toward zero like java.time: month and day packed side by side.
        MONTHS -> ((YearMonth.from(to).monthIndex * 32 + to.dayOfMonth) - (YearMonth.from(from).monthIndex * 32 + from.dayOfMonth)) / 32
    }
}

class DateTimeException(message: String) : RuntimeException(message)

/** A calendar day: 2030-01-31. */
class LocalDate private constructor(val year: Int, val monthValue: Int, val dayOfMonth: Int) : Comparable<LocalDate> {

    val month: Int get() = monthValue
    val isLeapYear: Boolean get() = isLeap(year)
    val dayOfWeek: DayOfWeek get() = DayOfWeek.of((toEpochDay() + 3).mod(7L).toInt() + 1)

    fun lengthOfMonth(): Int = monthLength(year, monthValue)

    /** Days since 1970-01-01. */
    fun toEpochDay(): Long {
        val y = year.toLong()
        val m = monthValue.toLong()
        var total = 365 * y
        total += if (y >= 0) (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400 else -(y / -4 - y / -100 + y / -400)
        total += (367 * m - 362) / 12
        total += dayOfMonth - 1
        if (m > 2) {
            total--
            if (!isLeapYear) total--
        }
        return total - DAYS_0000_TO_1970
    }

    fun plusDays(days: Long): LocalDate = if (days == 0L) this else ofEpochDay(toEpochDay() + days)
    fun plusDays(days: Int): LocalDate = plusDays(days.toLong())
    fun minusDays(days: Long): LocalDate = plusDays(-days)
    fun minusDays(days: Int): LocalDate = plusDays(-days.toLong())
    fun plusWeeks(weeks: Long): LocalDate = plusDays(weeks * 7)

    /** Months move the month and keep the day, or fall back to the last day of a shorter month. */
    fun plusMonths(months: Long): LocalDate {
        if (months == 0L) return this
        val index = year * 12L + (monthValue - 1) + months
        val y = index.floorDiv(12L).toInt()
        val m = index.mod(12L).toInt() + 1
        return of(y, m, minOf(dayOfMonth, monthLength(y, m)))
    }
    fun plusMonths(months: Int): LocalDate = plusMonths(months.toLong())
    fun minusMonths(months: Long): LocalDate = plusMonths(-months)
    fun minusMonths(months: Int): LocalDate = plusMonths(-months.toLong())
    fun plusYears(years: Long): LocalDate = plusMonths(years * 12)
    fun minusYears(years: Long): LocalDate = plusMonths(-years * 12)

    fun withDayOfMonth(day: Int): LocalDate = of(year, monthValue, day)
    fun withMonth(month: Int): LocalDate = of(year, month, minOf(dayOfMonth, monthLength(year, month)))

    /** The same day of the week within this ISO week (Monday to Sunday): `with(MONDAY)` is the week's first day. */
    fun with(day: DayOfWeek): LocalDate = plusDays(day.value - dayOfWeek.value)

    fun isBefore(other: LocalDate): Boolean = compareTo(other) < 0
    fun isAfter(other: LocalDate): Boolean = compareTo(other) > 0
    fun isEqual(other: LocalDate): Boolean = compareTo(other) == 0

    override fun compareTo(other: LocalDate): Int {
        var c = year - other.year
        if (c == 0) c = monthValue - other.monthValue
        if (c == 0) c = dayOfMonth - other.dayOfMonth
        return c
    }

    override fun equals(other: Any?): Boolean =
        other is LocalDate && year == other.year && monthValue == other.monthValue && dayOfMonth == other.dayOfMonth

    override fun hashCode(): Int = (year and -2048) xor ((year shl 11) + (monthValue shl 6) + dayOfMonth)

    /** ISO 8601: 2030-01-31. */
    override fun toString(): String {
        return "${yearText(year)}-${monthValue.toString().padStart(2, '0')}-${dayOfMonth.toString().padStart(2, '0')}"
    }

    companion object {
        private const val DAYS_0000_TO_1970 = 719528L

        fun of(year: Int, month: Int, day: Int): LocalDate {
            if (month !in 1..12) throw DateTimeException("Invalid value for MonthOfYear: $month")
            if (day !in 1..31) throw DateTimeException("Invalid value for DayOfMonth: $day")
            if (day > monthLength(year, month)) {
                throw DateTimeException(
                    if (day == 29) "Invalid date 'February 29' as '$year' is not a leap year" else "Invalid date '$year-$month-$day'",
                )
            }
            return LocalDate(year, month, day)
        }

        fun ofEpochDay(epochDay: Long): LocalDate {
            var zeroDay = epochDay + DAYS_0000_TO_1970 - 60
            var adjust = 0L
            if (zeroDay < 0) {
                val cycles = (zeroDay + 1) / 146097 - 1
                adjust = cycles * 400
                zeroDay += -cycles * 146097
            }
            var yearEst = (400 * zeroDay + 591) / 146097
            var doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
            if (doyEst < 0) {
                yearEst--
                doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
            }
            yearEst += adjust
            val marchDoy0 = doyEst.toInt()
            val marchMonth0 = (marchDoy0 * 5 + 2) / 153
            val month = (marchMonth0 + 2) % 12 + 1
            val dom = marchDoy0 - (marchMonth0 * 306 + 5) / 10 + 1
            yearEst += marchMonth0 / 10
            return LocalDate(yearEst.toInt(), month, dom)
        }

        /** The day in the household's own time zone. */
        fun now(): LocalDate = Clock.today()

        /** ISO 8601 (2030-01-31) only; any other layout goes through [DateTimeFormatter]. */
        fun parse(text: CharSequence): LocalDate {
            val m = ISO_DATE.matchEntire(text) ?: throw DateTimeException("Text '$text' could not be parsed at index 0")
            return of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }

        fun parse(text: CharSequence, formatter: DateTimeFormatter): LocalDate = formatter.parseDate(text.toString())

        private val ISO_DATE = Regex("""([+-]?\d{4,9})-(\d{2})-(\d{2})""")

        internal fun isLeap(year: Int): Boolean = (year and 3) == 0 && (year % 100 != 0 || year % 400 == 0)
        internal fun monthLength(year: Int, month: Int): Int = when (month) {
            2 -> if (isLeap(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

/** A calendar month: 2030-01. */
class YearMonth private constructor(val year: Int, val monthValue: Int) : Comparable<YearMonth> {

    /** Months since year 0: lets months be compared and counted. */
    internal val monthIndex: Long get() = year * 12L + (monthValue - 1)

    fun lengthOfMonth(): Int = LocalDate.monthLength(year, monthValue)
    fun atDay(day: Int): LocalDate = LocalDate.of(year, monthValue, day)
    fun atEndOfMonth(): LocalDate = LocalDate.of(year, monthValue, lengthOfMonth())

    fun plusMonths(months: Long): YearMonth = if (months == 0L) this else ofIndex(monthIndex + months)
    fun plusMonths(months: Int): YearMonth = plusMonths(months.toLong())
    fun minusMonths(months: Long): YearMonth = plusMonths(-months)
    fun minusMonths(months: Int): YearMonth = plusMonths(-months.toLong())
    fun plusYears(years: Long): YearMonth = plusMonths(years * 12)
    fun minusYears(years: Long): YearMonth = plusMonths(-years * 12)
    fun withMonth(month: Int): YearMonth = of(year, month)

    /** Whole months from this month to [end]; only [ChronoUnit.MONTHS] is meaningful for a month. */
    fun until(end: YearMonth, unit: ChronoUnit): Long {
        require(unit == ChronoUnit.MONTHS) { "Unsupported unit: $unit" }
        return end.monthIndex - monthIndex
    }

    fun isBefore(other: YearMonth): Boolean = compareTo(other) < 0
    fun isAfter(other: YearMonth): Boolean = compareTo(other) > 0

    override fun compareTo(other: YearMonth): Int = year.compareTo(other.year).let { if (it != 0) it else monthValue - other.monthValue }
    override fun equals(other: Any?): Boolean = other is YearMonth && year == other.year && monthValue == other.monthValue
    override fun hashCode(): Int = year xor (monthValue shl 27)

    /** ISO 8601: 2030-01. */
    override fun toString(): String = "${yearText(year)}-${monthValue.toString().padStart(2, '0')}"

    companion object {
        fun of(year: Int, month: Int): YearMonth {
            if (month !in 1..12) throw DateTimeException("Invalid value for MonthOfYear: $month")
            return YearMonth(year, month)
        }

        fun from(date: LocalDate): YearMonth = YearMonth(date.year, date.monthValue)
        fun now(): YearMonth = from(LocalDate.now())

        fun parse(text: CharSequence): YearMonth {
            val m = Regex("""(\d{4})-(\d{2})""").matchEntire(text) ?: throw DateTimeException("Text '$text' could not be parsed at index 0")
            return of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }

        private fun ofIndex(index: Long): YearMonth = YearMonth(index.floorDiv(12L).toInt(), index.mod(12L).toInt() + 1)
    }
}

/** A moment, to the millisecond (the precision every stored timestamp has). */
class Instant private constructor(private val epochMilli: Long) : Comparable<Instant> {

    fun toEpochMilli(): Long = epochMilli
    fun plusMillis(millis: Long): Instant = Instant(epochMilli + millis)
    fun minusMillis(millis: Long): Instant = Instant(epochMilli - millis)
    fun plusSeconds(seconds: Long): Instant = Instant(epochMilli + seconds * 1000)
    fun minusSeconds(seconds: Long): Instant = Instant(epochMilli - seconds * 1000)

    fun isBefore(other: Instant): Boolean = epochMilli < other.epochMilli
    fun isAfter(other: Instant): Boolean = epochMilli > other.epochMilli

    /** The day this moment falls on at UTC offset [offsetSeconds]. */
    fun toLocalDate(offsetSeconds: Int = 0): LocalDate =
        LocalDate.ofEpochDay((epochMilli + offsetSeconds * 1000L).floorDiv(86_400_000L))

    override fun compareTo(other: Instant): Int = epochMilli.compareTo(other.epochMilli)
    override fun equals(other: Any?): Boolean = other is Instant && epochMilli == other.epochMilli
    override fun hashCode(): Int = epochMilli.hashCode()

    /** ISO 8601 in UTC, like java.time: milliseconds only when there are some (`2030-01-15T12:00:00Z`, `…:00.250Z`). */
    override fun toString(): String {
        val day = epochMilli.floorDiv(86_400_000L)
        val inDay = epochMilli.mod(86_400_000L)
        val h = inDay / 3_600_000
        val mi = inDay / 60_000 % 60
        val s = inDay / 1000 % 60
        val ms = inDay % 1000
        val frac = if (ms == 0L) "" else "." + ms.toString().padStart(3, '0')
        fun two(n: Long) = n.toString().padStart(2, '0')
        return "${LocalDate.ofEpochDay(day)}T${two(h)}:${two(mi)}:${two(s)}${frac}Z"
    }

    companion object {
        fun ofEpochMilli(epochMilli: Long): Instant = Instant(epochMilli)
        fun now(): Instant = Instant(Clock.nowMillis())

        /** `2030-01-15T12:00:00Z`, with optional fraction and either `Z` or an offset like `+02:00`. */
        fun parse(text: CharSequence): Instant {
            val m = ISO_INSTANT.matchEntire(text) ?: throw DateTimeException("Text '$text' could not be parsed at index 0")
            val (y, mo, d, h, mi, s) = m.destructured
            val frac = m.groupValues[7]
            val zone = m.groupValues[8]
            val date = LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
            if (h.toInt() > 23 || mi.toInt() > 59 || s.toInt() > 59) throw DateTimeException("Text '$text' could not be parsed: invalid time")
            val millis = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
            val offset = if (zone == "Z" || zone == "z") 0L else {
                val sign = if (zone[0] == '-') -1 else 1
                sign * (zone.substring(1, 3).toLong() * 3600 + zone.substring(4, 6).toLong() * 60)
            }
            return Instant((date.toEpochDay() * 86_400L + h.toLong() * 3600 + mi.toLong() * 60 + s.toLong() - offset) * 1000 + millis)
        }

        private val ISO_INSTANT = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|z|[+-]\d{2}:\d{2})""")
    }
}

/** What the rules need to know about now. The only place a time zone is read. */
internal expect object Clock {
    fun nowMillis(): Long

    /** Today in the device's own zone. */
    fun today(): LocalDate
}

/** The year as ISO 8601 writes it: four digits, a sign only outside 0000..9999. */
private fun yearText(year: Int): String =
    if (year in 0..9999) year.toString().padStart(4, '0') else if (year > 9999) "+$year" else "-" + (-year).toString().padStart(4, '0')
