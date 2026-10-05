// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.DayTotals
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.core.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayTotalsTest {
    private val day = LocalDate.of(2030, 1, 15)

    private fun row(kind: TransactionKind, amount: Long, state: SyncState = SyncState.SYNCED, status: Status = Status.ACTIVE) =
        LocalTransaction(Fixtures.expense(amount = amount, date = day).copy(kind = kind, status = status), state)

    @Test
    fun what_was_spent_and_what_was_received_that_day() {
        val t = DayTotals.of(listOf(
            row(TransactionKind.EXPENSE, 1_850), row(TransactionKind.EXPENSE, 622), row(TransactionKind.INCOME, 70_000),
        ))
        assertEquals(DayTotals(2_472, 70_000), t)
    }

    @Test
    fun a_refund_takes_from_what_was_spent_and_what_is_beyond_it_is_received() {
        assertEquals(DayTotals(700, 0), DayTotals.of(listOf(row(TransactionKind.EXPENSE, 1_000), row(TransactionKind.REFUND, 300))))
        assertEquals(DayTotals(0, 500), DayTotals.of(listOf(row(TransactionKind.EXPENSE, 1_000), row(TransactionKind.REFUND, 1_500))))
    }

    @Test
    fun moves_between_accounts_and_people_are_neither() {
        val t = DayTotals.of(listOf(row(TransactionKind.TRANSFER, 5_000), row(TransactionKind.SETTLEMENT, 2_000)))
        assertTrue(t.isEmpty)
    }

    @Test
    fun deleted_and_refused_rows_are_left_out_like_everywhere_else() {
        val t = DayTotals.of(listOf(
            row(TransactionKind.EXPENSE, 1_000),
            row(TransactionKind.EXPENSE, 9_000, status = Status.DELETED),
            row(TransactionKind.EXPENSE, 4_000, state = SyncState.REJECTED),
            row(TransactionKind.EXPENSE, 200, state = SyncState.PENDING),
        ))
        assertEquals(DayTotals(1_200, 0), t)
    }
}
