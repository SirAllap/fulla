// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.local

import androidx.room.withTransaction
import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.sync.GuardedWrite
import io.github.sirallap.fulla.client.sync.SyncStore
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.sync.Conflict
import io.github.sirallap.fulla.core.sync.ConflictNote
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Rows to and from Room. The JSON is the row; the columns are copies for queries. */
object Rows {
    fun entity(householdId: String, row: LocalTransaction): TransactionEntity = TransactionEntity(
        id = row.id,
        householdId = householdId,
        json = Wire.transaction(row.transaction).plus("server_seq" to JsonPrimitive(row.transaction.serverSeq))
            .let { JsonObject(it) }.toString(),
        date = row.transaction.date.toString(),
        status = row.transaction.status.key,
        clientUpdatedAt = row.transaction.clientUpdatedAt,
        state = row.state.name,
        baseClientUpdatedAt = row.baseClientUpdatedAt,
        rejectCode = row.rejectCode,
        rejectMessage = row.rejectMessage,
    )

    fun local(e: TransactionEntity): LocalTransaction = LocalTransaction(
        transaction = Wire.transaction(Wire.json.parseToJsonElement(e.json).jsonObject),
        state = runCatching { SyncState.valueOf(e.state) }.getOrDefault(SyncState.PENDING),
        baseClientUpdatedAt = e.baseClientUpdatedAt,
        rejectCode = e.rejectCode,
        rejectMessage = e.rejectMessage,
    )

    fun note(householdId: String, n: ConflictNote, at: Long) = ConflictNoteEntity(
        householdId = householdId,
        transactionId = n.transactionId,
        kept = if (n.keptVersion == Conflict.Winner.SERVER) "server" else "client",
        changesJson = JsonArray(n.changes.map {
            JsonObject(mapOf("field" to JsonPrimitive(it.field), "before" to JsonPrimitive(it.before), "after" to JsonPrimitive(it.after)))
        }).toString(),
        createdAt = at,
    )
}

/** The sync's view of Room. Every method that writes is one transaction. */
class RoomSyncStore(private val db: FullaDatabase, private val clock: () -> Long = System::currentTimeMillis) : SyncStore {

    override suspend fun cursor(householdId: String): Long = db.households().get(householdId)?.cursor ?: 0

    override suspend fun configVersion(householdId: String): Int = db.households().get(householdId)?.configVersion ?: 0

    override suspend fun pending(householdId: String): List<LocalTransaction> =
        db.transactions().inState(householdId, SyncState.PENDING.name).map(Rows::local)

    override suspend fun held(householdId: String, ids: Collection<String>): List<LocalTransaction> =
        ids.toList().chunked(500).flatMap { db.transactions().byIds(householdId, it) }.map(Rows::local)

    override suspend fun settle(householdId: String, writes: List<GuardedWrite>, notes: List<ConflictNote>) {
        db.withTransaction {
            db.transactions().writeGuarded(householdId, writes.map { it.expectedStamp to Rows.entity(householdId, it.row) })
            if (notes.isNotEmpty()) db.conflicts().insert(notes.map { Rows.note(householdId, it, clock()) })
        }
    }

    override suspend fun applyPull(
        householdId: String,
        config: JsonObject?,
        configVersion: Int,
        writes: List<GuardedWrite>,
        notes: List<ConflictNote>,
        cursor: Long,
    ) {
        db.withTransaction {
            if (config != null) {
                // A pot chosen before the household was shared stays waiting until it is set (Ledger.finishSharing).
                val stored = db.households().get(householdId)?.configJson?.let { Wire.json.parseToJsonElement(it).jsonObject }
                db.households().setConfig(householdId, LocalHousehold.keepDeferred(stored, config).toString(), configVersion)
            }
            db.transactions().writeGuarded(householdId, writes.map { it.expectedStamp to Rows.entity(householdId, it.row) })
            if (notes.isNotEmpty()) db.conflicts().insert(notes.map { Rows.note(householdId, it, clock()) })
            db.households().advanceCursor(householdId, cursor)
        }
    }
}
