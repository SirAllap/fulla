// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.core.money.DecimalStyle
import io.github.sirallap.fulla.core.money.MoneyFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What every platform's number formatting must agree on, whatever its locale data says about the rest. */
class MoneyFormatterTest {
    private fun squash(s: String) = s.replace(Regex("[\\s\u00a0\u202f]"), "")

    @Test
    fun amounts_are_written_the_way_the_language_writes_them() {
        assertEquals("$1,234.56", squash(MoneyFormatter("en-US", Currency.require("USD")).format(123456)))
        assertEquals("1.234,56€", squash(MoneyFormatter("de-DE", Currency.require("EUR")).format(123456)))
        assertEquals("1,234.56", MoneyFormatter("en-US", Currency.require("USD")).formatPlain(123456))
        assertEquals("1.234,56", MoneyFormatter("de-DE", Currency.require("EUR")).formatPlain(123456))
    }

    @Test
    fun the_sign_is_a_real_minus_and_money_in_can_carry_a_plus() {
        val f = MoneyFormatter("en-US", Currency.require("USD"))
        assertEquals("−$5.00", f.format(-500))
        assertEquals("+$5.00", f.format(500, signed = true))
        assertEquals("$0.00", f.format(0, signed = true))
        assertEquals("$5.00", f.format(500))
    }

    @Test
    fun a_currency_keeps_its_own_number_of_decimals() {
        assertTrue(squash(MoneyFormatter("en-US", Currency.require("JPY")).format(1235)).endsWith("1,235"))
        assertEquals("1.235", MoneyFormatter("en-US", Currency.require("BHD")).formatPlain(1235))
    }

    @Test
    fun the_decimal_style_follows_the_language() {
        assertEquals(DecimalStyle.DOT, DecimalStyle.forLanguageTag("en-GB"))
        assertEquals(DecimalStyle.COMMA, DecimalStyle.forLanguageTag("es-ES"))
        assertEquals(DecimalStyle.COMMA, DecimalStyle.forLanguageTag("de"))
        assertEquals(DecimalStyle.COMMA, DecimalStyle.forLanguageTag("pt-BR"))
    }
}
