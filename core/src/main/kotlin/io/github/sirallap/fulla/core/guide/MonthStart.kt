// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.guide

import io.github.sirallap.fulla.core.model.Household
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.rules.PeriodRule
import java.time.LocalDate
import java.time.YearMonth

/** What [MonthStart.preview] shows: the period around `today`, and where a salary would land. */
data class MonthPreview(val label: YearMonth, val days: ClosedRange<LocalDate>, val salaryExample: Pair<LocalDate, YearMonth>)

/**
 * The three ways a household can choose when its month starts, offered in
 * the setup guide as one question instead of [PeriodRule]'s two independent
 * fields. Mirrors `fulla_household_update`'s own validation
 * (`supabase/migrations/0005_household.sql`): a period that starts mid-month
 * and shifted income exclude each other, so only one of the two fields is
 * ever set.
 */
sealed class MonthStart {
    /** The plain calendar month. */
    data object Calendar : MonthStart()

    /** The period runs from [day] of one month to [day] - 1 of the next. */
    data class Payday(val day: Int) : MonthStart() {
        init {
            require(day in 2..28) { "day must be 2..28" }
        }
    }

    /**
     * Calendar months, but fixed income on or after [day] counts for the next
     * one. Unlike [Payday], 1 is a real, if unusual, choice here: it means
     * every fixed income shifts forward, and `PeriodRule`/the database allow
     * it (`income_shift_day between 1 and 31`), so it is never rejected — the
     * setup guide's own stepper just never offers it (it starts at 2).
     */
    data class SalaryNextMonth(val day: Int) : MonthStart() {
        init {
            require(day in 1..31) { "day must be 1..31" }
        }
    }

    /** The matching [PeriodRule]. */
    fun toRule(): PeriodRule = when (this) {
        Calendar -> PeriodRule()
        is Payday -> PeriodRule(periodStartDay = day)
        is SalaryNextMonth -> PeriodRule(incomeShiftDay = day)
    }

    /**
     * The patch `fulla_household_update` (and the client's
     * `LocalHousehold.updateHousehold`) accept: both `period_start_day` and
     * `income_shift_day` are always present, one of them explicitly `null`,
     * so a patch never leaves the other field at whatever it used to be.
     */
    fun toPatch(): Map<String, Any?> = when (this) {
        Calendar -> mapOf("period_start_day" to 1, "income_shift_day" to null)
        is Payday -> mapOf("period_start_day" to day, "income_shift_day" to null)
        is SalaryNextMonth -> mapOf("period_start_day" to 1, "income_shift_day" to day)
    }

    /**
     * The period [today] falls in, the calendar days it covers, and where a
     * salary dated on this choice's boundary day would land: the date it
     * would be paid this month, and the period it is counted into. For
     * [Calendar] the boundary day is the 1st, which never moves anything, so
     * the example simply confirms that.
     */
    fun preview(today: LocalDate): MonthPreview {
        val rule = toRule()
        val label = rule.periodOf(today, TransactionKind.EXPENSE, Recurrence.VARIABLE)
        val boundaryDay = when (this) {
            Calendar -> 1
            is Payday -> day
            is SalaryNextMonth -> day
        }
        val month = YearMonth.from(today)
        val salaryDate = month.atDay(minOf(boundaryDay, month.lengthOfMonth()))
        val salaryPeriod = rule.periodOf(salaryDate, TransactionKind.INCOME, Recurrence.FIXED)
        return MonthPreview(label, rule.daysOf(label), salaryDate to salaryPeriod)
    }

    companion object {
        /** Reads a household's current choice off its two [PeriodRule] fields. */
        fun of(household: Household): MonthStart = when {
            household.incomeShiftDay != null -> SalaryNextMonth(household.incomeShiftDay)
            household.periodStartDay > 1 -> Payday(household.periodStartDay)
            else -> Calendar
        }
    }
}
