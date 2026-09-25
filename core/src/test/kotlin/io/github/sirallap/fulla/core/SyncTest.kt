// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.sync.Conflict
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.Mutation
import io.github.sirallap.fulla.core.sync.MutationType
import io.github.sirallap.fulla.core.sync.PushResult
import io.github.sirallap.fulla.core.sync.SyncEngine
import io.github.sirallap.fulla.core.sync.SyncState
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A server that behaves like fulla_sync_push and fulla_sync_pull
 * (supabase/migrations/0007_sync.sql), including the parts that matter most:
 * deletes are applied without comparing clocks, an older edit is refused and
 * answered with the stored row, and the cursor is the highest server_seq a
 * pull returned. Modelling any of that more conveniently would hide exactly
 * the bugs this suite exists to find.
 */
class FakeServer {
    val rows = LinkedHashMap<String, Transaction>()
    private var seq = 0L

    fun push(mutations: List<Mutation>): List<PushResult> {
        require(mutations.size <= SyncEngine.MAX_BATCH)
        return mutations.map { m ->
            val prior = rows[m.transaction.id]
            when {
                m.type == MutationType.DELETE && prior == null ->
                    PushResult(m.mutationId, m.transaction.id, ok = true, applied = false)
                m.type == MutationType.DELETE && prior!!.status == Status.DELETED ->
                    PushResult(m.mutationId, m.transaction.id, ok = true, applied = false, serverTransaction = prior)
                m.type == MutationType.DELETE -> {
                    rows[prior!!.id] = prior.copy(status = Status.DELETED, clientUpdatedAt = m.clientUpdatedAt, serverSeq = ++seq)
                    PushResult(m.mutationId, prior.id, ok = true, applied = true)
                }
                prior == null -> {
                    rows[m.transaction.id] = m.transaction.copy(clientUpdatedAt = m.clientUpdatedAt, serverSeq = ++seq)
                    PushResult(m.mutationId, m.transaction.id, ok = true, applied = true)
                }
                m.clientUpdatedAt < prior.clientUpdatedAt ->
                    PushResult(m.mutationId, prior.id, ok = true, applied = false,
                        conflict = Conflict(Conflict.Winner.SERVER, SyncEngine.diff(m.transaction, prior)),
                        serverTransaction = prior)
                else -> {
                    rows[prior.id] = m.transaction.copy(clientUpdatedAt = m.clientUpdatedAt, serverSeq = ++seq)
                    val collided = m.baseClientUpdatedAt != prior.clientUpdatedAt
                    PushResult(m.mutationId, prior.id, ok = true, applied = true,
                        conflict = if (collided) Conflict(Conflict.Winner.CLIENT, SyncEngine.diff(prior, m.transaction)) else null)
                }
            }
        }
    }

    data class Page(val rows: List<Transaction>, val cursor: Long, val hasMore: Boolean)

    fun pull(since: Long, limit: Int = 500): Page {
        val page = rows.values.filter { it.serverSeq > since }.sortedBy { it.serverSeq }.take(limit)
        val cursor = page.maxOfOrNull { it.serverSeq } ?: since
        return Page(page, cursor, rows.values.any { it.serverSeq > cursor })
    }
}

/** A phone: its own rows, its cursor, and a clock that may be wrong. */
class Phone(val name: String, private val server: FakeServer, var clockOffsetMinutes: Long = 0) {
    val rows = LinkedHashMap<String, LocalTransaction>()
    var cursor = 0L
    var online = true
    val notes = mutableListOf<Any>()
    private var mutations = 0

    fun now(world: Instant): Instant = world.plusSeconds(clockOffsetMinutes * 60)

    fun write(tx: Transaction, world: Instant) {
        val prior = rows[tx.id]
        val stamp = SyncEngine.stampFor(prior?.transaction?.clientUpdatedAt, now(world))
        val base = when (prior?.state) {
            null -> null
            SyncState.PENDING -> prior.baseClientUpdatedAt
            else -> prior.transaction.clientUpdatedAt
        }
        rows[tx.id] = LocalTransaction(tx.copy(clientUpdatedAt = stamp, createdAt = prior?.transaction?.createdAt ?: stamp),
            SyncState.PENDING, base)
    }

