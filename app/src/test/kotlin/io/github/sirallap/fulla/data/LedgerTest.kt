// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sirallap.fulla.client.local.Backup
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.data.local.FullaDatabase
import io.github.sirallap.fulla.data.prefs.SettingsStore
import io.github.sirallap.fulla.data.repo.Ledger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The phone-only paths through Ledger, against Room: everything a screen writes goes through these. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerTest {
    private lateinit var db: FullaDatabase
    private lateinit var ledger: Ledger
    private var syncs = 0
    private var clock = Instant.parse("2030-01-15T12:00:00Z")

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FullaDatabase::class.java).allowMainThreadQueries().build()
        ledger = Ledger(db, SettingsStore(context), requestSync = { syncs++ }, now = { clock })
    }

    @After
    fun close() = db.close()

    private suspend fun household(): String = ledger.createLocal("Demo household", "EUR", "en-GB", "Alice", "A", 0)

    private fun expense(h: String, amount: Long, config: io.github.sirallap.fulla.core.model.Config) = Transaction(
        id = UUID.randomUUID().toString(), kind = TransactionKind.EXPENSE, date = LocalDate.of(2030, 1, 15), amountMinor = amount,
        categoryId = config.categories.first { it.appliesTo.allows(TransactionKind.EXPENSE) }.id,
        accountId = config.accounts.first().id, paidByMemberId = config.meMemberId, note = "GROCERY STORE 01",
        createdAt = "", clientUpdatedAt = "",
    )

    @Test
    fun `a phone-only household writes rows that are never owed to a server`() = runTest {
        val h = household()
        val config = ledger.household(h)!!.config
        val t = expense(h, 1234, config)
        ledger.save(h, t)
        clock = clock.plusSeconds(60)
        ledger.save(h, t.copy(amountMinor = 999))
        val row = ledger.transaction(t.id)!!
        assertEquals(999, row.transaction.amountMinor)
        assertEquals(SyncState.LOCAL_ONLY, row.state)
        assertEquals("2030-01-15T12:00:00.000Z", row.transaction.createdAt)
        assertEquals("2030-01-15T12:01:00.000Z", row.transaction.clientUpdatedAt)
        assertEquals(0, syncs)

        ledger.delete(h, t.id)
        assertEquals(Status.DELETED, ledger.transaction(t.id)!!.transaction.status)
        ledger.restore(h, t.id)
        assertEquals(Status.ACTIVE, ledger.transaction(t.id)!!.transaction.status)
    }

    /** A monthly fixed cost that writes itself, in the household's own first expense category and account. */
    private fun rentRule(config: io.github.sirallap.fulla.core.model.Config, start: String, day: Int = 1, auto: Boolean = true, ruleId: String = UUID.randomUUID().toString()) =
        buildJsonObject {
            put("id", ruleId); put("name", "Rent"); put("start_date", start); put("auto_create", auto); put("active", true)
            put("schedule", buildJsonObject { put("freq", "monthly"); put("interval", 1); put("by_month_day", day) })
            put("template", buildJsonObject {
                put("kind", "expense"); put("amount_minor", 50000); put("recurrence", "fixed"); put("note", "RENT")
                put("category_id", config.categories.first { it.appliesTo.allows(TransactionKind.EXPENSE) }.id)
                put("account_id", config.accounts.first().id)
                put("paid_by_member_id", config.meMemberId)
            })
        }

    @Test
    fun `recurring items are written once, however often generation runs`() = runTest {
        val h = household()
        val config = ledger.household(h)!!.config
        ledger.upsert(h, Structure.RECURRING, rentRule(config, "2030-01-01"), api = null)
        repeat(3) { ledger.generateRecurring(h, LocalDate.of(2030, 3, 10)) }
        // A rule that never wrote a row starts with the current period: March, not the months before it.
        assertEquals(listOf(LocalDate.of(2030, 3, 1)), ledger.transactions(h).first().map { it.transaction.date })
        // Once it has written, it catches up whatever it missed (up to two months back).
        ledger.generateRecurring(h, LocalDate.of(2030, 5, 20))
        val rows = ledger.transactions(h).first()
        assertEquals(listOf("2030-03-01", "2030-04-01", "2030-05-01"), rows.map { it.transaction.date.toString() }.sorted())
        assertTrue(rows.all { it.transaction.recurringRuleId != null })
    }

    @Test
    fun `a phone-only household writes its fixed costs too, with nothing owed to a server`() = runTest {
        val first = household()
        val second = ledger.createLocal("Second household", "EUR", "en-GB", "Alice", "A", 0)
        ledger.upsert(first, Structure.RECURRING, rentRule(ledger.household(first)!!.config, "2030-01-01"), api = null)
        ledger.upsert(second, Structure.RECURRING, rentRule(ledger.household(second)!!.config, "2030-01-01", day = 5), api = null)
        assertEquals(2, ledger.generateRecurringEverywhere(LocalDate.of(2030, 3, 10)), "each household's own")
        assertEquals(0, ledger.generateRecurringEverywhere(LocalDate.of(2030, 3, 10)), "and not again")
        val rows = ledger.transactions(first).first() + ledger.transactions(second).first()
        assertTrue(rows.all { it.state == SyncState.LOCAL_ONLY })
        assertEquals(0, syncs)
    }

    @Test
    fun `a fixed cost that is saved is written at once if its day has already come this period`() = runTest {
        val h = household()
        val config = ledger.household(h)!!.config
        val firstOfMonth = LocalDate.now().withDayOfMonth(1)
        ledger.upsert(h, Structure.RECURRING, rentRule(config, firstOfMonth.toString()), api = null)
        // No sync, no waiting for the app to be opened again: the first of this month has come.
        assertEquals(listOf(firstOfMonth), ledger.transactions(h).first().map { it.transaction.date })
    }

    @Test
    fun `what is written by hand is not written again, and a connected household owes what it writes`() = runTest {
        val h = household()
        val config = ledger.household(h)!!.config
        val mine = expense(h, 50000, config).copy(date = LocalDate.of(2030, 3, 2))
        ledger.save(h, mine)
        ledger.upsert(h, Structure.RECURRING, rentRule(config, "2030-01-01"), api = null)
        assertEquals(0, ledger.generateRecurring(h, LocalDate.of(2030, 3, 10)), "the rent of March is on the books already")
        assertEquals(1, ledger.transactions(h).first().size)

        // The same household, shared: what it writes is owed to the server and asks for a sync.
        val fresh = io.github.sirallap.fulla.client.local.LocalHousehold.create("Shared household", "EUR", "en-GB", "Alice", "A", 0)
        val bundle = io.github.sirallap.fulla.client.local.LocalHousehold.upsert(fresh, Structure.RECURRING, rentRule(Wire.config(fresh), "2030-01-01"))
        val id = Wire.config(bundle).household.id
        db.households().upsert(io.github.sirallap.fulla.data.local.HouseholdEntity(id, Ledger.CONNECTED, bundle.toString(),
            io.github.sirallap.fulla.client.local.LocalHousehold.version(bundle)))
        val before = syncs
        assertEquals(1, ledger.generateRecurring(id, LocalDate.of(2030, 3, 10)))
        assertEquals(SyncState.PENDING, ledger.transactions(id).first().single().state)
        assertEquals(before + 1, syncs)
    }

    @Test
    fun `applying a charge that has come writes it once, and only if it is one`() = runTest {
        val h = household()
        val config = ledger.household(h)!!.config
        val ruleId = UUID.randomUUID().toString()
        ledger.upsert(h, Structure.RECURRING, rentRule(config, "2030-01-01", ruleId = ruleId), api = null)
        val march = LocalDate.of(2030, 3, 1)
        assertTrue(ledger.applyRecurring(h, ruleId, march))
        assertFalse("twice", ledger.applyRecurring(h, ruleId, march))
        assertFalse("not a day of the rule", ledger.applyRecurring(h, ruleId, LocalDate.of(2030, 3, 2)))
        assertFalse("not a rule", ledger.applyRecurring(h, UUID.randomUUID().toString(), march))
        val row = ledger.transactions(h).first().single()
        assertEquals(io.github.sirallap.fulla.core.recurring.DeterministicId.occurrence(ruleId, march), row.id)
        // What the phone writes by itself afterwards is that very row, not a second one.
        ledger.generateRecurring(h, LocalDate.of(2030, 3, 10))
        assertEquals(1, ledger.transactions(h).first().count { it.transaction.date == march })
        // A charge somebody deleted stays deleted, and applying it does not bring it back.
        ledger.delete(h, row.id)
        assertFalse(ledger.applyRecurring(h, ruleId, march))
        assertEquals(Status.DELETED, ledger.transaction(row.id)!!.transaction.status)
        // Letting one go writes it already deleted: the phone never writes that occurrence on its own.
        val april = LocalDate.of(2030, 4, 1)
        assertTrue(ledger.skipRecurring(h, ruleId, april))
        assertFalse("twice", ledger.skipRecurring(h, ruleId, april))
        val skipped = ledger.transaction(io.github.sirallap.fulla.core.recurring.DeterministicId.occurrence(ruleId, april))!!
        assertEquals(Status.DELETED, skipped.transaction.status)
        assertEquals(0, ledger.generateRecurring(h, LocalDate.of(2030, 4, 10)))
        assertFalse("and it cannot be applied after", ledger.applyRecurring(h, ruleId, april))
    }

    @Test
    fun `a backup restores on another phone, and never over a household already there`() = runTest {
        val h = household()
        val config = ledger.household(h)!!.config
        ledger.save(h, expense(h, 1234, config))
        val text = ledger.backup(h)!!
        val contents = Backup.read(text)
        assertFalse("restored over itself", ledger.restore(contents))
        ledger.forget(h)
        assertNull(ledger.household(h))
        assertTrue(ledger.restore(contents))
        assertEquals(1234, ledger.transactions(h).first().single().transaction.amountMinor)
        assertEquals(Wire.config(contents.bundle), ledger.household(h)!!.config)
    }

    @Test
    fun `structure changes on a phone-only household bump its version`() = runTest {
        val h = household()
        val before = ledger.household(h)!!.config.version
        ledger.updateHousehold(h, buildJsonObject { put("name", "Home") }, api = null)
        val after = ledger.household(h)!!.config
        assertEquals("Home", after.household.name)
        assertEquals(before + 1, after.version)
    }

    private suspend fun tripJson(h: String): kotlinx.serialization.json.JsonObject {
        val trip = buildJsonObject {
            put("id", UUID.randomUUID().toString()); put("name", "Porto")
            put("start_date", "2030-01-01"); put("end_date", "2030-01-20"); put("budget_minor", 30000)
            put("in_category_budgets", false); put("archived", false)
        }
        ledger.upsert(h, Structure.TRIP, trip, api = null)
        return trip
    }

    @Test
    fun `a delete launched on an app-level scope survives its caller's scope being cancelled (review HIGH 2)`() = runTest {
        // TripScreen used to run `ledger.deleteTrip` on its own
        // rememberCoroutineScope, then call onBack() immediately — which
        // cancels that scope the moment the screen leaves the composition,
        // sometimes mid-RPC. The fix runs it on AppContainer.scope instead,
        // built the same way here: a SupervisorJob a screen closing can
        // never reach.
        val h = household()
        val trip = tripJson(h)
        val tripId = trip["id"]!!.jsonPrimitive.content

        val screenScope = CoroutineScope(Job())
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val job = appScope.launch {
            started.complete(Unit)
            delay(50) // stands in for the network RPC a connected delete would make
            ledger.deleteTrip(h, tripId, api = null)
        }
        started.await()
        screenScope.cancel() // the screen popping right after the person confirms
        job.join()

        assertTrue(ledger.household(h)!!.config.trips.isEmpty())
    }
}
