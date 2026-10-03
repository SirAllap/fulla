// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.core.money.Currencies
import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.core.money.DecimalStyle
import io.github.sirallap.fulla.core.money.MoneyFormatter
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth

/** Money and dates, written the way the person's language writes them. */
class Format(val config: Config) {
    val currency: Currency = Currency.of(config.household.currency) ?: Currency(config.household.currency, 2)
    private val tag: String = config.household.locale.ifBlank { browserTag() }
    private val money = MoneyFormatter(tag, currency)

    /** What to assume of a number such as "1.234" when nothing else says: the household's own way of writing. */
    val decimalStyle: DecimalStyle = DecimalStyle.forLanguageTag(tag)

    fun money(minor: Long, signed: Boolean = false): String = money.format(minor, signed)
    fun plain(minor: Long): String = money.formatPlain(minor)

    /** A day for a list: "Tue 3 Oct". */
    fun day(d: LocalDate): String = date(d, "weekday" to "short", "day" to "numeric", "month" to "short")

    fun longDay(d: LocalDate): String = date(d, "weekday" to "long", "day" to "numeric", "month" to "long", "year" to "numeric")

    /** A whole amount, no decimals: the label above a vial. */
    fun whole(minor: Long): String {
        var divisor = 1.0
        repeat(currency.minorUnits) { divisor *= 10 }
        val units = kotlin.math.round(minor / divisor)
        val code = currency.code
        val tag = I18n.language
        return try {
            val options = js("({ style: 'currency', maximumFractionDigits: 0, minimumFractionDigits: 0 })")
            options.currency = code
            js("new Intl.NumberFormat(tag, options)").format(units) as String
        } catch (e: Throwable) { units.toLong().toString() }
    }

    /** The short name of a period's month: "Oct". */
    fun shortPeriod(p: YearMonth): String {
        val month = p.monthValue - 1
        return (js("new Date(2024, month, 1)").toLocaleDateString(I18n.language, js("({ month: 'short' })")) as String).replaceFirstChar { it.titlecase() }.trimEnd('.')
    }

    /** The days a period covers, when they are not a calendar month: "1 Oct – 31 Oct", or "from 25 Sep" while it is open. */
    fun periodRange(view: HouseholdView, p: YearMonth): String? {
        if (view.config.household.periodStartDay == 1 && !view.rule.anchored) return null
        val days = view.rule.daysOf(p)
        fun d(x: LocalDate) = date(x, "day" to "numeric", "month" to "short")
        return if (view.rule.isOpen(p)) t("period_open_from", d(days.start)) else "${d(days.start)} – ${d(days.endInclusive)}"
    }

    /** A month for a title: "October", with the year when it is not this one. */
    fun period(p: YearMonth): String {
        val year = p.year
        val month = p.monthValue - 1
        val name = intl(js("new Date(year, month, 1)"), arrayOf("month" to "long")).replaceFirstChar { it.titlecase() }
        return if (p.year == LocalDate.now().year) name else "$name ${p.year}"
    }

    private fun date(d: LocalDate, vararg options: Pair<String, String>): String {
        val year = d.year
        val month = d.monthValue - 1
        val day = d.dayOfMonth
        val jsDate = js("new Date(year, month, day)")
        return intl(jsDate, arrayOf(*options)).replaceFirstChar { it.titlecase() }
    }

    private fun intl(jsDate: dynamic, options: Array<out Pair<String, String>>): String {
        val opts = js("({})")
        for ((k, v) in options) opts[k] = v
        return try {
            jsDate.toLocaleDateString(I18n.language, opts) as String
        } catch (e: Throwable) {
            jsDate.toLocaleDateString("en", opts) as String
        }
    }

    companion object {
        fun browserTag(): String = (js("navigator.language") as? String) ?: "en"

        /** A currency to propose, from the region of the browser's language (es-MX → MXN). */
        fun proposedCurrency(): String {
            val region = browserTag().substringAfter('-', "").uppercase()
            val byRegion = mapOf(
                "US" to "USD", "GB" to "GBP", "MX" to "MXN", "AR" to "ARS", "CO" to "COP", "CL" to "CLP", "PE" to "PEN",
                "BR" to "BRL", "CA" to "CAD", "AU" to "AUD", "NZ" to "NZD", "CH" to "CHF", "JP" to "JPY", "IN" to "INR",
                "SE" to "SEK", "NO" to "NOK", "DK" to "DKK", "PL" to "PLN", "CZ" to "CZK", "UY" to "UYU", "BO" to "BOB",
                "EC" to "USD", "PA" to "USD", "DO" to "DOP", "GT" to "GTQ", "CR" to "CRC", "VE" to "VES", "PY" to "PYG",
            )
            val euro = setOf("ES", "FR", "DE", "IT", "PT", "NL", "BE", "AT", "IE", "FI", "GR", "LU", "SK", "SI", "EE", "LV", "LT", "MT", "CY", "HR")
            return byRegion[region] ?: if (region in euro || region.isEmpty()) "EUR" else "USD"
        }

        val currencies: List<String> get() = Currencies.MINOR_UNITS.keys.sorted()
    }
}