    fun sync(pageSize: Int = 500) {
        if (!online) return
        push()
        do {
            val page = server.pull(cursor, pageSize)
            val merged = SyncEngine.merge(rows.values.toList(), page.rows)
            merged.rows.forEach { rows[it.id] = it }
            notes.addAll(merged.notes)
            cursor = SyncEngine.nextCursor(cursor, page.cursor)
        } while (page.hasMore)
        if (SyncEngine.pending(rows.values.toList()).isNotEmpty()) push()
    }

    fun push() {
        val pending = SyncEngine.pending(rows.values.toList())
        for (batch in pending.chunked(SyncEngine.MAX_BATCH)) {
            val muts = batch.map { SyncEngine.toMutation(it, name, "$name-${mutations++}") }
            val outcome = SyncEngine.applyPush(batch, server.push(muts))
            outcome.rows.forEach { rows[it.id] = it }
            notes.addAll(outcome.notes)
            cursor = SyncEngine.cursorAfterPush(cursor)
        }
    }

    fun visible(): Map<String, Pair<Status, String>> =
        rows.values.associate { it.id to (it.transaction.status to it.transaction.note) }
}

class SyncTest {

    private val t0: Instant = Instant.parse("2030-01-15T12:00:00Z")
    private fun at(minutes: Long): Instant = t0.plusSeconds(minutes * 60)

    @Test
    fun `a row written offline hours ago is not stepped over`() {
        val server = FakeServer()
        val alice = Phone("alice", server)
        val bob = Phone("bob", server)
        bob.write(Fixtures.expense(), at(600))
        bob.sync()
        alice.sync()
        // Bob was offline all morning; his edit carries a clock reading from hours ago.
        val late = Fixtures.expense()
        bob.write(late, at(0))
        bob.sync()
        alice.sync()
        assertTrue(late.id in alice.rows)
    }

    @Test
    fun `the push never moves the pull cursor`() {
        val server = FakeServer()
        val alice = Phone("alice", server)
        val bob = Phone("bob", server)
        alice.write(Fixtures.expense(), at(0))
        alice.sync()
        val bobs = Fixtures.expense()
        bob.write(bobs, at(1))
        bob.sync()
        // Alice pushes before pulling. If her push moved her cursor, she would
        // skip Bob's row for good.
        alice.write(Fixtures.expense(), at(2))
        alice.push()
        alice.sync()
        assertTrue(bobs.id in alice.rows)
        assertEquals(7L, SyncEngine.cursorAfterPush(7L))
    }

    @Test
    fun `the cursor only moves forward and only to a value the server issued`() {
        assertEquals(10L, SyncEngine.nextCursor(10L, 4L))
        assertEquals(12L, SyncEngine.nextCursor(10L, 12L))
    }

    @Test
    fun `an incoming tombstone beats a synced local row whatever the clocks say`() {
        val tx = Fixtures.expense(stamp = "2030-01-15T12:00:00.000Z")
        val local = LocalTransaction(tx.copy(clientUpdatedAt = "2030-01-15T13:00:00.000Z"), SyncState.SYNCED)
        val remote = tx.copy(status = Status.DELETED, clientUpdatedAt = "2030-01-15T12:30:00.000Z")
        val merged = SyncEngine.merge(listOf(local), listOf(remote))
        assertEquals(Status.DELETED, merged.rows.single().transaction.status)
        assertEquals(0, merged.requeued)
    }

    @Test
    fun `a pending local edit against a remote tombstone keeps last write wins and raises a note`() {
        val tx = Fixtures.expense()
        val local = LocalTransaction(tx.copy(note = "edited", clientUpdatedAt = "2030-01-15T12:00:00.000Z"), SyncState.PENDING)
        val olderTombstone = tx.copy(status = Status.DELETED, clientUpdatedAt = "2030-01-15T11:00:00.000Z")
        assertTrue(SyncEngine.merge(listOf(local), listOf(olderTombstone)).rows.isEmpty(), "the pending edit stays to be pushed")
        val newerTombstone = tx.copy(status = Status.DELETED, clientUpdatedAt = "2030-01-15T13:00:00.000Z")
        val merged = SyncEngine.merge(listOf(local), listOf(newerTombstone))
        assertEquals(Status.DELETED, merged.rows.single().transaction.status)
        assertEquals(listOf("note", "status"), merged.notes.single().changes.map { it.field })
    }

