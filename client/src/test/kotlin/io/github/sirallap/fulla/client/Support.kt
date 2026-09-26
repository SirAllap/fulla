// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.sync.GuardedWrite
import io.github.sirallap.fulla.client.sync.SyncBackend
import io.github.sirallap.fulla.client.sync.SyncStore
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.model.Household
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.sync.Conflict
import io.github.sirallap.fulla.core.sync.ConflictNote
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.Mutation
import io.github.sirallap.fulla.core.sync.MutationType
import io.github.sirallap.fulla.core.sync.PushResult
import io.github.sirallap.fulla.core.sync.SyncEngine
import io.github.sirallap.fulla.core.sync.SyncState
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/** Invented people and amounts, a fictitious year. */
object Fixtures {
    const val HOUSEHOLD = "00000000-0000-4000-8000-000000000001"
    const val ALICE = "00000000-0000-4000-8000-00000000000a"
    const val BOB = "00000000-0000-4000-8000-00000000000b"
    const val GROCERIES = "00000000-0000-4000-8000-000000000101"
    const val MAIN = "00000000-0000-4000-8000-000000000201"

    private var counter = 0
    fun newId(): String = "00000000-0000-4000-9000-%012d".format(++counter)

    fun expense(amount: Long = 1234, id: String = newId(), stamp: String = "2030-01-15T12:00:00.000Z") = Transaction(
        id = id, kind = TransactionKind.EXPENSE, date = LocalDate.of(2030, 1, 15), amountMinor = amount,
        categoryId = GROCERIES, accountId = MAIN, paidByMemberId = ALICE, split = Split.Equal(listOf(ALICE, BOB)),
        note = "GROCERY STORE 01", createdAt = stamp, clientUpdatedAt = stamp,
    )
}

/** What fulla_sync_push and fulla_sync_pull do, in memory. Kept in step with the SQL. */
class FakeBackend : SyncBackend {
    val rows = LinkedHashMap<String, Transaction>()
    private var seq = 0L
    var configVersion = 1
    var config: JsonObject? = null
    val pushes = mutableListOf<List<Mutation>>()

    /** The household as the server holds it: new rows go through its shared pot rule. */
    var household = Household(Fixtures.HOUSEHOLD, "Demo household", "EUR", "en-GB")

    /** Runs inside a push, after the server has applied it and before the phone hears back. */
    var duringPush: suspend () -> Unit = {}

    override suspend fun push(householdId: String, mutations: List<Mutation>): List<PushResult> {
        require(mutations.size <= SyncEngine.MAX_BATCH)
        pushes += mutations
        val results = mutations.map { m ->
            val prior = rows[m.transaction.id]
            when {
                m.type == MutationType.DELETE && prior == null -> PushResult(m.mutationId, m.transaction.id, true, false)
                m.type == MutationType.DELETE && prior!!.status == Status.DELETED ->
                    PushResult(m.mutationId, m.transaction.id, true, false, serverTransaction = prior)
                m.type == MutationType.DELETE -> {
                    rows[prior!!.id] = prior.copy(status = Status.DELETED, clientUpdatedAt = m.clientUpdatedAt, serverSeq = ++seq)
                    PushResult(m.mutationId, prior.id, true, true)
                }
                m.transaction.amountMinor <= 0 ->
                    PushResult(m.mutationId, m.transaction.id, false, false, errorCode = "validation_failed", errorMessage = "Amount must be positive.")
                prior == null && SharedPot.refusesNew(m.transaction, household) ->
                    PushResult(m.mutationId, m.transaction.id, false, false, errorCode = "validation_failed", errorMessage = SharedPot.NOTHING_TO_SETTLE)
                prior == null -> {
                    val kept = tripped(SharedPot.forNew(m.transaction, household), null)
                    val stored = kept.copy(clientUpdatedAt = m.clientUpdatedAt, serverSeq = ++seq)
                    rows[m.transaction.id] = stored
                    PushResult(m.mutationId, m.transaction.id, true, true, serverTransaction = if (kept != m.transaction) stored else null)
                }
                m.clientUpdatedAt < prior.clientUpdatedAt -> PushResult(m.mutationId, prior.id, true, false,
                    conflict = Conflict(Conflict.Winner.SERVER, SyncEngine.diff(m.transaction, prior)), serverTransaction = prior)
                else -> {
                    val kept = tripped(if (m.baseClientUpdatedAt == null) SharedPot.forNew(m.transaction, household) else m.transaction, prior.tripId)
                    val stored = kept.copy(clientUpdatedAt = m.clientUpdatedAt, serverSeq = ++seq)
                    rows[prior.id] = stored
                    PushResult(m.mutationId, prior.id, true, true, serverTransaction = if (kept != m.transaction) stored else null)
                }
            }
        }
        duringPush()
        return results
    }

    /** As fulla.trip_for: absent keeps the stored trip, and a kind that is no longer expense/refund drops it silently. */
    private fun tripped(tx: Transaction, priorTripId: String?): Transaction {
        if (tx.kind != TransactionKind.EXPENSE && tx.kind != TransactionKind.REFUND) return tx.copy(tripId = null)
        return if (tx.tripKnown) tx else tx.copy(tripId = priorTripId)
    }

    override suspend fun pull(householdId: String, since: Long, configVersion: Int, limit: Int): Wire.PullPage {
        val page = rows.values.filter { it.serverSeq > since }.sortedBy { it.serverSeq }.take(limit)
        val cursor = page.maxOfOrNull { it.serverSeq } ?: since
        return Wire.PullPage(page, cursor, rows.values.any { it.serverSeq > cursor }, this.configVersion,
            if (configVersion != this.configVersion) config else null, null)
    }
}

/** The contract the app's Room store implements, in memory. */
class MemoryStore : SyncStore {
    val rows = LinkedHashMap<String, LocalTransaction>()
    val notes = mutableListOf<ConflictNote>()
    var cursor = 0L
    var version = 0
    var config: JsonObject? = null

    override suspend fun cursor(householdId: String) = cursor
    override suspend fun configVersion(householdId: String) = version
    override suspend fun pending(householdId: String) = rows.values.filter { it.state == SyncState.PENDING }
    override suspend fun held(householdId: String, ids: Collection<String>) = ids.mapNotNull { rows[it] }

    private fun write(writes: List<GuardedWrite>) {
        for (w in writes) {
            if (rows[w.row.id]?.transaction?.clientUpdatedAt == w.expectedStamp) rows[w.row.id] = w.row
        }
    }

    override suspend fun settle(householdId: String, writes: List<GuardedWrite>, notes: List<ConflictNote>) {
        write(writes)
        this.notes += notes
    }

    override suspend fun applyPull(householdId: String, config: JsonObject?, configVersion: Int, writes: List<GuardedWrite>,
                                   notes: List<ConflictNote>, cursor: Long) {
        if (config != null) { this.config = config; version = configVersion }
        write(writes)
        this.notes += notes
        this.cursor = cursor
    }
}
