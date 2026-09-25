// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.model.Household
import io.github.sirallap.fulla.core.model.MoneyMode
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.split.SharedPot
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
 * answered with the stored row, a new row goes through the shared pot rule
 * (fulla.shared_pot_for_new, 0011_money_mode.sql) and comes back as stored
 * when that changed it (new to the phone: no base, even if the id is already
 * stored), and the cursor is the highest server_seq a pull
 * returned. Modelling any of that more conveniently would hide exactly the
 * bugs this suite exists to find.
 */
class FakeServer {
    val rows = LinkedHashMap<String, Transaction>()
    private var seq = 0L

    /** The household as the server holds it; an admin switching money_mode changes it here. */
    var household: Household = Fixtures.config().household

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
                prior == null && SharedPot.refusesNew(m.transaction, household) ->
                    PushResult(m.mutationId, m.transaction.id, ok = false, applied = false,
                        errorCode = "validation_failed", errorMessage = SharedPot.NOTHING_TO_SETTLE)
                prior == null -> {
                    val kept = SharedPot.forNew(m.transaction, household)
                    val stored = kept.copy(clientUpdatedAt = m.clientUpdatedAt, serverSeq = ++seq)
                    rows[m.transaction.id] = stored
                    PushResult(m.mutationId, m.transaction.id, ok = true, applied = true,
                        serverTransaction = if (kept != m.transaction) stored else null)
                }
                m.clientUpdatedAt < prior.clientUpdatedAt ->
                    PushResult(m.mutationId, prior.id, ok = true, applied = false,
                        conflict = Conflict(Conflict.Winner.SERVER, SyncEngine.diff(m.transaction, prior)),
                        serverTransaction = prior)
                else -> {
                    // A phone that never saw the stored row wrote it as new (same recurring occurrence, same import line).
                    val kept = if (m.baseClientUpdatedAt == null) SharedPot.forNew(m.transaction, household) else m.transaction
                    val stored = kept.copy(clientUpdatedAt = m.clientUpdatedAt, serverSeq = ++seq)
                    rows[prior.id] = stored
                    val collided = m.baseClientUpdatedAt != prior.clientUpdatedAt
                    PushResult(m.mutationId, prior.id, ok = true, applied = true,
                        conflict = if (collided) Conflict(Conflict.Winner.CLIENT, SyncEngine.diff(prior, kept)) else null,
                        serverTransaction = if (kept != m.transaction) stored else null)
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

/** A phone: its own rows, its cursor, what it last heard of the household, and a clock that may be wrong. */
class Phone(val name: String, private val server: FakeServer, var clockOffsetMinutes: Long = 0) {
    val rows = LinkedHashMap<String, LocalTransaction>()
    var cursor = 0L
    var household: Household = server.household
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
            household = server.household
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

    /** A new row, as the app creates one: through the shared pot rule, as far as this phone knows the household. */
    fun create(tx: Transaction, world: Instant) = write(SharedPot.forNew(tx, household), world)

    fun visible(): Map<String, Seen> = rows.values.associate { it.id to it.transaction.seen() }
}

typealias Seen = Triple<Status, String, Split?>

fun Transaction.seen(): Seen = Triple(status, note, split)


class SyncTest {

    private companion object {
        const val RULE = "00000000-0000-4000-8000-00000000abcd"
    }

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
        assertEquals(bob.visible(), server.rows.mapValues { it.value.seen() })
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

    @Test
    fun `a phone that has not heard of the shared pot yet still writes no debt`() {
        val server = FakeServer()
        val alice = Phone("alice", server)
        val bob = Phone("bob", server)
        val older = Fixtures.expense()
        alice.create(older, at(0))
        alice.sync()
        bob.sync()
        // Alice's admin switch lands on the server; Bob's phone has not pulled since.
        server.household = server.household.copy(moneyMode = MoneyMode.SHARED)
        val groceries = Fixtures.expense(payer = Fixtures.BOB)
        bob.create(groceries, at(1))
        assertEquals(Split.Equal(listOf(Fixtures.ALICE, Fixtures.BOB)), bob.rows.getValue(groceries.id).transaction.split)
        bob.sync()
        // The server stored it as Bob's alone and handed that back at once.
        assertEquals(Split.Equal(listOf(Fixtures.BOB)), bob.rows.getValue(groceries.id).transaction.split)
        assertEquals(SyncState.SYNCED, bob.rows.getValue(groceries.id).state)
        // An edit of the row written before the switch keeps its split.
        bob.write(bob.rows.getValue(older.id).transaction.copy(note = "GROCERY STORE 02"), at(2))
        bob.sync()
        alice.sync()
        assertEquals(Split.Equal(listOf(Fixtures.ALICE, Fixtures.BOB)), server.rows.getValue(older.id).split)
        assertEquals(alice.visible(), bob.visible())
        assertEquals(bob.visible(), server.rows.mapValues { it.value.seen() })
        // Now that Bob has heard, his phone writes the payer's share itself and needs no second version.
        val next = Fixtures.expense(payer = Fixtures.BOB)
        bob.create(next, at(3))
        assertEquals(Split.Equal(listOf(Fixtures.BOB)), bob.rows.getValue(next.id).transaction.split)
    }

    @Test
    fun `a recurring occurrence a stale phone also writes stays its payer's alone`() {
        val server = FakeServer()
        val alice = Phone("alice", server)
        val bob = Phone("bob", server)
        bob.sync()
        server.household = server.household.copy(moneyMode = MoneyMode.SHARED)
        alice.sync()
        val id = DeterministicId.occurrence(RULE, LocalDate.of(2030, 2, 1))
        alice.create(Fixtures.expense(id = id), at(0))
        alice.sync()
        // Bob's phone still thinks the household splits, writes the same occurrence later and pushes before pulling.
        bob.create(Fixtures.expense(id = id), at(5))
        assertEquals(Split.Equal(listOf(Fixtures.ALICE, Fixtures.BOB)), bob.rows.getValue(id).transaction.split)
        bob.sync()
        alice.sync()
        val payerOnly = Split.Equal(listOf(Fixtures.ALICE))
        assertEquals(payerOnly, server.rows.getValue(id).split)
        assertEquals(payerOnly, bob.rows.getValue(id).transaction.split)
        assertEquals(payerOnly, alice.rows.getValue(id).transaction.split)
    }

    @Test
    fun `a new settlement pushed to a shared pot is held back with the reason`() {
        val server = FakeServer()
        server.household = server.household.copy(moneyMode = MoneyMode.SHARED)
        val bob = Phone("bob", server)
        val settle = Transaction(id = Fixtures.newId(), kind = TransactionKind.SETTLEMENT, date = LocalDate.of(2030, 1, 15),
            amountMinor = 4520, paidByMemberId = Fixtures.BOB, toMemberId = Fixtures.ALICE,
            createdAt = "2030-01-15T12:00:00.000Z", clientUpdatedAt = "2030-01-15T12:00:00.000Z")
        bob.create(settle, at(0))
        bob.sync()
        assertEquals(SyncState.REJECTED, bob.rows.getValue(settle.id).state)
        assertEquals(SharedPot.NOTHING_TO_SETTLE, bob.rows.getValue(settle.id).rejectMessage)
        assertTrue(settle.id !in server.rows)
    }

    /**
     * Ten phones, clocks up to two hours out, going on and offline, creating,
     * editing, deleting and restoring shared rows in random order, while an
     * admin switches the household between splitting and one shared pot. Once they
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
                when (random.nextInt(11)) {
                    0 -> phone.online = !phone.online
                    1, 2 -> {
                        // Half of them with ids every phone derives alike, as recurring occurrences have.
                        val id = if (random.nextBoolean()) DeterministicId.occurrence(RULE, LocalDate.of(2030, 1, 1).plusDays(random.nextLong(0, 30)))
                        else Fixtures.newId()
                        if (id !in phone.rows) {
                            phone.create(Fixtures.expense(id = id, payer = if (random.nextBoolean()) Fixtures.ALICE else Fixtures.BOB), at(minute))
                        }
                    }
                    3, 4, 5 -> phone.rows.values.filter { it.transaction.isActive }.randomOrNull(random)?.let {
                        phone.write(it.transaction.copy(note = "edit $seed/$minute by ${phone.name}"), at(minute))
                    }
                    6 -> phone.rows.values.filter { it.transaction.isActive }.randomOrNull(random)?.let {
                        phone.write(it.transaction.copy(status = Status.DELETED), at(minute))
                    }
                    7 -> phone.rows.values.filter { !it.transaction.isActive }.randomOrNull(random)?.let {
                        phone.write(it.transaction.copy(status = Status.ACTIVE), at(minute))
                    }
                    10 -> server.household = server.household.copy(
                        moneyMode = if (SharedPot.isShared(server.household)) MoneyMode.SPLIT else MoneyMode.SHARED)
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
            val truth = server.rows.mapValues { it.value.seen() }
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
