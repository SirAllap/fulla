// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.core.money.DecimalStyle
import io.github.sirallap.fulla.core.money.MoneyParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTypedTest {
    private val eur = Currency.require("EUR")
    private val jpy = Currency.require("JPY")

    private fun typed(text: String, fallback: DecimalStyle = DecimalStyle.DOT, currency: Currency = eur) =
        MoneyParser.parseTyped(text, currency, fallback)

    @Test
    fun `the decimal key decides whatever the language`() {
        assertEquals(1250L, typed("12,5"))
        assertEquals(1250L, typed("12,5", DecimalStyle.COMMA))
        assertEquals(1250L, typed("12.5", DecimalStyle.COMMA))
        assertEquals(1250L, typed("12.50"))
        assertEquals(123456L, typed("1.234,56"))
        assertEquals(123456L, typed("1,234.56"))
        assertEquals(123450L, typed("1 234,5"))
        assertEquals(5L, typed(",05"))
        assertEquals(1200L, typed("12"))
    }

    @Test
    fun `a group of three digits is left to the fallback`() {
        assertEquals(123400L, typed("1.234", DecimalStyle.COMMA))
        assertEquals(123400L, typed("1,234", DecimalStyle.DOT))
        assertEquals(123L, typed("1.234", DecimalStyle.DOT))
        assertEquals(1L, typed("1.234", DecimalStyle.DOT, jpy), "a yen has no decimals: 1.234 is one and a bit, rounded")
    }

    @Test
    fun `what is not an amount is not one`() {
        assertNull(typed(""))
        assertNull(typed("abc"))
        assertNull(typed("1.2.3"))
        assertNull(typed("12,5,5"))
    }
}
