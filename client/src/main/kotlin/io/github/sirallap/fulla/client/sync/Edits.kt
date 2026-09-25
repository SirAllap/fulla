// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.sync

import io.github.sirallap.fulla.core.model.Household
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncEngine
import io.github.sirallap.fulla.core.sync.SyncState
import java.time.Instant

/**
 * What a person's edit does to the local row: its stamp, the version it was
 * based on, and its sync state. Every write the app makes goes through here.
 */
object Edits {

    /**
     * A new row. [connected] is false in local mode, where nothing is ever
     * sent. [household] is the household as this phone last heard of it: in
     * one shared pot the row is written the way the server will store it
     * (SharedPot.forNew), whatever screen, import or recurring item made it.
     */
    fun create(t: Transaction, connected: Boolean, now: Instant, household: Household? = null): LocalTransaction {
        val stamp = SyncEngine.iso(now)
        val shaped = household?.let { SharedPot.forNew(t, it) } ?: t
        return LocalTransaction(
            shaped.copy(createdAt = stamp, clientUpdatedAt = stamp, serverSeq = 0),
            state = if (connected) SyncState.PENDING else SyncState.LOCAL_ONLY,
            baseClientUpdatedAt = null,
        )
    }

    /**
     * A change to a row this phone holds. It keeps the split it is given: the
     * shared pot rule is for new rows only. The edit is stamped after the
     * version it changes, whatever the clock says (SyncEngine.stampFor), and
     * remembers the last version the server confirmed, so the server can tell
     * whether somebody else edited in between.
     */
    fun edit(existing: LocalTransaction, changed: Transaction, connected: Boolean, now: Instant): LocalTransaction {
        val stamp = SyncEngine.stampFor(existing.transaction.clientUpdatedAt, now)
        val base = if (existing.state == SyncState.SYNCED) existing.transaction.clientUpdatedAt else existing.baseClientUpdatedAt
        return LocalTransaction(
            changed.copy(
                id = existing.id,
                createdAt = existing.transaction.createdAt,
                clientUpdatedAt = stamp,
                serverSeq = existing.transaction.serverSeq,
                createdByMemberId = existing.transaction.createdByMemberId,
            ),
            state = when {
                !connected -> SyncState.LOCAL_ONLY
                else -> SyncState.PENDING
            },
            baseClientUpdatedAt = base,
            rejectCode = null,
            rejectMessage = null,
        )
    }

    /** A deletion is a tombstone: the row stays, so the deletion can travel. */
    fun delete(existing: LocalTransaction, connected: Boolean, now: Instant): LocalTransaction =
        edit(existing, existing.transaction.copy(status = Status.DELETED), connected, now)

    fun restore(existing: LocalTransaction, connected: Boolean, now: Instant): LocalTransaction =
        edit(existing, existing.transaction.copy(status = Status.ACTIVE), connected, now)

    /**
     * Local mode becoming shared: every row this phone wrote is now owed to
     * the server. Rows already synced do not exist in local mode.
     */
    fun connect(rows: List<LocalTransaction>): List<LocalTransaction> =
        rows.filter { it.state == SyncState.LOCAL_ONLY }.map { it.copy(state = SyncState.PENDING, baseClientUpdatedAt = null) }
}
