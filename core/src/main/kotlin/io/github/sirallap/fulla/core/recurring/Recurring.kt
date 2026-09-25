// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.recurring

import io.github.sirallap.fulla.core.model.Transaction
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class Frequency(val key: String) {
    DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly"), YEARLY("yearly");

    companion object {
        fun of(key: String): Frequency? = entries.firstOrNull { it.key == key }
    }
}

/**
 * When a recurring item falls due.
 *
 * - [byWeekday]: weekly only, 1 = Monday … 7 = Sunday.
 * - [byMonthDay]: monthly and yearly; -1 is the last day of the month, and a
 *   day past the end of a month falls on its last day (31 → 30 April).
 * - [byMonth]: yearly only.
 */
data class Schedule(
    val frequency: Frequency,
    val interval: Int = 1,
    val byWeekday: List<Int> = emptyList(),
    val byMonthDay: Int? = null,
    val byMonth: Int? = null,
) {
    init {
        require(interval in 1..365) { "interval must be 1..365" }
        when (frequency) {
            Frequency.WEEKLY -> require(byWeekday.isNotEmpty() && byWeekday.all { it in 1..7 }) { "weekly needs byWeekday 1..7" }
            Frequency.MONTHLY -> require(byMonthDay != null && byMonthDay != 0 && byMonthDay in -1..31) { "monthly needs byMonthDay" }
            Frequency.YEARLY -> require(byMonthDay != null && byMonthDay != 0 && byMonthDay in -1..31 && byMonth in 1..12) {
                "yearly needs byMonthDay and byMonth"
            }
            Frequency.DAILY -> Unit
        }
    }
}

/** A transaction that repeats. [template] carries everything but id, date and status. */
data class RecurringRule(
    val id: String,
    val name: String,
    val template: Transaction,
    val schedule: Schedule,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val autoCreate: Boolean = false,
    val active: Boolean = true,
)

object Scheduler {

    /** Every date the rule falls due on within [from]..[to], in order. */
    fun occurrences(rule: RecurringRule, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (!rule.active) return emptyList()
        val first = maxOf(from, rule.startDate)
        val last = rule.endDate?.let { minOf(it, to) } ?: to
        if (first > last) return emptyList()
        val s = rule.schedule
        val out = mutableListOf<LocalDate>()
        when (s.frequency) {
            Frequency.DAILY -> {
                var d = rule.startDate
                while (d <= last) {
                    if (d >= first) out += d
                    d = d.plusDays(s.interval.toLong())
                }
            }
            Frequency.WEEKLY -> {
                val weekOfStart = rule.startDate.with(DayOfWeek.MONDAY)
                var d = maxOf(first, rule.startDate)
                while (d <= last) {
                    val weeks = ChronoUnit.WEEKS.between(weekOfStart, d.with(DayOfWeek.MONDAY))
                    if (weeks % s.interval == 0L && d.dayOfWeek.value in s.byWeekday) out += d
                    d = d.plusDays(1)
                }
            }
            Frequency.MONTHLY, Frequency.YEARLY -> {
                val step = if (s.frequency == Frequency.MONTHLY) s.interval.toLong() else 12L * s.interval
                var month = YearMonth.from(rule.startDate)
                if (s.frequency == Frequency.YEARLY) {
                    month = month.withMonth(s.byMonth!!)
                    if (month.atEndOfMonth() < rule.startDate) month = month.plusYears(1)
                }
                while (month.atDay(1) <= last) {
                    val d = dayIn(month, s.byMonthDay!!)
                    if (d in first..last && d >= rule.startDate) out += d
                    month = month.plusMonths(step)
                }
            }
        }
        return out
    }

    private fun dayIn(month: YearMonth, day: Int): LocalDate =
        if (day == -1 || day > month.lengthOfMonth()) month.atEndOfMonth() else month.atDay(day)
}

/**
 * Deterministic ids, so that two phones producing the same thing produce the
 * same row: a recurring occurrence (rule id + date) or an imported line
 * (household id + fingerprint). The sync then merges them instead of
 * duplicating them.
 */
object DeterministicId {

    fun occurrence(ruleId: String, date: LocalDate): String = uuid5(UUID.fromString(ruleId), date.toString())

    fun imported(householdId: String, fingerprint: String): String = uuid5(UUID.fromString(householdId), fingerprint)

    /** RFC 4122 version 5 (SHA-1, name-based). */
    fun uuid5(namespace: UUID, name: String): String {
        val ns = ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array()
        val hash = MessageDigest.getInstance("SHA-1").apply {
            update(ns)
            update(name.toByteArray(Charsets.UTF_8))
        }.digest()
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val buf = ByteBuffer.wrap(hash, 0, 16)
        return UUID(buf.long, buf.long).toString()
    }
}
