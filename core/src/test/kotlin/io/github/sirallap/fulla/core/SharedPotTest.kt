// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.balance.Balances
import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.MoneyMode
import io.github.sirallap.fulla.core.model.Role
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.model.TransactionValidator
import io.github.sirallap.fulla.core.split.SharedPot
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedPotTest {

    private fun config(mode: MoneyMode?) = Fixtures.config().let { it.copy(household = it.household.copy(moneyMode = mode)) }

    @Test
    fun `money modes read from the wire, and anything else is not chosen`() {
        assertEquals(MoneyMode.SHARED, MoneyMode.of("shared"))
        assertEquals(MoneyMode.SPLIT, MoneyMode.of("split"))
        assertNull(MoneyMode.of(null))
        assertNull(MoneyMode.of("halves"))
    }

    @Test
    fun `in a shared pot what Alice adds moves no balance, and who paid stays`() {
        val shared = config(MoneyMode.SHARED).household
        val groceries = SharedPot.forNew(Fixtures.expense(amount = 6000), shared)
        val refund = SharedPot.forNew(Fixtures.expense(amount = 500, payer = Fixtures.BOB).copy(kind = TransactionKind.REFUND), shared)
        assertEquals(Split.Equal(listOf(Fixtures.ALICE)), groceries.split)
        assertEquals(Fixtures.ALICE, groceries.paidByMemberId)
        assertEquals(Split.Equal(listOf(Fixtures.BOB)), refund.split)
        assertTrue(Balances.of(listOf(groceries, refund), listOf(Fixtures.ALICE, Fixtures.BOB)).all { it.balanceMinor == 0L })
        // Nothing to split it by without a payer; the validator says so.
        val nobody = Fixtures.expense(split = null).copy(paidByMemberId = null)
        assertEquals(nobody, SharedPot.forNew(nobody, shared))
    }

    @Test
    fun `settling is refused only for a new row in a shared pot`() {
        val settle = Transaction(id = Fixtures.newId(), kind = TransactionKind.SETTLEMENT, date = LocalDate.of(2030, 1, 15),
            amountMinor = 4520, paidByMemberId = Fixtures.BOB, toMemberId = Fixtures.ALICE,
            createdAt = "2030-01-15T00:00:00.000Z", clientUpdatedAt = "2030-01-15T00:00:00.000Z")
        assertEquals(listOf(SharedPot.NOTHING_TO_SETTLE), TransactionValidator.problems(settle, config(MoneyMode.SHARED)))
        assertEquals(emptyList(), TransactionValidator.problems(settle, config(MoneyMode.SHARED), isNew = false))
        assertEquals(emptyList(), TransactionValidator.problems(settle, config(MoneyMode.SPLIT)))
        assertEquals(emptyList(), TransactionValidator.problems(settle, config(null)))
    }

    @Test
    fun `an admin is asked how the household handles money until somebody chooses`() {
        val alice = Member(Fixtures.ALICE, "Alice", "A", role = Role.OWNER)
        val bob = Member(Fixtures.BOB, "Bob", "B", role = Role.MEMBER)
        assertTrue(SharedPot.shouldAsk(config(null), alice))
        assertFalse(SharedPot.shouldAsk(config(null), bob), "only someone who may change it is asked")
        assertFalse(SharedPot.shouldAsk(config(null), null))
        assertFalse(SharedPot.shouldAsk(config(MoneyMode.SPLIT), alice))
        assertFalse(SharedPot.shouldAsk(config(MoneyMode.SHARED), alice))
        assertFalse(SharedPot.isShared(config(null).household))
    }
}
