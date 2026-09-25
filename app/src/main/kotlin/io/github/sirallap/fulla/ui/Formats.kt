// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui

import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.core.money.DecimalStyle
import io.github.sirallap.fulla.core.money.MoneyFormatter
import io.github.sirallap.fulla.core.rules.PeriodRule
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * How one household's numbers and dates are written: its currency, in the
 * phone's language. Built once per config and handed down.
 */
class Formats(val config: Config, val locale: Locale = Locale.getDefault()) {
    val currency: Currency = Currency.of(config.household.currency) ?: Currency("XXX", 2)
    private val money = MoneyFormatter(locale, currency)
    val decimalStyle: DecimalStyle = DecimalStyle.of(locale)
    val periodRule = PeriodRule(config.household.periodStartDay, config.household.incomeShiftDay)
    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val longDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)

    fun money(minor: Long, signed: Boolean = false): String = money.format(minor, signed)
    fun plain(minor: Long): String = money.formatPlain(minor)

    fun period(p: YearMonth): String {
        val month = p.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
        return if (p.year == YearMonth.now().year) month else "$month ${p.year}"
    }

    /** The period's dates, when it does not start on the 1st. */
    fun periodRange(p: YearMonth): String? {
        if (config.household.periodStartDay == 1) return null
        val days = periodRule.daysOf(p)
        return "${dayFormat.format(days.start)} – ${dayFormat.format(days.endInclusive)}"
    }

    fun day(d: LocalDate): String = dayFormat.format(d).replaceFirstChar { it.titlecase(locale) }
    fun longDay(d: LocalDate): String = longDate.format(d)

    fun currentPeriod(today: LocalDate = LocalDate.now()): YearMonth = periodRule.daysOfContaining(today)

    companion object {
        /** The currency a new household is proposed, from the phone's region. */
        fun proposedCurrency(locale: Locale = Locale.getDefault()): String =
            runCatching { java.util.Currency.getInstance(locale).currencyCode }.getOrNull() ?: "EUR"
    }
}

/** The period whose days contain [date], for ordinary (non-shifted) rows. */
private fun PeriodRule.daysOfContaining(date: LocalDate): YearMonth =
    periodOf(date, io.github.sirallap.fulla.core.model.TransactionKind.EXPENSE, io.github.sirallap.fulla.core.model.Recurrence.VARIABLE)
