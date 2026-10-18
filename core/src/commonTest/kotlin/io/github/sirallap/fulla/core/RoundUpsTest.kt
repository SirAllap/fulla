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
import io.github.sirallap.fulla.core.time.LocalDate
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
    fun a_coffee_of_1_50_rounds_up_to_2_and_times_two_puts_1_00_aside() {
        assertEquals(100, RoundUps.savingMinor(150, eur, 2))
    }

    @Test
    fun the_difference_to_the_next_euro_times_the_multiplier() {
        assertEquals(40, RoundUps.savingMinor(80, eur, 2))     // 0,80 -> 0,20 x 2
        assertEquals(44, RoundUps.savingMinor(1256, eur, 1))   // 12,56 -> 0,44
        assertEquals(500, RoundUps.savingMinor(150, eur, 10))  // 0,50 x 10
        assertEquals(1, RoundUps.savingMinor(99, eur, 1))
        assertEquals(995, RoundUps.savingMinor(1_005, Currency.require("BHD"), 1), "a three-decimal currency rounds to its whole unit")
    }

    @Test
    fun an_exact_amount_is_not_rounded_and_a_currency_with_no_cents_has_nothing_to_round() {
        assertEquals(0, RoundUps.savingMinor(1200, eur, 5))
        assertEquals(0, RoundUps.savingMinor(1234, Currency.require("JPY"), 5))
        assertEquals(0, RoundUps.savingMinor(0, eur, 5))
    }

    @Test
    fun a_purchase_on_an_account_with_round_up_makes_a_transfer_to_the_savings_account() {
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
    fun the_id_is_the_same_whoever_works_it_out_and_different_for_another_purchase() {
        val a = spend(150)
        val b = spend(150)
        assertEquals(RoundUps.idFor(a.id), RoundUps.idFor(a.id))
        assertTrue(RoundUps.idFor(a.id) != RoundUps.idFor(b.id))
        assertTrue(RoundUps.idFor(a.id) != a.id)
    }

    @Test
    fun nothing_is_rounded_that_should_not_be() {
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
    fun a_new_purchase_gets_its_transfer_a_changed_one_changes_the_transfer_it_has_and_one_written_before_is_never_rounded_afterwards() {
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
    fun the_money_moves_between_the_accounts_and_is_neither_spending_nor_income() {
        val cfg = config()
        val expense = spend(150)
        val transfer = RoundUps.companionOf(expense, cfg)!!
        val a = Analytics(cfg, PeriodRule())
        val balances = a.accountBalances(listOf(expense, transfer), cfg.accounts, LocalDate.of(2030, 1, 31))
        assertEquals(-150L - 100, balances[Fixtures.MAIN])
        assertEquals(100L, balances[savingsId])
        val january = io.github.sirallap.fulla.core.time.YearMonth.of(2030, 1)
        val summary = a.summary(listOf(expense, transfer), january)
        assertEquals(150L, summary.expenseMinor, "only the purchase is spending")
        assertEquals(0L, summary.incomeMinor)
    }

    @Test
    fun asking_for_it_rounds_this_period_s_purchases_once_and_leaves_out_what_is_out_of_range_deleted_or_already_done() {
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
    fun changing_the_account_of_a_purchase_moves_its_round_up_with_it_both_ways_and_back_again() {
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
    fun an_amount_that_stops_being_exact_makes_the_round_up_and_one_that_becomes_exact_takes_it_away_and_gives_it_back() {
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
    fun a_list_shows_the_round_up_under_its_purchase_and_only_stands_it_alone_when_the_purchase_is_not_in_the_list() {
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

    @Test
    fun the_ids_are_the_ones_the_phone_works_out() {
        // Worked out by the Android app (JVM): the web must write the very same rows, or the sync would keep both.
        assertEquals("fc4007bd-9b5f-5f3e-bf85-cc9a0a9be5e9", RoundUps.idFor("00000000-0000-4000-8000-0000000000AB"))
        assertEquals("93659cb9-d22d-5e2a-af8f-586ad28601b5", io.github.sirallap.fulla.core.demo.DemoData.HOUSEHOLD_ID)
    }
}
