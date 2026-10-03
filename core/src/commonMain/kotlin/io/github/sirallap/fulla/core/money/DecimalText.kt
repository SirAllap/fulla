// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.money

/** Decimal numbers as text, with no binary floating point anywhere. */
object DecimalText {

    private val SHAPE = Regex("^([+-]?)(\\d*)(?:\\.(\\d*))?(?:[eE]([+-]?\\d+))?$")

    /**
     * The number written plainly and without trailing zeros: `12.50` → `12.5`,
     * `100` → `100`, `1.0E3` → `1000`, `0.0` → `0`. Null if it is not a number.
     */
    fun plain(text: String): String? {
        val m = SHAPE.matchEntire(text.trim()) ?: return null
        val intPart = m.groupValues[2]
        val fraction = m.groupValues[3]
        if (intPart.isEmpty() && fraction.isEmpty()) return null
        val exponent = m.groupValues[4].ifEmpty { "0" }.toIntOrNull() ?: return null
        var digits = (intPart + fraction).trimStart('0')
        var pointAt = intPart.length + exponent - (intPart + fraction).length + digits.length
        // pointAt: position of the decimal point counted from the start of `digits`.
        if (digits.isEmpty()) return "0"
        digits = digits.trimEnd('0')
        val body = when {
            pointAt <= 0 -> "0." + "0".repeat(-pointAt) + digits
            pointAt >= digits.length -> digits + "0".repeat(pointAt - digits.length)
            else -> digits.substring(0, pointAt) + "." + digits.substring(pointAt)
        }
        return (if (m.groupValues[1] == "-") "-" else "") + body
    }
}
