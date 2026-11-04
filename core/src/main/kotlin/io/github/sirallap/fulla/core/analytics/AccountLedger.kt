// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.analytics

import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import java.time.LocalDate

/** Why a movement of an account is, or is not, in the balance. */
enum class LedgerNote {
    /** In the balance. */
    COUNTS,
    /** Deleted: it existed, and no longer adds up. */
    DELETED,
    /** Refused by the server: kept and shown, and left out of every figure. */
    REFUSED,
    /** Dated before the day the account's opening balance was measured: that balance already holds it. */
    BEFORE_OPENING,
    /** Dated after today: not in today's balance yet. */
    FUTURE,
}

/**
 * One movement of one account, with what it does to that account and the balance after it. A movement that is
 * not in the balance ([note] is not [LedgerNote.COUNTS]) still has its [deltaMinor], what it would do, and leaves
 * the balance where it was.
 */
data class LedgerLine(
    val row: LocalTransaction,
    val deltaMinor: Long,
    val note: LedgerNote,
    val balanceAfterMinor: Long,
) {
    val transaction: Transaction get() = row.transaction
    val counts: Boolean get() = note == LedgerNote.COUNTS
    /** Only on this phone so far: the server has not heard of it. */
    val unsent: Boolean get() = row.state == SyncState.PENDING
}

/** An account's movements, newest first, and what it holds today. */
data class AccountLedgerView(
    val lines: List<LedgerLine>,
    val openingMinor: Long,
    val openingDate: LocalDate?,
    val balanceMinor: Long,
)

/**
 * The book of one account: every movement that touches it, in order, with the balance after each. It follows the
 * same rules as [Analytics.accountBalances] (the opening balance and its day, transfers both ways, settlements not
 * at all, deleted and refused rows out), so the last balance is the one Saldos shows; the difference is that here
 * the rows that are left out are listed too, and say why.
 */
object AccountLedger {

    fun of(rows: List<LocalTransaction>, account: Account, today: LocalDate): AccountLedgerView {
        val mine = rows.filter { r ->
            val t = r.transaction
            t.kind != TransactionKind.SETTLEMENT && (t.accountId == account.id || (t.kind == TransactionKind.TRANSFER && t.toAccountId == account.id))
        }.sortedWith(compareBy({ it.transaction.date }, { it.transaction.createdAt }, { it.transaction.id }))
        var running = account.openingBalanceMinor
        val lines = mine.map { r ->
            val t = r.transaction
            val delta = delta(t, account.id)
            val note = when {
                !t.isActive -> LedgerNote.DELETED
                r.state == SyncState.REJECTED -> LedgerNote.REFUSED
                t.date > today -> LedgerNote.FUTURE
                account.openingBalanceDate != null && t.date < account.openingBalanceDate -> LedgerNote.BEFORE_OPENING
                else -> LedgerNote.COUNTS
            }
            if (note == LedgerNote.COUNTS) running += delta
            LedgerLine(r, delta, note, running)
        }
        return AccountLedgerView(lines.asReversed(), account.openingBalanceMinor, account.openingBalanceDate, running)
    }

    private fun delta(t: Transaction, id: String): Long = when (t.kind) {
        TransactionKind.INCOME, TransactionKind.REFUND -> t.amountMinor
        TransactionKind.EXPENSE -> -t.amountMinor
        TransactionKind.TRANSFER -> if (t.accountId == id) -t.amountMinor else t.amountMinor
        TransactionKind.SETTLEMENT -> 0
    }
}
