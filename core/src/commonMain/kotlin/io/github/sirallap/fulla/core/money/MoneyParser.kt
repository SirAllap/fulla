// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.money

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
        /** The style a language tag (`es-ES`, `en`) writes numbers in. */
        fun forLanguageTag(tag: String): DecimalStyle = if (decimalSeparatorFor(tag) == ',') COMMA else DOT
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

    /**
     * What a person typed on a phone's keyboard, whose decimal key follows the
     * phone's region and not the app's language: the separator is whichever of
     * `.` and `,` comes last with one or two digits after it ("12,5", "1.234,56",
     * "1,234.5"). When nothing says ("1.234", "1,234": a thousand, or one and a
     * bit?), [fallback] decides.
     */
    fun parseTyped(text: String, currency: Currency, fallback: DecimalStyle): Long? {
        val trimmed = text.trim()
        val s = if (trimmed.startsWith(".") || trimmed.startsWith(",")) "0$trimmed" else trimmed
        val at = s.lastIndexOfAny(charArrayOf('.', ','))
        val style = if (at >= 0 && s.length - at - 1 in 1..2 && s.substring(at + 1).all { it in '0'..'9' }) {
            if (s[at] == ',') DecimalStyle.COMMA else DecimalStyle.DOT
        } else fallback
        return parse(s, currency, style)
    }

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
        val whole = s.substringBefore('.')
        val fraction = s.substringAfter('.', "")
        val units = currency.minorUnits
        val kept = fraction.take(units).padEnd(units, '0')
        var minor = (whole + kept).toLongOrNull() ?: return null
        // Half away from zero, on the digits themselves: the first dropped digit decides.
        if (fraction.length > units && fraction[units] >= '5') {
            if (minor == Long.MAX_VALUE) return null
            minor++
        }
        return if (negative) -minor else minor
    }
}
