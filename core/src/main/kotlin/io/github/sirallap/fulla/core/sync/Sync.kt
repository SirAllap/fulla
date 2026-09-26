// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.sync

import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import java.time.Instant

/**
 * The sync rules. Everything the phone decides about sync is decided here, so
 * that a test can hold it; the Android side only calls these functions and
 * moves bytes. docs/sync.md explains the protocol.
 */

enum class SyncState {
    /** Local mode: there is no backend to send it to. */
    LOCAL_ONLY,

    /** Written here, not yet accepted by the server. */
    PENDING,

    /** The server has this version. */
    SYNCED,

    /** The server refused it. Kept and shown; never retried until the user changes it. */
    REJECTED,
}

data class LocalTransaction(
    val transaction: Transaction,
    val state: SyncState = SyncState.PENDING,
    /** The client_updated_at this edit started from, so the server can tell a collision from an ordinary edit. */
    val baseClientUpdatedAt: String? = null,
    val rejectCode: String? = null,
    val rejectMessage: String? = null,
) {
    val id: String get() = transaction.id
}

enum class MutationType(val key: String) { UPSERT("upsert"), DELETE("delete") }

data class Mutation(
    val mutationId: String,
    val type: MutationType,
    val clientId: String,
    val clientUpdatedAt: String,
    val baseClientUpdatedAt: String?,
    val transaction: Transaction,
)

data class FieldChange(val field: String, val before: String, val after: String)

data class Conflict(val winner: Winner, val overwritten: List<FieldChange>) {
    enum class Winner { CLIENT, SERVER }
}

