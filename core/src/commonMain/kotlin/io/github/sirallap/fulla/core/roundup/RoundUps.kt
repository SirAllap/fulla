// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.roundup

import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.time.LocalDate

/**
 * Round-up saving, as the banks that offer it do: a purchase on an account that has it on is rounded up to the
 * next whole unit of the currency, and the difference, times a multiplier, is moved to another account.
 * Café 1,50 € with ×2: the difference to 2 € is 0,50 €, so 1,00 € goes to the savings account.
 *
 * Fulla does not move money: it writes down what the bank does, so the balances match. The move is an ordinary
 * transfer carrying the reserved tag [TAG], with an id worked out from the purchase's ([idFor]), so two phones
 * that write it write the same row and the sync merges them, and the purchase can always find its transfer.
 * The setting itself is the account's [io.github.sirallap.fulla.core.model.RoundUp].
 */
object RoundUps {
    /** Reserved tag of the transfers this makes; the phone hides it (PeriodAnchors.visibleTags). */
    const val TAG = "fulla:round-up"

    /** What a bank offers: the difference once, twice, five or ten times. */
    val MULTIPLIERS = listOf(1, 2, 5, 10)

    /** What one purchase puts aside: the cents up to the next whole unit, times [multiplier]. Nothing for an exact amount, or a currency with no cents. */
    fun savingMinor(amountMinor: Long, currency: Currency, multiplier: Int): Long {
        var unit = 1L
        repeat(currency.minorUnits) { unit *= 10 }
        if (unit <= 1 || amountMinor <= 0) return 0
        val rest = amountMinor % unit
        return if (rest == 0L) 0 else (unit - rest) * multiplier
    }

    /** The id of the transfer a purchase makes: the same on every phone, so it is written once. */
    fun idFor(expenseId: String): String = DeterministicId.uuid5(expenseId, "round-up")

    /** Whether [t] is a transfer this made. */
    fun isRoundUp(t: Transaction): Boolean = t.kind == TransactionKind.TRANSFER && TAG in t.tags

    /**
     * The transfer that should exist for [expense], or null when none applies: it is not an active expense, its
     * account has no round-up (or is cash, or archived), the account it goes to is gone, archived or the same one,
     * or the amount has nothing to round.
     */
    fun companionOf(expense: Transaction, config: Config): Transaction? {
        if (expense.kind != TransactionKind.EXPENSE || expense.status != Status.ACTIVE) return null
        val from = config.account(expense.accountId) ?: return null
        val rule = from.roundUp ?: return null
        if (from.type == AccountType.CASH || from.archived || rule.multiplier !in MULTIPLIERS) return null
        val to = config.account(rule.toAccountId) ?: return null
        if (to.archived || to.id == from.id) return null
        val currency = Currency.of(config.household.currency) ?: return null
        val saving = savingMinor(expense.amountMinor, currency, rule.multiplier)
        if (saving <= 0) return null
        return Transaction(
            id = idFor(expense.id), kind = TransactionKind.TRANSFER, date = expense.date, amountMinor = saving,
            accountId = from.id, toAccountId = to.id, paidByMemberId = expense.paidByMemberId, tags = listOf(TAG),
            createdAt = "", clientUpdatedAt = "",
        )
    }

    /**
     * The transfers a person asks to write for what [accountId] already holds between [from] and [to] (both included):
     * one per active expense of that account in that range that has none yet. [all] is every row held, deleted ones
     * too, so a round-up the person deleted stays deleted and pressing it twice writes nothing the second time.
     */
    fun backfill(all: List<Transaction>, accountId: String, from: LocalDate, to: LocalDate, config: Config): List<Transaction> {
        val held = all.map { it.id }.toSet()
        return all.asSequence()
            .filter { it.accountId == accountId && it.date in from..to }
            .mapNotNull { companionOf(it, config) }
            .filter { it.id !in held }
            .toList()
    }

    /** The active round-up transfer of [expense] among [byId] (every row held, by id), or null: the one a list shows under the purchase. */
    fun underPurchase(expense: Transaction, byId: Map<String, Transaction>): Transaction? {
        if (expense.kind != TransactionKind.EXPENSE) return null
        val id = runCatching { idFor(expense.id) }.getOrNull() ?: return null
        return byId[id]?.takeIf { isRoundUp(it) && it.status == Status.ACTIVE }
    }

    /**
     * The round-up transfers of [shown] that sit under a purchase that is shown too, so a list draws them once, as part of
     * the purchase. One whose purchase is not in the list (another filter, a search) is not among them and stands alone.
     */
    fun foldedInto(shown: List<Transaction>): Set<String> {
        val under = shown.filter { it.kind == TransactionKind.EXPENSE }.mapNotNull { runCatching { idFor(it.id) }.getOrNull() }.toSet()
        return shown.filter { isRoundUp(it) && it.id in under }.map { it.id }.toSet()
    }

    /** What to do with the transfer of a purchase that was just written. */
    sealed interface Action {
        data object None : Action
        data class Create(val transfer: Transaction) : Action
        data class Update(val transfer: Transaction) : Action
        data object Delete : Action
    }

    /**
     * What a written purchase does to its transfer. [previous] is the purchase as it was before this edit (null for a new
     * one) and [held] the transfer this phone already has for it, if any.
     *
     * A purchase gets its transfer when it is new, or when this edit is what makes it qualify: it moved from cash to an
     * account that rounds, or its amount stopped being exact. What was written before round-up was turned on is not
     * rounded afterwards by an edit that changes nothing about that (the person can ask for it, see [backfill]). A
     * transfer that is held follows the purchase (amount, date, account), and goes when the purchase no longer
     * qualifies (moved to cash, amount exact). If it went that way and the purchase qualifies again, it comes back. A
     * transfer the person deleted by hand, while the purchase qualified, stays deleted.
     */
    fun reconcile(expense: Transaction, previous: Transaction?, held: Transaction?, config: Config): Action {
        val wanted = companionOf(expense, config)
        val qualifiedBefore = previous != null && companionOf(previous, config) != null
        if (held == null) return if (wanted != null && !qualifiedBefore) Action.Create(wanted) else Action.None
        if (held.status != Status.ACTIVE) return if (wanted != null && !qualifiedBefore) Action.Update(wanted) else Action.None
        if (wanted == null) return Action.Delete
        val same = held.amountMinor == wanted.amountMinor && held.date == wanted.date && held.accountId == wanted.accountId &&
            held.toAccountId == wanted.toAccountId && held.paidByMemberId == wanted.paidByMemberId
        return if (same) Action.None else Action.Update(wanted)
    }
}
