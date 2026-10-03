// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.keypad

import io.github.sirallap.fulla.core.money.Currency

/**
 * The amount keypad as a pure state machine: digits, a decimal key, delete, and
 * "+" to add several receipts together. It works in the currency's minor units
 * from the start, so it cannot produce an amount the currency cannot hold: no
 * decimal key for JPY, three decimals for BHD.
 *
 * [entry] is what is being typed, with '.' as the decimal point internally;
 * the screen shows it with the locale's separator.
 */
data class Keypad(
    val currency: Currency,
    val entry: String = "",
    val addends: List<Long> = emptyList(),
) {
    val hasDecimalKey: Boolean get() = currency.minorUnits > 0

    /** The entry being typed, in minor units, or 0. */
    val entryMinor: Long
        get() {
            if (entry.isEmpty() || entry == ".") return 0
            val (whole, frac) = entry.split('.').let { it[0] to it.getOrElse(1) { "" } }
            val wholePart = (whole.ifEmpty { "0" }).toLong()
            val fracPart = frac.padEnd(currency.minorUnits, '0').ifEmpty { "0" }.toLong()
            return wholePart * pow10(currency.minorUnits) + fracPart
        }

    val subtotalMinor: Long get() = addends.sum()
    val totalMinor: Long get() = subtotalMinor + entryMinor
    val isAdding: Boolean get() = addends.isNotEmpty()
    val canSave: Boolean get() = totalMinor > 0
    val isEmpty: Boolean get() = addends.isEmpty() && entryMinor == 0L

    fun digit(d: Char): Keypad {
        if (d !in '0'..'9') return this
        val parts = entry.split('.')
        if (parts.size > 1 && parts[1].length >= currency.minorUnits) return this
        if (parts.size == 1 && parts[0].length >= MAX_WHOLE_DIGITS) return this
        return copy(entry = if (entry == "0") d.toString() else entry + d)
    }

    fun decimal(): Keypad {
        if (!hasDecimalKey || entry.contains('.')) return this
        return copy(entry = if (entry.isEmpty()) "0." else "$entry.")
    }

    /** Deletes a character; with nothing typed, brings back the last addend to edit. */
    fun delete(): Keypad = when {
        entry.isNotEmpty() -> copy(entry = entry.dropLast(1))
        addends.isNotEmpty() -> copy(entry = toEntry(addends.last()), addends = addends.dropLast(1))
        else -> this
    }

    /** Puts the current entry aside and starts the next one. */
    fun plus(): Keypad {
        val current = entryMinor
        if (current == 0L) return this
        return copy(entry = "", addends = addends + current)
    }

    fun clear(): Keypad = Keypad(currency)

    fun withAmount(minor: Long): Keypad = Keypad(currency, entry = toEntry(minor))

    private fun toEntry(minor: Long): String {
        val scale = pow10(currency.minorUnits)
        val whole = minor / scale
        val frac = minor % scale
        if (currency.minorUnits == 0 || frac == 0L) return whole.toString()
        return "$whole." + frac.toString().padStart(currency.minorUnits, '0').trimEnd('0')
    }

    companion object {
        const val MAX_WHOLE_DIGITS = 12
        private fun pow10(n: Int): Long = (0 until n).fold(1L) { acc, _ -> acc * 10 }
    }
}
