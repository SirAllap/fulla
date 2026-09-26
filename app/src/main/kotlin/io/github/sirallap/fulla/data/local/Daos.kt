// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class HouseholdDao {
    @Query("select * from households order by id")
    abstract fun observeAll(): Flow<List<HouseholdEntity>>

    @Query("select * from households where id = :id")
    abstract fun observe(id: String): Flow<HouseholdEntity?>

    @Query("select * from households where id = :id")
    abstract suspend fun get(id: String): HouseholdEntity?

    @Query("select * from households")
    abstract suspend fun all(): List<HouseholdEntity>

    @Upsert
    abstract suspend fun upsert(household: HouseholdEntity)

    @Query("update households set config_json = :json, config_version = :version where id = :id")
    abstract suspend fun setConfig(id: String, json: String, version: Int)

    @Query("update households set cursor = :cursor where id = :id and cursor <= :cursor")
    abstract suspend fun advanceCursor(id: String, cursor: Long)

    /** Forgetting a whole household (leaving it, or a household that was removed). */
    @Query("delete from households where id = :id")
    abstract suspend fun forget(id: String)

    @Query("update households set last_sync_at = :at, last_error = :error where id = :id")
    abstract suspend fun setSyncResult(id: String, at: Long?, error: String?)

    @Query("update households set mode = :mode where id = :id")
    abstract suspend fun setMode(id: String, mode: String)

    /**
     * Trips' one-time forced re-pull (H3, docs/CLAUDE.md "the cursor moves on
     * a pull"): a household still owing it (migrated in with the column
     * false) gets cursor and config_version back to 0, so the next sync pulls
     * everything again and every row comes back with its trip_id. The where
     * clause makes this idempotent: a second call is a no-op.
     */
    @Query("update households set cursor = 0, config_version = 0, trips_repulled = 1 where id = :id and trips_repulled = 0")
    abstract suspend fun resetForTripsRepull(id: String)
}

@Dao
abstract class TransactionDao {
    @Query("select * from transactions where household_id = :householdId order by date desc, client_updated_at desc")
    abstract fun observeAll(householdId: String): Flow<List<TransactionEntity>>

    @Query("select * from transactions where household_id = :householdId")
    abstract suspend fun all(householdId: String): List<TransactionEntity>

    @Query("select * from transactions where id = :id")
    abstract suspend fun get(id: String): TransactionEntity?

    @Query("select * from transactions where household_id = :householdId and id in (:ids)")
    abstract suspend fun byIds(householdId: String, ids: List<String>): List<TransactionEntity>

    @Query("select * from transactions where household_id = :householdId and state = :state")
    abstract suspend fun inState(householdId: String, state: String): List<TransactionEntity>

    @Query("select count(*) from transactions where household_id = :householdId and state = :state")
    abstract fun observeCount(householdId: String, state: String): Flow<Int>

    @Query("select id from transactions where household_id = :householdId")
    abstract suspend fun ids(householdId: String): List<String>

    @Upsert
    abstract suspend fun upsert(rows: List<TransactionEntity>)

    @Query("delete from transactions where household_id = :householdId")
    abstract suspend fun forget(householdId: String)

    /**
     * Writes each row only if the row stored under its id still has the stamp
     * the caller read (null: there was none). The check and the write are one
     * transaction, so an edit made in between is never overwritten.
     */
    @Transaction
    open suspend fun writeGuarded(householdId: String, rows: List<Pair<String?, TransactionEntity>>) {
        if (rows.isEmpty()) return
        val current = rows.map { it.second.id }.chunked(500)
            .flatMap { byIds(householdId, it) }
            .associate { it.id to it.clientUpdatedAt }
        upsert(rows.filter { (expected, row) -> current[row.id] == expected }.map { it.second })
    }
}

@Dao
abstract class ConflictDao {
    @Query("select * from conflict_notes where household_id = :householdId and not seen order by created_at desc")
    abstract fun observeUnseen(householdId: String): Flow<List<ConflictNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(notes: List<ConflictNoteEntity>)

    @Query("update conflict_notes set seen = 1 where household_id = :householdId")
    abstract suspend fun markAllSeen(householdId: String)

    @Query("delete from conflict_notes where household_id = :householdId")
    abstract suspend fun forget(householdId: String)
}
