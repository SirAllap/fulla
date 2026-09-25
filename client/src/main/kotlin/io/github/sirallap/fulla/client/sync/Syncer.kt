// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.sync

import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.sync.ConflictNote
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.Mutation
import io.github.sirallap.fulla.core.sync.PushResult
import io.github.sirallap.fulla.core.sync.SyncEngine
import io.github.sirallap.fulla.core.sync.SyncState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** The two server calls the sync needs. [io.github.sirallap.fulla.client.remote.FullaApi] is the real one. */
interface SyncBackend {
    suspend fun push(householdId: String, mutations: List<Mutation>): List<PushResult>
    suspend fun pull(householdId: String, since: Long, configVersion: Int, limit: Int): Wire.PullPage
}

/**
 * A row to write only if the row stored under its id still is what the sync
 * read: [expectedStamp] is the client_updated_at it had, or null if there was
 * no row. A person can edit while a sync is in flight; their edit carries a
 * newer stamp, the guard fails, and the edit stays pending instead of being
 * overwritten by the answer to an older version.
 */
data class GuardedWrite(val expectedStamp: String?, val row: LocalTransaction)

/** A household's local copy, as far as the sync is concerned. */
interface SyncStore {
    suspend fun cursor(householdId: String): Long
    suspend fun configVersion(householdId: String): Int

    /** Rows owed to the server ([SyncState.PENDING]). */
    suspend fun pending(householdId: String): List<LocalTransaction>

    /** The rows held under these ids, in any state. */
    suspend fun held(householdId: String, ids: Collection<String>): List<LocalTransaction>

    /** Writes a push's answers, each only if its guard holds, in one transaction. */
    suspend fun settle(householdId: String, writes: List<GuardedWrite>, notes: List<ConflictNote>)

    /**
     * Applies one pulled page in one transaction: the config first (if any),
     * then the rows (each only if its guard holds), then the cursor. The
     * cursor never lands without the rows it covers.
     */
    suspend fun applyPull(
        householdId: String,
        config: JsonObject?,
        configVersion: Int,
        writes: List<GuardedWrite>,
        notes: List<ConflictNote>,
        cursor: Long,
    )
}

data class SyncReport(
    val pushed: Int,
    val pulled: Int,
    val rejected: Int,
    val conflicts: Int,
    val configChanged: Boolean,
)

/**
 * One sync: push everything owed, pull every page, and push once more if the
 * merge queued anything (docs/sync.md). Exactly one extra push, never a loop.
 *
 * Every decision is SyncEngine's; this class only moves rows between the
 * store and the server in the right order.
 */
class Syncer(
    private val store: SyncStore,
    private val backend: SyncBackend,
    private val clientId: String,
    private val pageSize: Int = 500,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val running = Mutex()

    suspend fun sync(householdId: String): SyncReport = running.withLock {
        var report = push(householdId)
        val pulled = pull(householdId)
        report = report.copy(pulled = pulled.pulled, conflicts = report.conflicts + pulled.conflicts, configChanged = pulled.configChanged)
        if (pulled.requeued > 0) {
            val again = push(householdId)
            report = report.copy(pushed = report.pushed + again.pushed, rejected = report.rejected + again.rejected,
                conflicts = report.conflicts + again.conflicts)
        }
        report
    }

    private suspend fun push(householdId: String): SyncReport {
        val owed = SyncEngine.pending(store.pending(householdId))
        var pushed = 0
        var rejected = 0
        var conflicts = 0
        for (batch in owed.chunked(SyncEngine.MAX_BATCH)) {
            val mutations = batch.map { SyncEngine.toMutation(it, clientId, newId()) }
            val results = backend.push(householdId, mutations)
            val outcome = SyncEngine.applyPush(batch, results)
            val sentStamp = batch.associate { it.id to it.transaction.clientUpdatedAt }
            store.settle(householdId, outcome.rows.map { GuardedWrite(sentStamp[it.id], it) }, outcome.notes)
            pushed += outcome.rows.count { it.state == SyncState.SYNCED }
            rejected += outcome.rows.count { it.state == SyncState.REJECTED }
            conflicts += outcome.notes.size
        }
        return SyncReport(pushed, 0, rejected, conflicts, false)
    }

    private data class Pulled(val pulled: Int, val conflicts: Int, val requeued: Int, val configChanged: Boolean)

    private suspend fun pull(householdId: String): Pulled {
        var pulled = 0
        var conflicts = 0
        var requeued = 0
        var configChanged = false
        do {
            val since = store.cursor(householdId)
            val page = backend.pull(householdId, since, store.configVersion(householdId), pageSize)
            val local = store.held(householdId, page.transactions.map { it.id })
            val stamps = local.associate { it.id to it.transaction.clientUpdatedAt }
            val merge = SyncEngine.merge(local, page.transactions)
            store.applyPull(
                householdId,
                config = page.config,
                configVersion = page.configVersion,
                writes = merge.rows.map { GuardedWrite(stamps[it.id], it) },
                notes = merge.notes,
                cursor = SyncEngine.nextCursor(since, page.cursor),
            )
            configChanged = configChanged || page.config != null
            pulled += page.transactions.size
            conflicts += merge.notes.size
            requeued += merge.requeued
            // A server that answers has_more without moving the cursor would loop forever.
        } while (page.hasMore && page.cursor > since)
        return Pulled(pulled, conflicts, requeued, configChanged)
    }
}
