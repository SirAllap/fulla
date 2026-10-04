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

    /**
     * Spanish, Italian and European Portuguese leave four-digit numbers ungrouped in a browser's data and group them
     * on the phone: the same amount must read the same on both, so these lines hold on every platform. Spaces are
     * marked: "_" is a no-break space, "~" a narrow one.
     */
    @Test
    fun the_same_amount_reads_the_same_on_every_platform() {
        val amounts = listOf(0L, 5L, 99_999L, 100_000L, 146_761L, 123_456_789L, -146_761L)
        val expected = mapOf(
            "es-ES" to listOf("0,00_€", "0,05_€", "999,99_€", "1.000,00_€", "1.467,61_€", "1.234.567,89_€", "−1.467,61_€"),
            "it-IT" to listOf("0,00_€", "0,05_€", "999,99_€", "1.000,00_€", "1.467,61_€", "1.234.567,89_€", "−1.467,61_€"),
            "de-DE" to listOf("0,00_€", "0,05_€", "999,99_€", "1.000,00_€", "1.467,61_€", "1.234.567,89_€", "−1.467,61_€"),
            "pt-PT" to listOf("0,00_€", "0,05_€", "999,99_€", "1_000,00_€", "1_467,61_€", "1_234_567,89_€", "−1_467,61_€"),
            "fr-FR" to listOf("0,00_€", "0,05_€", "999,99_€", "1~000,00_€", "1~467,61_€", "1~234~567,89_€", "−1~467,61_€"),
            "pt-BR" to listOf("€_0,00", "€_0,05", "€_999,99", "€_1.000,00", "€_1.467,61", "€_1.234.567,89", "−€_1.467,61"),
            "en-GB" to listOf("€0.00", "€0.05", "€999.99", "€1,000.00", "€1,467.61", "€1,234,567.89", "−€1,467.61"),
        )
        for ((tag, lines) in expected) {
            val f = MoneyFormatter(tag, Currency.require("EUR"))
            assertEquals(lines, amounts.map { f.format(it).replace('\u00a0', '_').replace('\u202f', '~') }, tag)
        }
        assertEquals("+1.234,56_€", MoneyFormatter("es-ES", Currency.require("EUR")).format(123_456, signed = true).replace('\u00a0', '_'))
        assertEquals("1.467,61", MoneyFormatter("es-ES", Currency.require("EUR")).formatPlain(146_761))
    }
}
