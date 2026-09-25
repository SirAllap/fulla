// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.money

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Minor units → text, the way the locale writes money: symbol and position,
 * grouping, decimal separator, and exactly the currency's number of decimals.
 *
 * Negative amounts use the real minus sign (U+2212). With [signed], positive
 * amounts carry a "+", so that money coming in is never marked by colour alone.
 *
 * This is the JVM implementation, used by tests and exports. java.text only
 * knows uniform grouping, so locales that group unevenly (en-IN: 1,23,456.78)
 * come out grouped by thousands; the app formats on screen through
 * android.icu, which follows CLDR fully, and must keep the same sign rules.
 */
class MoneyFormatter(private val locale: Locale, private val money: Currency) {

    private val withSymbol: NumberFormat = NumberFormat.getCurrencyInstance(locale).apply {
        val jdk = runCatching { java.util.Currency.getInstance(money.code) }.getOrNull()
        if (jdk != null) this.currency = jdk
        minimumFractionDigits = money.minorUnits
        maximumFractionDigits = money.minorUnits
    }

    private val plain: NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = money.minorUnits
        maximumFractionDigits = money.minorUnits
        isGroupingUsed = true
    }

    fun format(minor: Long, signed: Boolean = false): String = render(withSymbol, minor, signed)

    /** The number alone, for columns that print the currency once in a header. */
    fun formatPlain(minor: Long, signed: Boolean = false): String = render(plain, minor, signed)

    private fun render(format: NumberFormat, minor: Long, signed: Boolean): String {
        val text = format.format(BigDecimal.valueOf(minor.let { if (it < 0) -it else it }, money.minorUnits))
        return when {
            minor < 0 -> "−$text"
            signed && minor > 0 -> "+$text"
            else -> text
        }
    }
}
