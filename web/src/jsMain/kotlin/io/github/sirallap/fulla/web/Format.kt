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
