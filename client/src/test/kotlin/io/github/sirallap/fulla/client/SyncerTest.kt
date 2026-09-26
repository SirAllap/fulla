// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.Fixtures.HOUSEHOLD
import io.github.sirallap.fulla.client.Fixtures.expense
import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.sync.Edits
import io.github.sirallap.fulla.client.sync.Syncer
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.model.MoneyMode
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.sync.SyncState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncerTest {

    private val t0 = Instant.parse("2030-01-15T12:00:00Z")

    private class Phone(val backend: FakeBackend, name: String) {
        val store = MemoryStore()
        val syncer = Syncer(store, backend, clientId = name)
    }

    @Test
    fun `a row written on one phone reaches the other`() = runTest {
        val server = FakeBackend()
        val a = Phone(server, "a")
        val b = Phone(server, "b")
        val row = Edits.create(expense(), connected = true, now = t0)
        a.store.rows[row.id] = row

        val report = a.syncer.sync(HOUSEHOLD)
        assertEquals(1, report.pushed)
        b.syncer.sync(HOUSEHOLD)

        assertEquals(row.transaction.amountMinor, b.store.rows[row.id]!!.transaction.amountMinor)
        assertEquals(SyncState.SYNCED, a.store.rows[row.id]!!.state)
    }

    @Test
    fun `a push never moves the cursor`() = runTest {
        val server = FakeBackend()
        val a = Phone(server, "a")
        val b = Phone(server, "b")
        b.store.rows["x"] = Edits.create(expense(id = "x"), true, t0)
        b.syncer.sync(HOUSEHOLD)
        // a pulls nothing yet; pushes its own row, whose server stamp is above b's row.
        val mine = Edits.create(expense(id = "y"), true, t0)
        a.store.rows[mine.id] = mine
        a.syncer.sync(HOUSEHOLD)
        assertTrue("x" in a.store.rows, "the other phone's row was stepped over")
    }

    @Test
    fun `an edit made while a push is in flight is not overwritten by its answer`() = runTest {
        val server = FakeBackend()
        val a = Phone(server, "a")
        val row = Edits.create(expense(amount = 1000), true, t0)
        a.store.rows[row.id] = row
        server.duringPush = {
            server.duringPush = {}
            val held = a.store.rows.getValue(row.id)
            a.store.rows[row.id] = Edits.edit(held, held.transaction.copy(amountMinor = 2000), true, t0.plusSeconds(5))
        }
        a.syncer.sync(HOUSEHOLD)
        // The in-flight answer was dropped by the guard; the extra pass or the next sync sends the edit.
        a.syncer.sync(HOUSEHOLD)
        assertEquals(2000, server.rows.getValue(row.id).amountMinor)
        assertEquals(2000, a.store.rows.getValue(row.id).transaction.amountMinor)
        assertEquals(SyncState.SYNCED, a.store.rows.getValue(row.id).state)
    }

    @Test
    fun `a refused row is held with its reason and not sent again`() = runTest {
        val server = FakeBackend()
        val a = Phone(server, "a")
        val bad = Edits.create(expense(amount = 0), true, t0)
        a.store.rows[bad.id] = bad
        val report = a.syncer.sync(HOUSEHOLD)
        assertEquals(1, report.rejected)
        assertEquals(SyncState.REJECTED, a.store.rows.getValue(bad.id).state)
        assertEquals("validation_failed", a.store.rows.getValue(bad.id).rejectCode)
        a.syncer.sync(HOUSEHOLD)
        assertEquals(1, server.pushes.size)
    }

    @Test
    fun `pulls every page and never stores a cursor beyond the rows applied`() = runTest {
        val server = FakeBackend()
        val a = Phone(server, "a")
        repeat(7) { a.store.rows["r$it"] = Edits.create(expense(id = "r$it"), true, t0) }
        a.syncer.sync(HOUSEHOLD)
        val b = MemoryStore()
        Syncer(b, server, "b", pageSize = 3).sync(HOUSEHOLD)
        assertEquals(7, b.rows.size)
        assertEquals(server.rows.values.maxOf { it.serverSeq }, b.cursor)
    }

    @Test
    fun `a new config arrives with the pull and only when the version moved`() = runTest {
        val server = FakeBackend()
        server.configVersion = 4
        server.config = JsonObject(mapOf("config_version" to JsonPrimitive(4)))
        val a = Phone(server, "a")
        assertTrue(a.syncer.sync(HOUSEHOLD).configChanged)
        assertEquals(4, a.store.version)
        assertTrue(!a.syncer.sync(HOUSEHOLD).configChanged)
    }

    @Test
    fun `a deletion on one phone reaches the other whatever the clocks`() = runTest {
        val server = FakeBackend()
        val a = Phone(server, "a")
        val b = Phone(server, "b")
        val row = Edits.create(expense(), true, t0)
        a.store.rows[row.id] = row
        a.syncer.sync(HOUSEHOLD)
        b.syncer.sync(HOUSEHOLD)
        // b's clock is an hour behind a's.
        b.store.rows[row.id] = Edits.delete(b.store.rows.getValue(row.id), true, t0.minusSeconds(3600))
        b.syncer.sync(HOUSEHOLD)
        a.syncer.sync(HOUSEHOLD)
        assertEquals(Status.DELETED, a.store.rows.getValue(row.id).transaction.status)
    }

    @Test
    fun `phones converge through Syncer whatever the order`() = runTest {
        repeat(60) { seed ->
            val rnd = Random(seed)
            val server = FakeBackend()
            val phones = List(4) { Phone(server, "p$it") }
            val offsets = List(4) { rnd.nextLong(-7200, 7200) }
            var world = t0
            repeat(80) {
                world = world.plusSeconds(rnd.nextLong(1, 600))
                val i = rnd.nextInt(phones.size)
                val p = phones[i]
                val now = world.plusSeconds(offsets[i])
                val ids = p.store.rows.keys.toList()
                when (rnd.nextInt(5)) {
                    0, 1 -> Edits.create(expense(amount = rnd.nextLong(1, 5000)), true, now).let { p.store.rows[it.id] = it }
                    2 -> ids.randomOrNull(rnd)?.let { id ->
                        val held = p.store.rows.getValue(id)
                        p.store.rows[id] = Edits.edit(held, held.transaction.copy(amountMinor = rnd.nextLong(1, 5000)), true, now)
                    }
                    3 -> ids.randomOrNull(rnd)?.let { id -> p.store.rows[id] = Edits.delete(p.store.rows.getValue(id), true, now) }
                    else -> p.syncer.sync(HOUSEHOLD)
                }
            }
            repeat(3) { phones.forEach { it.syncer.sync(HOUSEHOLD) } }
            val truth = server.rows.mapValues { it.value.status to it.value.amountMinor }
            for (p in phones) {
                val held = p.store.rows.filterKeys { it in truth }.mapValues { it.value.transaction.status to it.value.transaction.amountMinor }
                assertEquals(truth, held, "seed $seed: ${p.syncer} diverged")
                assertTrue(p.store.rows.values.none { it.state == SyncState.PENDING }, "seed $seed: something left unsent")
            }
        }
    }

    @Test
    fun `a phone that writes an equal split while another switches to one shared pot ends up with no debt`() = runTest {
        val server = FakeBackend()
        val a = Phone(server, "a")
        val b = Phone(server, "b")
        val older = Edits.create(expense(), true, t0, server.household)
        a.store.rows[older.id] = older
        a.syncer.sync(HOUSEHOLD)
        b.syncer.sync(HOUSEHOLD)
        val stale = server.household
        // b's admin switches the household; the config moves on the server.
        server.household = server.household.copy(moneyMode = MoneyMode.SHARED)
        server.configVersion = 2
        server.config = Wire.bundle(io.github.sirallap.fulla.core.model.Config(2, server.household, Fixtures.ALICE))
        // a has not pulled yet: it still splits between everyone.
        val groceries = Edits.create(expense(), true, t0.plusSeconds(60), stale)
        a.store.rows[groceries.id] = groceries
        assertEquals(Split.Equal(listOf(Fixtures.ALICE, Fixtures.BOB)), groceries.transaction.split)

        a.syncer.sync(HOUSEHOLD)
        b.syncer.sync(HOUSEHOLD)

        val payerOnly = Split.Equal(listOf(Fixtures.ALICE))
        assertEquals(payerOnly, server.rows.getValue(groceries.id).split)
        assertEquals(payerOnly, a.store.rows.getValue(groceries.id).transaction.split, "the push answer is adopted at once")
        assertEquals(SyncState.SYNCED, a.store.rows.getValue(groceries.id).state)
        assertEquals(payerOnly, b.store.rows.getValue(groceries.id).transaction.split)
        assertEquals(MoneyMode.SHARED, Wire.config(a.store.config!!).household.moneyMode, "a heard of the switch with the pull")

        // Edits keep the split their row has: the row from before the switch is not rewritten.
        val held = a.store.rows.getValue(older.id)
        a.store.rows[older.id] = Edits.edit(held, held.transaction.copy(amountMinor = 999), true, t0.plusSeconds(120))
        a.syncer.sync(HOUSEHOLD)
        assertEquals(Split.Equal(listOf(Fixtures.ALICE, Fixtures.BOB)), server.rows.getValue(older.id).split)

        // Now a writes the payer's share itself, and the server has nothing to change.
        val next = Edits.create(expense(), true, t0.plusSeconds(180), Wire.config(a.store.config!!).household)
        assertEquals(payerOnly, next.transaction.split)
        a.store.rows[next.id] = next
        a.syncer.sync(HOUSEHOLD)
        assertEquals(payerOnly, server.rows.getValue(next.id).split)
    }

    @Test
    fun `a household that chose one pot on this phone keeps its history when it is shared`() = runTest {
        // Alice's phone-only household: Bob without an account, an expense they split, then the pot chosen
        // with "Settle it now". All of it LOCAL_ONLY.
        var local = LocalHousehold.create("Demo household", "EUR", "en-GB", "Alice", "A", 0)
        val me = Wire.config(local).meMemberId!!
        local = LocalHousehold.upsertMember(local, kotlinx.serialization.json.buildJsonObject {
            put("id", Fixtures.BOB); put("display_name", "Bob"); put("initials", "B")
        })
        val both = Split.Equal(listOf(me, Fixtures.BOB))
        val food = Edits.create(expense(amount = 10000).copy(paidByMemberId = me, split = both), false, t0, Wire.config(local).household)
        val settle = Edits.create(expense(amount = 5000).copy(kind = io.github.sirallap.fulla.core.model.TransactionKind.SETTLEMENT,
            categoryId = null, split = null, paidByMemberId = Fixtures.BOB, toMemberId = me), false, t0.plusSeconds(1))
        local = LocalHousehold.updateHousehold(local, kotlinx.serialization.json.buildJsonObject { put("money_mode", "shared") })

        // Sharing it: the upload says nothing about the pot.
        val upload = LocalHousehold.forUpload(local)
        assertTrue("money_mode" !in (upload["household"] as JsonObject))
        val server = FakeBackend()
        val serverBundle = Wire.bundle(Wire.config(upload))
        var stored = LocalHousehold.afterUpload(local, serverBundle)
        assertEquals(MoneyMode.SHARED, LocalHousehold.config(stored).household.moneyMode, "the phone already works as one pot")

        val a = Phone(server, "a")
        Edits.connect(listOf(food, settle)).forEach { a.store.rows[it.id] = it }
        // Before the first push nothing is set on the server.
        assertNull(LocalHousehold.applyDeferred(stored, unsent = a.store.pending(HOUSEHOLD).size) { error("too early") })
        a.syncer.sync(HOUSEHOLD)

        // The history arrived as it was: the split kept, the settlement accepted.
        assertEquals(both, server.rows.getValue(food.id).split)
        assertEquals(SyncState.SYNCED, a.store.rows.getValue(settle.id).state)
        assertTrue(io.github.sirallap.fulla.core.balance.Balances.of(server.rows.values, listOf(me, Fixtures.BOB)).all { it.balanceMinor == 0L })

        // Only now the pot is set on the server, and the waiting mark goes.
        stored = LocalHousehold.applyDeferred(stored, unsent = a.store.pending(HOUSEHOLD).size) { patch ->
            assertEquals("shared", (patch["money_mode"] as JsonPrimitive).content)
            server.household = server.household.copy(moneyMode = MoneyMode.SHARED)
            Wire.bundle(Wire.config(serverBundle).let { it.copy(household = it.household.copy(moneyMode = MoneyMode.SHARED)) })
        }!!
        assertNull(LocalHousehold.deferredMoneyMode(stored))
        assertEquals(MoneyMode.SHARED, Wire.config(stored).household.moneyMode)
        // A config pulled in the meantime would not have lost it.
        assertEquals(MoneyMode.SHARED, LocalHousehold.deferredMoneyMode(LocalHousehold.keepDeferred(LocalHousehold.afterUpload(local, serverBundle), serverBundle)))
    }

    @Test
    fun `an old-format row's own edit does not clear a trip another phone set`() = runTest {
        val server = FakeBackend()
        val a = Phone(server, "a")
        val b = Phone(server, "b")
        val row = Edits.create(expense(), true, t0)
        a.store.rows[row.id] = row
        a.syncer.sync(HOUSEHOLD)
        b.syncer.sync(HOUSEHOLD)

        // Someone sets a trip on the server, through whatever phone knows about trips.
        val withTrip = server.rows.getValue(row.id)
        server.rows[row.id] = withTrip.copy(tripId = "00000000-0000-4000-8000-000000000501", serverSeq = withTrip.serverSeq + 1000)
        a.syncer.sync(HOUSEHOLD)
        assertEquals("00000000-0000-4000-8000-000000000501", a.store.rows.getValue(row.id).transaction.tripId)

        // b's own copy was decoded from a shape that never carried trip_id at
        // all (an old app version's own row): tripKnown stays false through
        // this edit, as Wire's decoder would leave it.
        val held = b.store.rows.getValue(row.id)
        val stale = held.transaction.copy(note = "edited by an old phone", tripId = null, tripKnown = false)
        b.store.rows[row.id] = Edits.edit(held, stale, true, t0.plusSeconds(60))
        b.syncer.sync(HOUSEHOLD)

        assertEquals("00000000-0000-4000-8000-000000000501", server.rows.getValue(row.id).tripId, "an absent trip_id must not clear a trip another phone set")
        assertEquals("00000000-0000-4000-8000-000000000501", b.store.rows.getValue(row.id).transaction.tripId)
    }

    @Test
    fun `nothing in local mode is ever sent`() = runTest {
        val server = FakeBackend()
        val a = Phone(server, "a")
        val row = Edits.create(expense(), connected = false, now = t0)
        a.store.rows[row.id] = row
        a.syncer.sync(HOUSEHOLD)
        assertTrue(server.pushes.isEmpty())
        val connected = Edits.connect(a.store.rows.values.toList())
        connected.forEach { a.store.rows[it.id] = it }
        a.syncer.sync(HOUSEHOLD)
        assertEquals(setOf(row.id), server.rows.keys)
        assertNull(connected.single().baseClientUpdatedAt)
    }
}
