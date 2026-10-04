// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.money

actual class MoneyFormatter actual constructor(languageTag: String, private val money: Currency) {

    // Plain locals: js() sees a local by its own name, never a property.
    private val tag: String = languageTag

    private val withSymbol: dynamic = try {
        val languageTag = tag
        val options = js("({})")
        options.style = "currency"
        options.currency = money.code
        options.minimumFractionDigits = money.minorUnits
        options.maximumFractionDigits = money.minorUnits
        // Spanish, Italian and European Portuguese leave four-digit numbers ungrouped by default (1467,61); the phone's
        // formatter groups them (1.467,61), and one amount must read the same on both.
        options.useGrouping = "always"
        js("new Intl.NumberFormat(languageTag, options)")
    } catch (e: Throwable) {
        null
    }

    private val plain: dynamic = run {
        val languageTag = tag
        val options = js("({})")
        options.minimumFractionDigits = money.minorUnits
        options.maximumFractionDigits = money.minorUnits
        options.useGrouping = "always"
        js("new Intl.NumberFormat(languageTag, options)")
    }

    actual fun format(minor: Long, signed: Boolean): String {
        val format = withSymbol ?: return "${money.code} ${formatPlain(minor, signed)}"
        return signedText(render(format, minor), minor, signed)
    }

    actual fun formatPlain(minor: Long, signed: Boolean): String = signedText(render(plain, minor), minor, signed)

    private fun render(format: dynamic, minor: Long): String {
        val size = if (minor < 0) -minor else minor
        var divisor = 1.0
        repeat(money.minorUnits) { divisor *= 10 }
        return format.format(size.toDouble() / divisor) as String
    }
}

internal actual fun decimalSeparatorFor(languageTag: String): Char {
    val sample = js("new Intl.NumberFormat(languageTag).format(1.5)") as String
    return sample.firstOrNull { !it.isDigit() } ?: '.'
}
