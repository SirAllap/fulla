// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.money

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

actual class MoneyFormatter actual constructor(languageTag: String, private val money: Currency) {

    constructor(locale: Locale, money: Currency) : this(locale.toLanguageTag(), money)

    private val locale: Locale = Locale.forLanguageTag(languageTag)

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

    actual fun format(minor: Long, signed: Boolean): String = render(withSymbol, minor, signed)

    actual fun formatPlain(minor: Long, signed: Boolean): String = render(plain, minor, signed)

    private fun render(format: NumberFormat, minor: Long, signed: Boolean): String {
        val text = format.format(BigDecimal.valueOf(minor.let { if (it < 0) -it else it }, money.minorUnits))
        return signedText(text, minor, signed)
    }
}

internal actual fun decimalSeparatorFor(languageTag: String): Char =
    DecimalFormatSymbols.getInstance(Locale.forLanguageTag(languageTag)).decimalSeparator

/** The decimal style a java.util.Locale writes numbers in. */
fun DecimalStyle.Companion.of(locale: Locale): DecimalStyle = forLanguageTag(locale.toLanguageTag())
