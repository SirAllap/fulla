// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.keypad.Keypad
import io.github.sirallap.fulla.core.money.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeypadTest {
    private fun type(k: Keypad, keys: String): Keypad = keys.fold(k) { acc, c ->
        when (c) { '.' -> acc.decimal(); '+' -> acc.plus(); '<' -> acc.delete(); else -> acc.digit(c) }
    }
    private val eur = Keypad(Currency.require("EUR"))

    @Test
    fun `typing an amount`() {
        assertEquals(1234L, type(eur, "12.34").totalMinor)
        assertEquals(1230L, type(eur, "12.3").totalMinor)
        assertEquals(1234L, type(eur, "12.345").totalMinor, "a third decimal is ignored")
        assertEquals(50L, type(eur, ".5").totalMinor)
        assertEquals(700L, type(eur, "07").totalMinor)
        assertFalse(eur.canSave)
    }

    @Test
    fun `adding receipts`() {
        val k = type(eur, "4.50+2.25+1")
        assertEquals(775L, k.totalMinor)
        assertEquals(675L, k.subtotalMinor)
        assertTrue(k.isAdding)
        val back = type(k, "<<")
        assertEquals("2.25", back.entry, "deleting past the entry brings back the last addend")
        assertEquals(675L, back.totalMinor)
    }

    @Test
    fun `currencies without decimals and with three`() {
        val jpy = Keypad(Currency.require("JPY"))
        assertFalse(jpy.hasDecimalKey)
        assertEquals(1500L, type(jpy, "1.500").totalMinor)
        assertEquals(1235L, type(Keypad(Currency.require("BHD")), "1.2356").totalMinor)
    }

    @Test
    fun `an amount can be loaded for editing`() {
        assertEquals("12.3", eur.withAmount(1230).entry)
        assertEquals("12", eur.withAmount(1200).entry)
        assertEquals(1230L, eur.withAmount(1230).totalMinor)
        assertEquals(12, type(eur, "9".repeat(20)).entry.length, "at most twelve whole digits")
    }
}
