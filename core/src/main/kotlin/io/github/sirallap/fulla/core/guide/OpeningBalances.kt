// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.guide

import io.github.sirallap.fulla.core.model.Account

/** Setting an account's opening balance, the guide's second setup step. */
object OpeningBalances {
    /** [account] with its opening balance set to [minor], its currency's minor units, never negative. */
    fun patch(account: Account, minor: Long): Account {
        require(minor >= 0) { "minor must not be negative" }
        return account.copy(openingBalanceMinor = minor)
    }
}
