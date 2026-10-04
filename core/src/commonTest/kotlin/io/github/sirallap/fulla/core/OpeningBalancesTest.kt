// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.guide.OpeningBalances
import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.core.money.DecimalStyle
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What an account is said to hold when it starts, as typed: a debt is a minus sign, and text that is not an amount is not kept as one. */
class OpeningBalancesTest {
    private val eur = Currency.require("EUR")

    private fun typed(text: String, style: DecimalStyle = DecimalStyle.COMMA) = OpeningBalances.parse(text, eur, style)

    @Test
    fun a_blank_field_is_zero() {
        assertEquals(0L, typed(""))
        assertEquals(0L, typed("   "))
    }

    @Test
    fun a_minus_sign_is_a_debt() {
        assertEquals(-30_000L, typed("-300"))
        assertEquals(-30_050L, typed("−300,50"))
        assertEquals(-30_050L, typed("(300,50)"))
        assertEquals(30_000L, typed("+300"))
    }

    @Test
    fun the_debt_switch_is_the_same_as_a_minus_sign_and_never_cancels_it() {
        // A phone's decimal keypad has no minus key: the switch says it.
        assertEquals(-30_000L, OpeningBalances.parse("300", eur, DecimalStyle.COMMA, debt = true))
        assertEquals(-30_000L, OpeningBalances.parse("-300", eur, DecimalStyle.COMMA, debt = true))
        assertEquals(0L, OpeningBalances.parse("", eur, DecimalStyle.COMMA, debt = true))
        assertEquals(30_000L, OpeningBalances.parse("300", eur, DecimalStyle.COMMA, debt = false))
        assertNull(OpeningBalances.parse("abc", eur, DecimalStyle.COMMA, debt = true))
    }

    @Test
    fun a_dot_or_a_comma_is_the_decimal_whatever_the_language() {
        // 12.50 typed on a keyboard whose decimal key is the dot, in an app that writes commas:
        // twelve and a half, not a thousand two hundred and fifty.
        assertEquals(1_250L, typed("12.50", DecimalStyle.COMMA))
        assertEquals(1_250L, typed("12,50", DecimalStyle.DOT))
        assertEquals(123_456L, typed("1.234,56", DecimalStyle.DOT))
        assertEquals(123_456L, typed("1,234.56", DecimalStyle.COMMA))
    }

    @Test
    fun text_that_is_not_an_amount_is_not_kept_as_one() {
        assertNull(typed("abc"))
        assertNull(typed("1.2.3"))
        assertNull(typed("--5"))
    }

    @Test
    fun a_card_in_debt_brings_the_total_of_the_accounts_down() {
        val config = Fixtures.config()
        val card = Account("00000000-0000-4000-8000-000000000203", "Card", AccountType.CREDIT_CARD,
            openingBalanceMinor = typed("-300")!!, openingBalanceDate = LocalDate.of(2030, 1, 1))
        val accounts = (config.accounts + card).map { if (it.id == Fixtures.MAIN) OpeningBalances.patch(it, typed("1000")!!) else it }
        val onTheCard = Fixtures.expense(amount = 2_500, date = LocalDate.of(2030, 1, 10)).copy(accountId = card.id)
        val total = Analytics(config, PeriodRule()).accountsTotal(listOf(onTheCard), accounts, LocalDate.of(2030, 1, 31))
        assertEquals(100_000L - 30_000 - 2_500, total)
        assertEquals(-30_000L, OpeningBalances.patch(card, -30_000L).openingBalanceMinor)
    }
}
