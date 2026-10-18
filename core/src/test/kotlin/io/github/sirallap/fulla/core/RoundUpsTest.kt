// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.RoundUp
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.core.roundup.RoundUps
import io.github.sirallap.fulla.core.roundup.RoundUps.Action
import io.github.sirallap.fulla.core.rules.PeriodAnchors
import io.github.sirallap.fulla.core.rules.PeriodRule
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoundUpsTest {
    private val eur = Currency.require("EUR")
    private val savingsId = "00000000-0000-4000-8000-000000000301"

    private fun config(multiplier: Int = 2, type: AccountType = AccountType.CHECKING, to: String = savingsId): Config {
        val base = Fixtures.config()
        return base.copy(accounts = listOf(
            Account(Fixtures.MAIN, "Main account", type, roundUp = RoundUp(to, multiplier)),
            Account(Fixtures.CASH, "Cash", AccountType.CASH),
            Account(savingsId, "Orange", AccountType.SAVINGS),
        ))
    }

    private fun spend(minor: Long) = Fixtures.expense(amount = minor, split = null)

    @Test
    fun `a round-up goes to any other open account, never to cash unless it already did`() {
        val accounts = config().accounts + Account("00000000-0000-4000-8000-000000000302", "Old", AccountType.SAVINGS, archived = true)
        assertEquals(listOf(savingsId), RoundUps.destinations(accounts, Fixtures.MAIN, null).map { it.id })
        assertEquals(listOf(Fixtures.CASH, savingsId), RoundUps.destinations(accounts, Fixtures.MAIN, Fixtures.CASH).map { it.id })
        assertEquals(listOf(Fixtures.MAIN, savingsId), RoundUps.destinations(accounts, null, null).map { it.id })
    }

    @Test
    fun `a coffee of 1,50 rounds up to 2 and, times two, puts 1,00 aside`() {
        assertEquals(100, RoundUps.savingMinor(150, eur, 2))
    }

    @Test
    fun `the difference to the next euro, times the multiplier`() {
        assertEquals(40, RoundUps.savingMinor(80, eur, 2))     // 0,80 -> 0,20 x 2
        assertEquals(44, RoundUps.savingMinor(1256, eur, 1))   // 12,56 -> 0,44
        assertEquals(500, RoundUps.savingMinor(150, eur, 10))  // 0,50 x 10
        assertEquals(1, RoundUps.savingMinor(99, eur, 1))
        assertEquals(995, RoundUps.savingMinor(1_005, Currency.require("BHD"), 1), "a three-decimal currency rounds to its whole unit")
    }

    @Test
    fun `an exact amount is not rounded, and a currency with no cents has nothing to round`() {
        assertEquals(0, RoundUps.savingMinor(1200, eur, 5))
        assertEquals(0, RoundUps.savingMinor(1234, Currency.require("JPY"), 5))
        assertEquals(0, RoundUps.savingMinor(0, eur, 5))
    }

    @Test
    fun `a purchase on an account with round-up makes a transfer to the savings account`() {
        val expense = spend(150)
        val t = RoundUps.companionOf(expense, config())!!
        assertEquals(TransactionKind.TRANSFER, t.kind)
        assertEquals(100, t.amountMinor)
        assertEquals(Fixtures.MAIN, t.accountId)
        assertEquals(savingsId, t.toAccountId)
        assertEquals(expense.date, t.date)
        assertEquals(listOf(RoundUps.TAG), t.tags)
        assertEquals(RoundUps.idFor(expense.id), t.id)
        assertTrue(RoundUps.isRoundUp(t))
        assertEquals(emptyList(), PeriodAnchors.visibleTags(t.tags), "nobody sees the reserved tag")
    }

    @Test
    fun `the id is the same whoever works it out, and different for another purchase`() {
        val a = spend(150)
        val b = spend(150)
        assertEquals(RoundUps.idFor(a.id), RoundUps.idFor(a.id))
        assertTrue(RoundUps.idFor(a.id) != RoundUps.idFor(b.id))
        assertTrue(RoundUps.idFor(a.id) != a.id)
    }

    @Test
    fun `nothing is rounded that should not be`() {
        val expense = spend(150)
        assertNull(RoundUps.companionOf(expense.copy(kind = TransactionKind.INCOME), config()), "income")
        assertNull(RoundUps.companionOf(expense.copy(kind = TransactionKind.REFUND), config()), "a refund")
        assertNull(RoundUps.companionOf(expense.copy(status = Status.DELETED), config()), "a deleted purchase")
        assertNull(RoundUps.companionOf(expense.copy(accountId = Fixtures.CASH), config()), "cash")
        assertNull(RoundUps.companionOf(expense, config(type = AccountType.CASH)), "a cash account never rounds, whatever it says")
        assertNull(RoundUps.companionOf(spend(1200), config()), "an exact amount")
        assertNull(RoundUps.companionOf(expense, Fixtures.config()), "an account with no round-up")
        assertNull(RoundUps.companionOf(expense, config(to = "00000000-0000-4000-8000-0000000003ff")), "a destination that is gone")
        assertNull(RoundUps.companionOf(expense, config(to = Fixtures.MAIN)), "round-up to itself")
        assertNull(RoundUps.companionOf(expense, config(multiplier = 3)), "a multiplier no bank offers")
        val archived = config().copy(accounts = config().accounts.map { if (it.id == savingsId) it.copy(archived = true) else it })
        assertNull(RoundUps.companionOf(expense, archived), "an archived destination")
    }

    @Test
    fun `a new purchase gets its transfer, a changed one changes the transfer it has, and one written before is never rounded afterwards`() {
        val cfg = config()
        val expense = spend(150)
        val made = (RoundUps.reconcile(expense, previous = null, held = null, config = cfg) as Action.Create).transfer
        assertEquals(100, made.amountMinor)

        // the amount changes: the transfer follows
        val dearer = expense.copy(amountMinor = 380)
        val update = RoundUps.reconcile(dearer, previous = expense, held = made, config = cfg) as Action.Update
        assertEquals(40, update.transfer.amountMinor)
        assertEquals(made.id, update.transfer.id)
        // nothing changed: nothing to do
        assertEquals(Action.None, RoundUps.reconcile(expense, previous = expense, held = made, config = cfg))
        // the amount is exact now, or the account is no longer one that rounds: the transfer goes
        assertEquals(Action.Delete, RoundUps.reconcile(expense.copy(amountMinor = 200), previous = expense, held = made, config = cfg))
        assertEquals(Action.Delete, RoundUps.reconcile(expense.copy(accountId = Fixtures.CASH), previous = expense, held = made, config = cfg))
        // an old purchase that is edited gets nothing made after the fact
        assertEquals(Action.None, RoundUps.reconcile(dearer, previous = expense, held = null, config = cfg))
        // a transfer the person deleted stays deleted
        assertEquals(Action.None, RoundUps.reconcile(dearer, previous = expense, held = made.copy(status = Status.DELETED), config = cfg))
    }

    @Test
    fun `the money moves between the accounts and is neither spending nor income`() {
        val cfg = config()
        val expense = spend(150)
        val transfer = RoundUps.companionOf(expense, cfg)!!
        val a = Analytics(cfg, PeriodRule())
        val balances = a.accountBalances(listOf(expense, transfer), cfg.accounts, LocalDate.of(2030, 1, 31))
        assertEquals(-150L - 100, balances[Fixtures.MAIN])
        assertEquals(100L, balances[savingsId])
        val january = java.time.YearMonth.of(2030, 1)
        val summary = a.summary(listOf(expense, transfer), january)
        assertEquals(150L, summary.expenseMinor, "only the purchase is spending")
        assertEquals(0L, summary.incomeMinor)
    }

    @Test
    fun `asking for it rounds this period's purchases once, and leaves out what is out of range, deleted or already done`() {
        val inside = spend(150).copy(id = "00000000-0000-4000-8000-000000000a01", date = LocalDate.of(2030, 1, 10))
        val outside = spend(150).copy(id = "00000000-0000-4000-8000-000000000a02", date = LocalDate.of(2029, 12, 31))
        val exact = spend(1200).copy(id = "00000000-0000-4000-8000-000000000a03", date = LocalDate.of(2030, 1, 11))
        val cash = spend(150).copy(id = "00000000-0000-4000-8000-000000000a04", date = LocalDate.of(2030, 1, 12), accountId = Fixtures.CASH)
        val gone = spend(150).copy(id = "00000000-0000-4000-8000-000000000a05", date = LocalDate.of(2030, 1, 13), status = Status.DELETED)
        val from = LocalDate.of(2030, 1, 1)
        val to = LocalDate.of(2030, 1, 31)
        val rows = listOf(inside, outside, exact, cash, gone)

        val made = RoundUps.backfill(rows, Fixtures.MAIN, from, to, config())
        assertEquals(listOf(RoundUps.idFor(inside.id)), made.map { it.id })
        assertEquals(100L, made.single().amountMinor)
        assertEquals(inside.date, made.single().date)

        assertTrue(RoundUps.backfill(rows + made, Fixtures.MAIN, from, to, config()).isEmpty(), "a second press writes nothing")
        val deletedByHand = made.single().copy(status = Status.DELETED)
        assertTrue(RoundUps.backfill(rows + deletedByHand, Fixtures.MAIN, from, to, config()).isEmpty(), "a round-up the person deleted stays deleted")
    }

    @Test
    fun `changing the account of a purchase moves its round-up with it, both ways, and back again`() {
        val cfg = config()
        val inCash = spend(150).copy(accountId = Fixtures.CASH)
        val onCard = inCash.copy(accountId = Fixtures.MAIN)

        // cash, then corrected to the account that rounds: the round-up is made now
        val made = (RoundUps.reconcile(onCard, previous = inCash, held = null, config = cfg) as Action.Create).transfer
        assertEquals(100, made.amountMinor)
        // and the other way: the card purchase was cash after all, the round-up goes
        assertEquals(Action.Delete, RoundUps.reconcile(inCash, previous = onCard, held = made, config = cfg))
        // and back to the card once more: the same transfer comes back
        val gone = made.copy(status = Status.DELETED)
        val back = RoundUps.reconcile(onCard, previous = inCash, held = gone, config = cfg) as Action.Update
        assertEquals(made.id, back.transfer.id)
        assertEquals(Status.ACTIVE, back.transfer.status)
        // one the person deleted by hand while the purchase qualified stays deleted when the account is not touched
        assertEquals(Action.None, RoundUps.reconcile(onCard.copy(note = "x"), previous = onCard, held = gone, config = cfg))
    }

    @Test
    fun `an amount that stops being exact makes the round-up, and one that becomes exact takes it away and gives it back`() {
        val cfg = config()
        val exact = spend(400)
        val inexact = exact.copy(amountMinor = 380)
        val made = (RoundUps.reconcile(inexact, previous = exact, held = null, config = cfg) as Action.Create).transfer
        assertEquals(40, made.amountMinor)
        assertEquals(Action.Delete, RoundUps.reconcile(exact, previous = inexact, held = made, config = cfg))
        val back = RoundUps.reconcile(inexact, previous = exact, held = made.copy(status = Status.DELETED), config = cfg)
        assertTrue(back is Action.Update && back.transfer.status == Status.ACTIVE)
    }

    @Test
    fun `a list shows the round-up under its purchase and only stands it alone when the purchase is not in the list`() {
        val purchase = spend(150)
        val made = RoundUps.companionOf(purchase, config())!!
        val other = spend(300).copy(id = "00000000-0000-4000-8000-000000000b01")
        val byId = listOf(purchase, made, other).associateBy { it.id }

        assertEquals(made, RoundUps.underPurchase(purchase, byId))
        assertNull(RoundUps.underPurchase(other, byId), "a purchase with nothing to round has nothing under it")
        assertNull(RoundUps.underPurchase(made, byId), "only a purchase has a round-up under it")
        assertNull(RoundUps.underPurchase(purchase, byId + (made.id to made.copy(status = Status.DELETED))), "one the person deleted is not shown")

        assertEquals(setOf(made.id), RoundUps.foldedInto(listOf(made, purchase, other)))
        assertTrue(RoundUps.foldedInto(listOf(made, other)).isEmpty(), "without its purchase in the list it stands alone")
    }
}
