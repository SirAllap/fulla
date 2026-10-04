// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.guide

import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.core.money.DecimalStyle
import io.github.sirallap.fulla.core.money.MoneyParser
import kotlin.math.abs

/** Setting an account's opening balance, the guide's second setup step. */
object OpeningBalances {
    /**
     * What was typed in an opening-balance field, in minor units. A blank field
     * is 0. An account [debt] (a credit card, an overdraft) holds money owed, so
     * it is the amount below zero; a minus sign typed in the field says the same
     * (a phone's decimal keypad has no minus key, hence the switch). Text that
     * is not an amount is null, so the screen can say so instead of keeping
     * whatever balance was there as if the person had typed it. Either decimal
     * key is the decimal, whatever language the app is in.
     */
    fun parse(text: String, currency: Currency, style: DecimalStyle, debt: Boolean = false): Long? {
        val typed = if (text.isBlank()) 0L else MoneyParser.parseTyped(text, currency, style) ?: return null
        return if (debt) -abs(typed) else typed
    }

    /** [account] with its opening balance set to [minor], its currency's minor units; negative when the account is in debt. */
    fun patch(account: Account, minor: Long): Account = account.copy(openingBalanceMinor = minor)
}
