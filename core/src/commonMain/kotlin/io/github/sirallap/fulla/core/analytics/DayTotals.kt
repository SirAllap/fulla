// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.analytics

import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncEngine

/**
 * What one day of the history holds, said in two figures: what was spent and what was received.
 *
 * It counts what every other figure counts ([SyncEngine.counted]: not deleted, not refused) and only
 * income, expenses and refunds; transfers and settlements move money around and are neither. A refund
 * takes from what was spent that day, and what it takes beyond that is money received, so neither
 * figure is ever negative and `received - spent` is exactly income + refunds - expenses.
 */
data class DayTotals(val spentMinor: Long, val receivedMinor: Long) {
    /** Nothing to say about the day (only transfers, or nothing counted). */
    val isEmpty: Boolean get() = spentMinor == 0L && receivedMinor == 0L

    companion object {
        fun of(rows: List<LocalTransaction>): DayTotals {
            var expenses = 0L
            var refunds = 0L
            var income = 0L
            for (t in SyncEngine.counted(rows)) when (t.kind) {
                TransactionKind.EXPENSE -> expenses += t.amountMinor
                TransactionKind.REFUND -> refunds += t.amountMinor
                TransactionKind.INCOME -> income += t.amountMinor
                else -> Unit
            }
            val net = expenses - refunds
            return if (net >= 0) DayTotals(net, income) else DayTotals(0, income - net)
        }
    }
}
