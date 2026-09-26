// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sirallap.fulla.client.sync.GuardedWrite
import io.github.sirallap.fulla.client.sync.Edits
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.data.local.FullaDatabase
import io.github.sirallap.fulla.data.local.HouseholdEntity
import io.github.sirallap.fulla.data.local.RoomSyncStore
import io.github.sirallap.fulla.data.local.Rows
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/** The store contract that client's SyncerTest assumes, held against Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSyncStoreTest {
    private lateinit var db: FullaDatabase
    private lateinit var store: RoomSyncStore
    private val household = "00000000-0000-4000-8000-000000000001"
    private val t0 = Instant.parse("2030-01-15T12:00:00Z")

    private fun expense(amount: Long) = Transaction(
        id = "00000000-0000-4000-9000-000000000001", kind = TransactionKind.EXPENSE, date = LocalDate.of(2030, 1, 15),
        amountMinor = amount, note = "GROCERY STORE 01", createdAt = "", clientUpdatedAt = "",
    )

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FullaDatabase::class.java)
            .allowMainThreadQueries().build()
        store = RoomSyncStore(db)
        runTest { db.households().upsert(HouseholdEntity(household, "connected", "{}", 1)) }
    }

    @After
    fun close() = db.close()

    @Test
    fun `a write whose guard no longer holds is dropped`() = runTest {
        val row = Edits.create(expense(1000), connected = true, now = t0)
        db.transactions().upsert(listOf(Rows.entity(household, row)))
        val edited = Edits.edit(row, row.transaction.copy(amountMinor = 2000), connected = true, now = t0.plusSeconds(5))
        db.transactions().upsert(listOf(Rows.entity(household, edited)))

        // The answer to the push of the first version arrives after the edit.
        store.settle(household, listOf(GuardedWrite(row.transaction.clientUpdatedAt, row.copy(state = SyncState.SYNCED))), emptyList())

        val held = store.held(household, listOf(row.id)).single()
        assertEquals(2000, held.transaction.amountMinor)
        assertEquals(SyncState.PENDING, held.state)
    }

    @Test
    fun `a row survives Room exactly`() = runTest {
        val stamp = "2030-01-15T12:00:00.000Z"
        val row = LocalTransaction(expense(1234).copy(serverSeq = 42, tags = listOf("trip"), createdAt = stamp, clientUpdatedAt = stamp),
            SyncState.SYNCED, baseClientUpdatedAt = stamp)
        store.settle(household, listOf(GuardedWrite(null, row)), emptyList())
        assertEquals(row, store.held(household, listOf(row.id)).single())
    }

    @Test
    fun `the cursor only moves forward, and only with a pull`() = runTest {
        store.applyPull(household, null, 1, emptyList(), emptyList(), cursor = 40)
        store.applyPull(household, null, 1, emptyList(), emptyList(), cursor = 12)
        assertEquals(40, store.cursor(household))
    }

    @Test
    fun `trips' one-time re-pull fires once, for a household still owing it`() = runTest {
        // A household stored before this column existed, migrated in as still owing it (H3).
        val stale = "00000000-0000-4000-8000-000000000002"
        db.households().upsert(HouseholdEntity(stale, "connected", "{}", 3, cursor = 40, tripsRepulled = false))
        db.households().resetForTripsRepull(stale)
        val once = db.households().get(stale)!!
        assertEquals(0, once.cursor)
        assertEquals(0, once.configVersion)
        assertEquals(true, once.tripsRepulled)

        // A later pull moves the cursor again; a second call must not reset it.
        db.households().advanceCursor(stale, 99)
        db.households().resetForTripsRepull(stale)
        assertEquals(99, db.households().get(stale)!!.cursor)

        // A household created by the current client never owed it in the first place.
        assertEquals(true, db.households().get(household)!!.tripsRepulled)
        db.households().resetForTripsRepull(household)
        assertEquals(1, db.households().get(household)!!.configVersion)
    }
}