    @Test
    fun `a local win over a synced row re-queues it`() {
        val tx = Fixtures.expense()
        val local = LocalTransaction(tx.copy(note = "mine", clientUpdatedAt = "2030-01-15T13:00:00.000Z"), SyncState.SYNCED)
        val remote = tx.copy(note = "theirs", clientUpdatedAt = "2030-01-15T12:00:00.000Z")
        val merged = SyncEngine.merge(listOf(local), listOf(remote))
        assertEquals(SyncState.PENDING, merged.rows.single().state)
        assertEquals("mine", merged.rows.single().transaction.note)
        assertEquals(1, merged.requeued)
    }

    @Test
    fun `a rejected row is never re-queued by a merge`() {
        val tx = Fixtures.expense()
        val local = LocalTransaction(tx.copy(clientUpdatedAt = "2030-01-15T13:00:00.000Z"), SyncState.REJECTED, rejectCode = "validation_failed")
        val merged = SyncEngine.merge(listOf(local), listOf(tx))
        assertTrue(merged.rows.isEmpty())
        assertEquals(0, merged.requeued)
    }

    @Test
    fun `a remote win over a pending edit raises a note listing every overwritten field`() {
        val tx = Fixtures.expense()
        val local = LocalTransaction(tx.copy(note = "a", amountMinor = 1, extras = mapOf("shop" to "X"),
            clientUpdatedAt = "2030-01-15T12:00:00.000Z"), SyncState.PENDING)
        val remote = tx.copy(note = "b", amountMinor = 2, extras = mapOf("shop" to "Y"), clientUpdatedAt = "2030-01-15T13:00:00.000Z")
        val note = SyncEngine.merge(listOf(local), listOf(remote)).notes.single()
        assertEquals(listOf("amount_minor", "note", "extras.shop"), note.changes.map { it.field })
        assertEquals(Conflict.Winner.SERVER, note.keptVersion)
    }

    @Test
    fun `an edit refused as older adopts the server's row at once`() {
        val server = FakeServer()
        val alice = Phone("alice", server, clockOffsetMinutes = 0)
        val bob = Phone("bob", server, clockOffsetMinutes = -120)
        val tx = Fixtures.expense()
        alice.write(tx, at(0))
        alice.sync()
        bob.sync()
        alice.write(tx.copy(note = "alice's"), at(10))
        alice.sync()
        // Bob's clock is two hours behind. Without the stamp rule his edit,
        // made after he saw Alice's first version, would lose to it on the clock.
        bob.write(tx.copy(note = "bob's"), at(20))
        bob.sync()
        alice.sync()
        assertEquals(alice.visible(), bob.visible())
        assertEquals(bob.visible(), server.rows.mapValues { it.value.status to it.value.note })
    }

    @Test
    fun `an edit is always stamped after the version it edits`() {
        val stamp = SyncEngine.stampFor("2030-01-15T12:00:00.000Z", Instant.parse("2030-01-15T10:00:00Z"))
        assertEquals("2030-01-15T12:00:00.001Z", stamp)
        assertEquals("2030-01-15T13:00:00.000Z", SyncEngine.stampFor("2030-01-15T12:00:00.000Z", Instant.parse("2030-01-15T13:00:00Z")))
    }

    @Test
    fun `pushes go in batches of at most 100`() {
        val server = FakeServer()
        val alice = Phone("alice", server)
        repeat(250) { alice.write(Fixtures.expense(), at(it.toLong())) }
        alice.sync(pageSize = 40)
        assertEquals(250, server.rows.size)
        assertTrue(alice.rows.values.all { it.state == SyncState.SYNCED })
        assertEquals(listOf(100, 100, 50), SyncEngine.batches(List(250) { SyncEngine.toMutation(alice.rows.values.first(), "a", "$it") }).map { it.size })
    }

