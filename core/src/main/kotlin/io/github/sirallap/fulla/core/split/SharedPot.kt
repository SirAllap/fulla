// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.split

import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Household
import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.MoneyMode
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.roles.Permissions

/**
 * One shared pot: a household whose money all goes in together, so nobody
 * owes anybody.
 *
 *   expense, refund   split between the payer alone: all of it is their own
 *                     share, and under the unchanged balance rule it moves
 *                     nothing. Who paid stays, as information.
 *   settlement        refused: there is nothing to settle.
 *   income, transfer  unchanged; they never moved a balance.
 *
 * Only for new rows. An edit keeps the split its row has: nothing written
 * before a household switched is ever rewritten. New means new to the phone
 * that wrote it: a row two phones both created under one id (a recurring
 * occurrence) follows the rule whichever reaches the server last.
 *
 * The same rule is fulla.shared_pot_for_new, applied where fulla_sync_push
 * inserts a row, so a phone that has not heard of the switch yet (or an app
 * that predates it) cannot create a debt. Both pass
 * testdata/vectors/shared_pot.json.
 */
object SharedPot {

    const val NOTHING_TO_SETTLE = "This household shares one pot; there is nothing to settle."

    fun isShared(household: Household): Boolean = household.moneyMode == MoneyMode.SHARED

    /** A new transaction as this household stores it. */
    fun forNew(t: Transaction, household: Household): Transaction {
        if (!isShared(household)) return t
        val payer = t.paidByMemberId ?: return t
        return when (t.kind) {
            TransactionKind.EXPENSE, TransactionKind.REFUND -> t.copy(split = Split.Equal(listOf(payer)))
            else -> t
        }
    }

    /** Whether this household refuses [t] as a new row. */
    fun refusesNew(t: Transaction, household: Household): Boolean =
        isShared(household) && t.kind == TransactionKind.SETTLEMENT

    /**
     * Whether to ask how the household handles money before somebody else
     * joins it: nobody has chosen yet, and [me] may choose.
     */
    fun shouldAsk(config: Config, me: Member?): Boolean =
        config.household.moneyMode == null && me != null && Permissions.canEditHouseholdSettings(me)
}