/** The server's answer to one mutation. */
data class PushResult(
    val mutationId: String,
    val transactionId: String,
    val ok: Boolean,
    val applied: Boolean,
    val conflict: Conflict? = null,
    /** When the server kept its own, newer version: that version. */
    val serverTransaction: Transaction? = null,
    val warnings: List<String> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/** Told to the user after an edit was overwritten. Never silent. */
data class ConflictNote(
    val transactionId: String,
    val keptVersion: Conflict.Winner,
    val changes: List<FieldChange>,
)

data class MergeResult(
    /** Rows to write to the local database, in their new state. */
    val rows: List<LocalTransaction>,
    val notes: List<ConflictNote>,
    /** How many rows the merge queued to be pushed again. More than zero means one more push. */
    val requeued: Int,
)

data class PushOutcome(val rows: List<LocalTransaction>, val notes: List<ConflictNote>)

object SyncEngine {

    const val MAX_BATCH = 100
    private const val BACKOFF_CAP_MS = 15 * 60_000L

    /** Never one request per row: pushes carry up to [MAX_BATCH] mutations. */
    fun batches(mutations: List<Mutation>): List<List<Mutation>> = mutations.chunked(MAX_BATCH)

    /** 2 s, 4 s, 8 s … capped at 15 minutes. */
    fun backoffMillis(attempt: Int): Long = minOf(2_000L shl attempt.coerceIn(0, 20), BACKOFF_CAP_MS)

    /**
     * The client_updated_at for a new edit of a row last stamped [previous].
     *
     * The phone's clock, unless that clock is behind the version being edited
     * (another phone's clock runs ahead, or this one runs behind): then one
     * millisecond after it. An edit made after seeing a version is, by
     * definition, newer than it, and must not lose to it on a clock.
     */
    fun stampFor(previous: String?, now: Instant): String {
        val prev = previous?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val stamp = if (prev != null && !now.isAfter(prev)) prev.plusMillis(1) else now
        return iso(stamp)
    }

    fun iso(instant: Instant): String = instant.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()
        .let { if (it.length == 20) it.dropLast(1) + ".000Z" else it }

    /** Everything owed to the server, oldest edit first. */
    fun pending(rows: List<LocalTransaction>): List<LocalTransaction> =
        rows.filter { it.state == SyncState.PENDING }.sortedBy { it.transaction.clientUpdatedAt }

    fun toMutation(row: LocalTransaction, clientId: String, mutationId: String): Mutation = Mutation(
        mutationId = mutationId,
        type = if (row.transaction.status == Status.DELETED) MutationType.DELETE else MutationType.UPSERT,
        clientId = clientId,
        clientUpdatedAt = row.transaction.clientUpdatedAt,
        baseClientUpdatedAt = row.baseClientUpdatedAt,
        transaction = row.transaction,
    )

    /**
     * What a push's answers mean for the rows that were sent.
     *
     * ok → settled: synced, whether or not the server applied it. If the
     * server kept its own newer version, that version replaces the local one
     * here and now; waiting for a pull would not work, because the server's
     * version may already be behind this phone's cursor.
     * not ok → rejected, held with its reason.
     */
    fun applyPush(sent: List<LocalTransaction>, results: List<PushResult>): PushOutcome {
        val byId = sent.associateBy { it.id }
        val rows = mutableListOf<LocalTransaction>()
        val notes = mutableListOf<ConflictNote>()
        for (r in results) {
            val row = byId[r.transactionId] ?: continue
            if (!r.ok) {
                rows += row.copy(state = SyncState.REJECTED, rejectCode = r.errorCode, rejectMessage = r.errorMessage)
                continue
            }
            val kept = r.serverTransaction ?: row.transaction
            rows += LocalTransaction(kept, SyncState.SYNCED, baseClientUpdatedAt = kept.clientUpdatedAt)
            r.conflict?.let { notes += ConflictNote(row.id, it.winner, it.overwritten) }
        }
        return PushOutcome(rows, notes)
    }

    /**
     * Merges rows from a pull into what the phone holds.
     *
     * 1. Not held here: store it.
     * 2. A remote deletion beats a local row with no unsent edit, whatever the
     *    clocks say. Clocks on two phones are never in step; a deletion that
     *    loses to one leaves a row alive here and gone everywhere else, and
     *    because the local copy is already synced nothing would ever repair it.
     * 3. Otherwise the later client_updated_at wins. A local row that wins and
     *    was already synced is queued again: its win would otherwise exist on
     *    this phone only. A rejected row is left as it is.
     * 4. A remote row that overwrites a local unsent edit raises a note listing
     *    what changed.
     */
    fun merge(local: List<LocalTransaction>, remote: List<Transaction>): MergeResult {
        val held = local.associateBy { it.id }
        val rows = mutableListOf<LocalTransaction>()
        val notes = mutableListOf<ConflictNote>()
        var requeued = 0
        for (r in remote) {
            val l = held[r.id]
            if (l == null) {
                rows += LocalTransaction(r, SyncState.SYNCED, r.clientUpdatedAt)
                continue
            }
            if (l.state == SyncState.REJECTED) continue
            val tombstoneWins = r.status == Status.DELETED &&
                l.transaction.status != Status.DELETED &&
                l.state != SyncState.PENDING
            val localWins = !tombstoneWins && l.transaction.clientUpdatedAt > r.clientUpdatedAt
            if (localWins) {
                if (l.state == SyncState.SYNCED) {
                    rows += l.copy(state = SyncState.PENDING)
                    requeued++
                }
                continue
            }
            if (l.state == SyncState.PENDING) {
                val changes = diff(l.transaction, r)
                if (changes.isNotEmpty()) notes += ConflictNote(r.id, Conflict.Winner.SERVER, changes)
            }
            rows += LocalTransaction(r, SyncState.SYNCED, r.clientUpdatedAt)
        }
        return MergeResult(rows, notes, requeued)
    }

    /** The cursor after a pull: only forward, and only to a value the server issued. */
    fun nextCursor(previous: Long, fromServer: Long): Long = maxOf(previous, fromServer)

    /**
     * The cursor after a push: unchanged. A push never moves the pull cursor.
     * Anything a push could hand back sits above rows other members wrote since
     * this phone last pulled, and adopting it would skip them for good.
     */
    fun cursorAfterPush(previous: Long): Long = previous

    /** Fields that differ between two versions, for conflict notes. Server bookkeeping is never reported. */
    fun diff(before: Transaction, after: Transaction): List<FieldChange> {
        val out = mutableListOf<FieldChange>()
        fun cmp(name: String, a: Any?, b: Any?) {
            if (a != b) out += FieldChange(name, a?.toString() ?: "", b?.toString() ?: "")
        }
        cmp("kind", before.kind.key, after.kind.key)
        cmp("date", before.date, after.date)
        cmp("amount_minor", before.amountMinor, after.amountMinor)
        cmp("category_id", before.categoryId, after.categoryId)
        cmp("account_id", before.accountId, after.accountId)
        cmp("to_account_id", before.toAccountId, after.toAccountId)
        cmp("paid_by_member_id", before.paidByMemberId, after.paidByMemberId)
        cmp("to_member_id", before.toMemberId, after.toMemberId)
        cmp("split", before.split, after.split)
        cmp("recurrence", before.recurrence.key, after.recurrence.key)
        cmp("note", before.note, after.note)
        cmp("tags", before.tags, after.tags)
        cmp("status", before.status.key, after.status.key)
        cmp("trip_id", before.tripId, after.tripId)
        for (key in (before.extras.keys + after.extras.keys).sorted()) {
            cmp("extras.$key", before.extras[key], after.extras[key])
        }
        return out
    }
}
