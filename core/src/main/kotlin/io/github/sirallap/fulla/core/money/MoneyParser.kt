// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Which character separates the decimals. The other of `.` and `,` is taken
 * as a grouping separator, along with spaces and apostrophes. It is always
 * stated, never guessed: "1.234" is a thousand and more in one country and
 * one and a bit in another.
 */
enum class DecimalStyle(val decimal: Char, val grouping: Char) {
    DOT('.', ','),
    COMMA(',', '.');

    companion object {
        fun of(locale: java.util.Locale): DecimalStyle =
            if (java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator == ',') COMMA else DOT
    }
}

/**
 * Text → minor units. The one place in Fulla where a decimal number becomes an
 * amount, used by the keypad, custom money fields and every importer.
 *
 * Rounding is half away from zero on the decimal text itself, never through a
 * binary double: "0.125" in a two-decimal currency is 13, "-0.125" is -13.
 */
object MoneyParser {

    private val CLEAN = Regex("[\\s  '’\\p{Sc}A-Za-z]")
    private val NUMBER = Regex("^\\d+(\\.\\d+)?$")

    /** Minor units, or null if the text is not an amount. */
    fun parse(text: String, currency: Currency, style: DecimalStyle): Long? {
        var s = text.trim()
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1)
        }
        s = s.replace(CLEAN, "")
        when {
            s.startsWith("-") || s.startsWith("−") -> { negative = !negative; s = s.substring(1) }
            s.endsWith("-") || s.endsWith("−") -> { negative = !negative; s = s.dropLast(1) }
            s.startsWith("+") -> s = s.substring(1)
        }
        s = s.replace(style.grouping.toString(), "").replace(style.decimal, '.')
        if (!NUMBER.matches(s)) return null
        return try {
            val value = BigDecimal(s).setScale(currency.minorUnits, RoundingMode.HALF_UP)
            val minor = value.unscaledValue().longValueExact()
            if (negative) -minor else minor
        } catch (e: ArithmeticException) {
            null
        }
    }
}
