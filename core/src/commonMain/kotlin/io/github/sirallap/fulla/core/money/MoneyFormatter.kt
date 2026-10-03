// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.money

/**
 * Minor units → text, the way the language writes money: symbol and position,
 * grouping, decimal separator, and exactly the currency's number of decimals.
 *
 * Negative amounts use the real minus sign (U+2212). With `signed`, positive
 * amounts carry a "+", so that money coming in is never marked by colour alone.
 *
 * Each platform brings its own number rules: the JVM's java.text (which only
 * knows uniform grouping: en-IN comes out 123,456.78, not 1,23,456.78), the
 * phone's ICU, a browser's Intl. The sign rules are the same on all of them.
 */
expect class MoneyFormatter(languageTag: String, money: Currency) {
    fun format(minor: Long, signed: Boolean = false): String

    /** The number alone, for columns that print the currency once in a header. */
    fun formatPlain(minor: Long, signed: Boolean = false): String
}

/** The sign in front of text already formatted from the amount's size. */
internal fun signedText(text: String, minor: Long, signed: Boolean): String = when {
    minor < 0 -> "−$text"
    signed && minor > 0 -> "+$text"
    else -> text
}

internal expect fun decimalSeparatorFor(languageTag: String): Char
