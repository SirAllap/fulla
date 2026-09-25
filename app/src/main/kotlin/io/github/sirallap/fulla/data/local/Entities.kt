// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A household this phone holds. Its structure is the config bundle exactly as
 * the server sends it (or as local mode builds it): one format, read by one
 * mapper, and uploaded unchanged when local mode becomes shared.
 */
@Entity(tableName = "households")
data class HouseholdEntity(
    @PrimaryKey val id: String,
    /** "local" or "connected". */
    val mode: String,
    @ColumnInfo(name = "config_json") val configJson: String,
    @ColumnInfo(name = "config_version") val configVersion: Int,
    /** The highest server_seq a pull returned. Moves on pulls only. */
    val cursor: Long = 0,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

/**
 * A transaction: the whole row as wire JSON, plus the columns queries and the
 * sync need. The JSON is the truth; the columns are copies of it.
 */
@Entity(
    tableName = "transactions",
    indices = [Index("household_id", "status"), Index("household_id", "state")],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val json: String,
    val date: String,
    val status: String,
    @ColumnInfo(name = "client_updated_at") val clientUpdatedAt: String,
    /** A SyncState name. */
    val state: String,
    @ColumnInfo(name = "base_client_updated_at") val baseClientUpdatedAt: String?,
    @ColumnInfo(name = "reject_code") val rejectCode: String?,
    @ColumnInfo(name = "reject_message") val rejectMessage: String?,
)

/** What a sync overwrote, kept until the person has seen it. */
@Entity(tableName = "conflict_notes", indices = [Index("household_id")])
data class ConflictNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "household_id") val householdId: String,
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    /** "client" or "server": whose version was kept. */
    val kept: String,
    /** [{field, before, after}] */
    @ColumnInfo(name = "changes_json") val changesJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val seen: Boolean = false,
)
