// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.rules

import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.TransactionKind
import java.time.LocalDate
import java.time.YearMonth

/**
 * The period (YYYY-MM) a transaction counts in.
 *
 * By default the calendar month. A household can choose instead:
 *
 * - [periodStartDay] S from 2 to 28: a period runs from day S of one month to
 *   day S-1 of the next and is named after the month it ends in. With S = 20,
 *   20 March to 19 April is "April".
 * - [incomeShiftDay] D, only with calendar months: fixed income dated on or
 *   after day D counts in the next month, for a salary paid at the end of a
 *   month to fund the next one. Only income moves, and only fixed income.
 *
 * The same rule is fulla.period_of in the database. Both pass
 * testdata/vectors/period.json.
 */
data class PeriodRule(val periodStartDay: Int = 1, val incomeShiftDay: Int? = null) {

    init {
        require(periodStartDay in 1..28) { "periodStartDay must be 1..28" }
        require(incomeShiftDay == null || incomeShiftDay in 1..31) { "incomeShiftDay must be 1..31" }
        require(periodStartDay == 1 || incomeShiftDay == null) { "A mid-month period and shifted income exclude each other" }
    }

    fun periodOf(date: LocalDate, kind: TransactionKind, recurrence: Recurrence): YearMonth {
        val month = YearMonth.from(date)
        val shifts = when {
            periodStartDay > 1 -> date.dayOfMonth >= periodStartDay
            incomeShiftDay != null -> kind == TransactionKind.INCOME &&
                recurrence == Recurrence.FIXED && date.dayOfMonth >= incomeShiftDay
            else -> false
        }
        return if (shifts) month.plusMonths(1) else month
    }

    fun label(date: LocalDate, kind: TransactionKind, recurrence: Recurrence): String =
        periodOf(date, kind, recurrence).toString()

    /** The calendar days a period covers, first and last inclusive. */
    fun daysOf(period: YearMonth): ClosedRange<LocalDate> =
        if (periodStartDay == 1) {
            period.atDay(1)..period.atEndOfMonth()
        } else {
            period.minusMonths(1).atDay(periodStartDay)..period.atDay(periodStartDay - 1)
        }
}