    @Test
    fun `recurring occurrences generated on two devices share one id`() {
        val rule = "00000000-0000-4000-8000-00000000abcd"
        val a = DeterministicId.occurrence(rule, LocalDate.of(2030, 2, 5))
        val b = DeterministicId.occurrence(rule, LocalDate.of(2030, 2, 5))
        assertEquals(a, b)
        assertTrue(a != DeterministicId.occurrence(rule, LocalDate.of(2030, 3, 5)))
    }

    @Test
    fun `backoff doubles and stops at fifteen minutes`() {
        assertEquals(2_000L, SyncEngine.backoffMillis(0))
        assertEquals(4_000L, SyncEngine.backoffMillis(1))
        assertEquals(15 * 60_000L, SyncEngine.backoffMillis(30))
    }

    /**
     * Ten phones, clocks up to two hours out, going on and offline, creating,
     * editing, deleting and restoring shared rows in random order. Once they
     * all reconnect and a full round of syncs changes nothing, every phone
     * must hold exactly what the server holds. Seeded per
     * round, so a failure names the seed that reproduces it.
     */
    @Test
    fun `ten phones converge whatever the order`() {
        for (seed in 1..300) {
            val random = Random(seed)
            val server = FakeServer()
            val phones = (0 until 10).map { Phone("p$it", server, clockOffsetMinutes = random.nextLong(-120, 121)) }
            var minute = 0L
            repeat(200) {
                minute += random.nextLong(0, 5)
                val phone = phones.random(random)
                when (random.nextInt(10)) {
                    0 -> phone.online = !phone.online
                    1, 2 -> phone.write(Fixtures.expense(), at(minute))
                    3, 4, 5 -> phone.rows.values.filter { it.transaction.isActive }.randomOrNull(random)?.let {
                        phone.write(it.transaction.copy(note = "edit $seed/$minute by ${phone.name}"), at(minute))
                    }
                    6 -> phone.rows.values.filter { it.transaction.isActive }.randomOrNull(random)?.let {
                        phone.write(it.transaction.copy(status = Status.DELETED), at(minute))
                    }
                    7 -> phone.rows.values.filter { !it.transaction.isActive }.randomOrNull(random)?.let {
                        phone.write(it.transaction.copy(status = Status.ACTIVE), at(minute))
                    }
                    else -> phone.sync(pageSize = 1 + random.nextInt(20))
                }
            }
            // Everyone reconnects and syncs until a whole round changes
            // nothing on the server. A round can end with a push that phones
            // earlier in the round have not seen yet, so one round is not
            // enough; more than a few would mean phones undoing each other.
            phones.forEach { it.online = true }
            var rounds = 0
            do {
                val before = server.rows.values.maxOfOrNull { it.serverSeq } ?: 0L
                phones.shuffled(random).forEach { it.sync() }
                rounds++
            } while ((server.rows.values.maxOfOrNull { it.serverSeq } ?: 0L) != before && rounds < 5)
            assertTrue(rounds < 5, "seed $seed: phones kept changing the server after $rounds rounds")
            val truth = server.rows.mapValues { it.value.status to it.value.note }
            for (p in phones) {
                // A row created and deleted on one phone before it was ever
                // pushed never reaches the server: its delete is answered
                // "not found". It stays on that phone as a tombstone nobody sees.
                val neverSent = p.visible().filterKeys { it !in truth }
                assertTrue(neverSent.values.all { it.first == Status.DELETED }, "seed $seed: ${p.name} holds a live row the server lacks")
                val mine = p.visible() - neverSent.keys
                val differing = (truth.keys + mine.keys).filter { truth[it] != mine[it] }
                    .map { "$it server=${truth[it]} phone=${mine[it]} state=${p.rows[it]?.state}" }
                assertTrue(differing.isEmpty(), "seed $seed: ${p.name} differs from the server: $differing")
                assertNull(p.rows.values.firstOrNull { it.state != SyncState.SYNCED }, "seed $seed: ${p.name} has unsynced rows")
            }
        }
    }
}
