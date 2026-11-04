// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.AccountLedger
import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.analytics.LedgerNote
import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The book of an account lists what Saldos adds up, and what it leaves out, and ends on the same balance. */
class AccountLedgerTest {
    private val today = LocalDate.of(2030, 3, 10)
    private val cash = Account("cash", "Cash", AccountType.CASH, openingBalanceMinor = 6_339, openingBalanceDate = LocalDate.of(2030, 3, 1))
    private val bank = Account("bank", "Bank", AccountType.CHECKING)

    private fun row(id: String, kind: TransactionKind, day: Int, amount: Long, account: String? = "cash", to: String? = null,
                    status: Status = Status.ACTIVE, state: SyncState = SyncState.SYNCED) = LocalTransaction(
        Transaction(id = id, kind = kind, date = LocalDate.of(2030, 3, day), amountMinor = amount, accountId = account, toAccountId = to,
            status = status, createdAt = "2030-03-%02dT10:00:00Z".format(day), clientUpdatedAt = "2030-03-%02dT10:00:00Z".format(day)),
        state,
    )

    private val rows = listOf(
        row("a", TransactionKind.EXPENSE, 2, 932),
        row("b", TransactionKind.EXPENSE, 3, 1_918, status = Status.DELETED),
        row("c", TransactionKind.EXPENSE, 4, 1_450),
        row("d", TransactionKind.TRANSFER, 5, 5_000, account = "bank", to = "cash"),
        row("e", TransactionKind.EXPENSE, 6, 809, state = SyncState.PENDING),
        row("f", TransactionKind.EXPENSE, 7, 700, state = SyncState.REJECTED),
        row("g", TransactionKind.EXPENSE, 20, 1_000),
        row("h", TransactionKind.EXPENSE, 28, 5_555, account = "cash").let { it.copy(transaction = it.transaction.copy(date = LocalDate.of(2030, 2, 20))) },
        row("s", TransactionKind.SETTLEMENT, 8, 300),
    )

    @Test
    fun `it ends on the balance Saldos shows`() {
        val counted = rows.filter { it.transaction.isActive && it.state != SyncState.REJECTED }.map { it.transaction }
        val expected = Analytics.accountBalances(counted, listOf(cash, bank), today).getValue("cash")
        assertEquals(expected, AccountLedger.of(rows, cash, today).balanceMinor)
    }

    @Test
    fun `each movement says why it counts or not, and the balance after it`() {
        val book = AccountLedger.of(rows, cash, today)
        val byId = book.lines.associateBy { it.transaction.id }
        assertEquals(LedgerNote.COUNTS, byId.getValue("a").note)
        assertEquals(LedgerNote.DELETED, byId.getValue("b").note)
        assertEquals(LedgerNote.REFUSED, byId.getValue("f").note)
        assertEquals(LedgerNote.FUTURE, byId.getValue("g").note)
        assertEquals(LedgerNote.BEFORE_OPENING, byId.getValue("h").note)
        assertTrue(byId.getValue("e").unsent)
        // 63,39 − 9,32 = 54,07; the deleted one leaves it; −14,50 = 39,57; +50,00 = 89,57; −8,09 = 81,48.
        assertEquals(5_407, byId.getValue("a").balanceAfterMinor)
        assertEquals(5_407, byId.getValue("b").balanceAfterMinor)
        assertEquals(3_957, byId.getValue("c").balanceAfterMinor)
        assertEquals(8_957, byId.getValue("d").balanceAfterMinor)
        assertEquals(8_148, byId.getValue("e").balanceAfterMinor)
    }

    @Test
    fun `a transfer is an entry for one account and an exit for the other, and a settlement is in neither`() {
        val cashBook = AccountLedger.of(rows, cash, today)
        val bankBook = AccountLedger.of(rows, bank, today)
        assertEquals(5_000, cashBook.lines.first { it.transaction.id == "d" }.deltaMinor)
        assertEquals(-5_000, bankBook.lines.first { it.transaction.id == "d" }.deltaMinor)
        assertTrue(cashBook.lines.none { it.transaction.id == "s" })
        assertTrue(bankBook.lines.none { it.transaction.id == "s" })
    }

    @Test
    fun `the newest is first`() {
        val ids = AccountLedger.of(rows, cash, today).lines.map { it.transaction.id }
        assertEquals(listOf("g", "f", "e", "d", "c", "b", "a", "h"), ids)
    }
}
