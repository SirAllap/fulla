// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.local.RecurringPlanner
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.sync.Edits
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.guide.MonthStart
import io.github.sirallap.fulla.core.model.MoneyMode
import io.github.sirallap.fulla.core.model.Role
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.sync.SyncState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalTest {

    private val t0 = Instant.parse("2030-01-15T12:00:00Z")

    @Test
    fun `a local household reads like one the server sent`() {
        val bundle = LocalHousehold.create("Demo household", "eur", "es-ES", "Alice", "A", 0)
        val config = Wire.config(bundle)
        assertEquals("EUR", config.household.currency)
        assertEquals(Role.OWNER, config.me()!!.role)
        assertEquals("Supermercado", config.categories.first { it.icon == "shopping_cart" }.name)
        assertEquals(2, config.accounts.size)
        assertTrue(config.categories.map { it.id }.toSet().size == config.categories.size)
    }

    @Test
    fun `saving structure replaces by id, never removes, and bumps the version`() {
        var bundle = LocalHousehold.create("Demo household", "EUR", "en-GB", "Alice", "A", 0)
        val account = Wire.config(bundle).accounts.first()
        val v = LocalHousehold.version(bundle)
        bundle = LocalHousehold.upsert(bundle, Structure.ACCOUNT, buildJsonObject {
            put("id", account.id); put("name", "Wallet"); put("archived", true)
        })
        val after = Wire.config(bundle)
        assertEquals(2, after.accounts.size)
        assertEquals("Wallet", after.account(account.id)!!.name)
        assertTrue(after.account(account.id)!!.archived)
        assertEquals(account.type, after.account(account.id)!!.type)
        assertEquals(v + 1, LocalHousehold.version(bundle))

        val category = after.categories.first().id
        fun budget(amount: Long) = buildJsonObject {
            put("id", Fixtures.newId()); put("category_id", category); put("period", JsonNull); put("amount_minor", amount)
        }
        bundle = LocalHousehold.upsert(bundle, Structure.BUDGET, budget(5000))
        bundle = LocalHousehold.upsert(bundle, Structure.BUDGET, budget(7000))
        assertEquals(listOf(7000L), Wire.config(bundle).budgets.map { it.amountMinor })

        bundle = LocalHousehold.updateHousehold(bundle, JsonObject(mapOf("period_start_day" to JsonPrimitive(25),
            "created_by" to JsonPrimitive("ignored"))))
        assertEquals(25, Wire.config(bundle).household.periodStartDay)

        bundle = LocalHousehold.upsertMember(bundle, buildJsonObject { put("id", Fixtures.BOB); put("display_name", "Bob"); put("initials", "B") })
        assertEquals(Role.MEMBER, Wire.config(bundle).member(Fixtures.BOB)!!.role)
    }

    @Test
    fun `updateHousehold refuses the exclusive pair`() {
        var bundle = LocalHousehold.create("Demo household", "EUR", "en-GB", "Alice", "A", 0)
        // A patch that would leave both a mid-month start and shifted income set is refused,
        // and refusing it leaves the stored household untouched.
        val before = Wire.config(bundle).household
        bundle = LocalHousehold.updateHousehold(bundle, buildJsonObject { put("period_start_day", 20) })
        assertFailsWith<IllegalArgumentException> {
            LocalHousehold.updateHousehold(bundle, buildJsonObject { put("income_shift_day", 25) })
        }
        assertEquals(20, Wire.config(bundle).household.periodStartDay)
        assertNull(Wire.config(bundle).household.incomeShiftDay)
        assertEquals(before.periodStartDay, 1)

        // MonthStart.toPatch always sends both keys, one explicitly null, so going through
        // it can set one and clear the other in a single, always-valid patch.
        for (day in listOf(2, 28)) {
            val payday = LocalHousehold.updateHousehold(bundle,
                JsonObject(MonthStart.Payday(day).toPatch().mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v as Int) }))
            assertEquals(day, Wire.config(payday).household.periodStartDay)
            assertNull(Wire.config(payday).household.incomeShiftDay)
        }
        for (day in listOf(2, 31)) {
            val salary = LocalHousehold.updateHousehold(bundle,
                JsonObject(MonthStart.SalaryNextMonth(day).toPatch().mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v as Int) }))
            assertEquals(1, Wire.config(salary).household.periodStartDay)
            assertEquals(day, Wire.config(salary).household.incomeShiftDay)
        }
    }

    @Test
    fun `an edit is stamped after the version it changes, and remembers the synced one`() {
        val created = Edits.create(Fixtures.expense(), true, t0)
        val synced = created.copy(state = SyncState.SYNCED, baseClientUpdatedAt = created.transaction.clientUpdatedAt)
        // This phone's clock is an hour behind the version it is looking at.
        val edited = Edits.edit(synced, synced.transaction.copy(amountMinor = 42), true, t0.minusSeconds(3600))
        assertTrue(edited.transaction.clientUpdatedAt > synced.transaction.clientUpdatedAt)
        assertEquals(synced.transaction.clientUpdatedAt, edited.baseClientUpdatedAt)
        assertEquals(SyncState.PENDING, edited.state)

        val again = Edits.edit(edited, edited.transaction.copy(amountMinor = 43), true, t0)
        assertEquals(synced.transaction.clientUpdatedAt, again.baseClientUpdatedAt)

        val rejected = again.copy(state = SyncState.REJECTED, rejectCode = "validation_failed")
        val fixed = Edits.edit(rejected, rejected.transaction, true, t0.plusSeconds(1))
        assertNull(fixed.rejectCode)
        assertEquals(SyncState.PENDING, fixed.state)

        val deleted = Edits.delete(fixed, true, t0.plusSeconds(2))
        assertEquals(Status.DELETED, deleted.transaction.status)
        assertEquals(Status.ACTIVE, Edits.restore(deleted, true, t0.plusSeconds(3)).transaction.status)
        assertEquals(SyncState.LOCAL_ONLY, Edits.edit(created, created.transaction, false, t0).state)
    }

    @Test
    fun `recurring occurrences are generated once, with ids both phones agree on`() {
        val ruleId = "00000000-0000-4000-8000-000000000301"
        val bundle = LocalHousehold.upsert(LocalHousehold.create("Demo household", "EUR", "en-GB", "Alice", "A", 0),
            Structure.RECURRING, buildJsonObject {
                put("id", ruleId); put("name", "Rent"); put("start_date", "2030-01-01"); put("auto_create", true); put("active", true)
                put("schedule", buildJsonObject { put("freq", "monthly"); put("interval", 1); put("by_month_day", 1) })
                put("template", buildJsonObject {
                    put("kind", "expense"); put("amount_minor", 50000); put("recurrence", "fixed"); put("note", "RENT")
                })
            })
        val config = Wire.config(bundle)
        val today = LocalDate.of(2030, 3, 10)
        val due = RecurringPlanner.due(config, emptySet(), today)
        assertEquals(listOf(LocalDate.of(2030, 2, 1), LocalDate.of(2030, 3, 1)), due.map { it.date })
        assertEquals(DeterministicId.occurrence(ruleId, LocalDate.of(2030, 3, 1)), due.last().id)
        assertEquals(ruleId, due.last().recurringRuleId)
        assertEquals(config.meMemberId, due.last().createdByMemberId)
        // A deleted occurrence still holds its id and is not generated again.
        assertEquals(listOf(LocalDate.of(2030, 2, 1)), RecurringPlanner.due(config, setOf(due.last().id), today).map { it.date })
    }

    @Test
    fun `a household on this phone chooses one shared pot, and what it writes next follows`() {
        val ruleId = "00000000-0000-4000-8000-000000000302"
        var bundle = LocalHousehold.create("Demo household", "EUR", "en-GB", "Alice", "A", 0)
        assertNull(Wire.config(bundle).household.moneyMode, "nobody has chosen yet")
        val me = Wire.config(bundle).meMemberId!!
        bundle = LocalHousehold.upsertMember(bundle, buildJsonObject { put("id", Fixtures.BOB); put("display_name", "Bob"); put("initials", "B") })
        bundle = LocalHousehold.upsert(bundle, Structure.RECURRING, buildJsonObject {
            put("id", ruleId); put("name", "Rent"); put("start_date", "2030-01-01"); put("auto_create", true); put("active", true)
            put("schedule", buildJsonObject { put("freq", "monthly"); put("interval", 1); put("by_month_day", 1) })
            put("template", buildJsonObject {
                put("kind", "expense"); put("amount_minor", 50000); put("recurrence", "fixed"); put("note", "RENT")
                put("paid_by_member_id", me)
                put("split", buildJsonObject { put("mode", "equal"); put("members", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(me), JsonPrimitive(Fixtures.BOB)))) })
            })
        })
        bundle = LocalHousehold.updateHousehold(bundle, buildJsonObject { put("money_mode", "shared") })
        val config = Wire.config(bundle)
        assertEquals(MoneyMode.SHARED, config.household.moneyMode)
        // The rule's template still splits; what it writes while the pot is shared does not.
        assertEquals(Split.Equal(listOf(me, Fixtures.BOB)), config.recurringRules.single().template.split)
        val due = RecurringPlanner.due(config, emptySet(), LocalDate.of(2030, 1, 10))
        assertEquals(listOf(Split.Equal(listOf(me))), due.map { it.split })
        // An expense written on this phone is the payer's alone; an edit keeps whatever split it has.
        val created = Edits.create(Fixtures.expense(), false, t0, config.household)
        assertEquals(Split.Equal(listOf(Fixtures.ALICE)), created.transaction.split)
        val edited = Edits.edit(created, created.transaction.copy(split = Split.Equal(listOf(Fixtures.ALICE, Fixtures.BOB))), false, t0)
        assertEquals(Split.Equal(listOf(Fixtures.ALICE, Fixtures.BOB)), edited.transaction.split)
        // The choice travels with the bundle when the household is shared, and back.
        assertEquals(MoneyMode.SHARED, Wire.config(Wire.bundle(config)).household.moneyMode)
        // A patch that leaves money_mode out keeps it.
        bundle = LocalHousehold.updateHousehold(bundle, buildJsonObject { put("name", "Our place") })
        assertEquals(MoneyMode.SHARED, Wire.config(bundle).household.moneyMode)
    }

    @Test
    fun `transactions survive the wire both ways`() {
        val t = Fixtures.expense().copy(
            split = Split.Shares(mapOf(Fixtures.ALICE to 2, Fixtures.BOB to 1)), tags = listOf("trip"),
            extras = mapOf("receipt" to true, "mileage" to 12L, "store" to "GROCERY STORE 01", "cleared" to null),
            occurrenceDate = LocalDate.of(2030, 1, 15), originalAmountMinor = 1500, originalCurrency = "USD",
            tripId = "00000000-0000-4000-8000-000000000501",
        )
        assertEquals(t, Wire.transaction(Wire.transaction(t)))
        val exact = t.copy(split = Split.Exact(mapOf(Fixtures.ALICE to 1000L, Fixtures.BOB to 234L)))
        assertEquals(exact, Wire.transaction(Wire.transaction(exact)))
    }

    @Test
    fun `trip_id round-trips absent, explicit null and a uuid, each distinctly`() {
        val withTrip = Fixtures.expense().copy(tripId = "00000000-0000-4000-8000-000000000501", tripKnown = true)
        assertEquals(withTrip.tripId, Wire.transaction(Wire.transaction(withTrip)).tripId)
        assertTrue(Wire.transaction(withTrip).containsKey("trip_id"))

        // An old row that never heard of trips: the key must not be written at all.
        val neverKnew = Fixtures.expense().copy(tripId = null, tripKnown = false)
        val encoded = Wire.transaction(neverKnew)
        assertTrue(!encoded.containsKey("trip_id"), "an unknown trip must not be written as an explicit null")
        val decodedBack = Wire.transaction(encoded)
        assertEquals(false, decodedBack.tripKnown)
        assertEquals(null, decodedBack.tripId)

        // The person removed the trip on the entry screen: an explicit null, distinct from never having heard of it.
        val cleared = Fixtures.expense().copy(tripId = null, tripKnown = true)
        val clearedJson = Wire.transaction(cleared)
        assertTrue(clearedJson.containsKey("trip_id"))
        assertEquals(true, Wire.transaction(clearedJson).tripKnown)
    }
}

class BundleTest {
    @Test
    fun `a config survives becoming a bundle and back`() {
        val demo = io.github.sirallap.fulla.core.demo.DemoData.build(LocalDate.of(2030, 3, 10))
        val schedule = io.github.sirallap.fulla.core.recurring.Schedule(io.github.sirallap.fulla.core.recurring.Frequency.MONTHLY, byMonthDay = 1)
        val rule = io.github.sirallap.fulla.core.recurring.RecurringRule("00000000-0000-4000-8000-000000000301", "Rent",
            Fixtures.expense(id = "00000000-0000-4000-8000-000000000301", stamp = "").copy(date = LocalDate.of(2030, 1, 1), createdAt = ""),
            schedule, LocalDate.of(2030, 1, 1))
        val trip = io.github.sirallap.fulla.core.trips.Trip(
            id = "00000000-0000-4000-8000-000000000501", name = "Porto",
            startDate = LocalDate.of(2030, 8, 12), endDate = LocalDate.of(2030, 8, 19), budgetMinor = 30000,
        )
        val config = demo.config.copy(recurringRules = listOf(rule), trips = listOf(trip, trip.copy(id = "00000000-0000-4000-8000-000000000502", budgetMinor = null)))
        assertEquals(config, Wire.config(Wire.bundle(config)))
    }
}

class CsvExportTest {
    @Test
    fun `the export reads back through the importer's own CSV parser, to the cent`() {
        val demo = io.github.sirallap.fulla.core.demo.DemoData.build(LocalDate.of(2030, 3, 10))
        val tricky = Fixtures.expense(amount = 105).copy(note = "Line one, \"quoted\"\nline two", extras = mapOf("receipt" to true))
        val config = demo.config
        val csv = io.github.sirallap.fulla.client.local.CsvExport.write(config, demo.transactions + tricky.copy(categoryId = config.categories.first().id))
        val rows = io.github.sirallap.fulla.core.importers.Csv.parse(csv.removePrefix("\uFEFF"), ',')
        assertEquals(io.github.sirallap.fulla.client.local.CsvExport.HEADER, rows.first())
        val body = rows.drop(1).filter { it.size > 1 }
        assertEquals(demo.transactions.count { it.isActive } + 1, body.size)
        val mine = body.single { it[15] == tricky.id }
        assertEquals("1.05", mine[2])
        assertEquals(tricky.note, mine[11])
        assertEquals("{\"receipt\":true}", mine[14])
        assertEquals(demo.transactions.filter { it.isActive }.sumOf { it.amountMinor } + 105,
            body.sumOf { java.math.BigDecimal(it[2]).movePointRight(2).longValueExact() })
    }
}

class BackupTest {
    @Test
    fun `a backup restores the household exactly, deleted rows included`() {
        val bundle = io.github.sirallap.fulla.client.local.LocalHousehold.create("Demo household", "EUR", "en-GB", "Alice", "A", 0)
        val rows = listOf(Fixtures.expense(amount = 1234), Fixtures.expense(amount = 99).copy(status = io.github.sirallap.fulla.core.model.Status.DELETED, serverSeq = 7))
        val text = io.github.sirallap.fulla.client.local.Backup.write(bundle, rows, "2030-01-15T12:00:00.000Z")
        val back = io.github.sirallap.fulla.client.local.Backup.read(text)
        assertEquals(bundle, back.bundle)
        assertEquals(rows, back.transactions)
        assertEquals("Demo household", back.householdName)
    }

    @Test
    fun `anything that is not a backup is refused with a reason`() {
        fun reason(text: String) = runCatching { io.github.sirallap.fulla.client.local.Backup.read(text) }.exceptionOrNull()
            .let { (it as io.github.sirallap.fulla.client.local.Backup.Unreadable).reason }
        assertEquals(io.github.sirallap.fulla.client.local.Backup.Reason.NOT_A_BACKUP, reason("date,amount\n2030-01-01,1"))
        assertEquals(io.github.sirallap.fulla.client.local.Backup.Reason.NOT_A_BACKUP, reason("{\"format\":\"other\"}"))
        assertEquals(io.github.sirallap.fulla.client.local.Backup.Reason.NEWER_VERSION, reason("{\"format\":\"fulla-backup\",\"version\":99}"))
        assertEquals(io.github.sirallap.fulla.client.local.Backup.Reason.DAMAGED, reason("{\"format\":\"fulla-backup\",\"version\":1,\"config\":{}}"))
    }
}

class FieldKeysTest {
    @Test
    fun `keys follow the database's rules whatever the label`() {
        val k = io.github.sirallap.fulla.client.local.FieldKeys
        assertEquals("tienda", k.from("Tienda", emptySet()))
        assertEquals("pago_con_tarjeta", k.from("¿Pagó con tarjeta?", emptySet()))
        assertEquals("field_2030", k.from("2030", emptySet()))
        assertEquals("note_2", k.from("Note", emptySet()))
        assertEquals("tienda_2", k.from("Tienda", setOf("tienda")))
        assertEquals("field", k.from("!!!", emptySet()))
        kotlin.test.assertTrue(k.from("x".repeat(80), emptySet()).length <= 32)
    }
}

class ImportProfilesTest {
    @Test
    fun `a saved mapping comes back the same`() {
        val p = io.github.sirallap.fulla.client.local.SavedProfile("00000000-0000-4000-8000-000000000401", "Main account CSV", Fixtures.MAIN,
            io.github.sirallap.fulla.core.importers.ImportProfile(delimiter = ';', skipRows = 2, dateColumn = 0, dateFormat = "dd/MM/yyyy",
                amount = io.github.sirallap.fulla.core.importers.AmountColumns.Split(3, 4),
                decimal = io.github.sirallap.fulla.core.money.DecimalStyle.COMMA, descriptionColumns = listOf(1, 2),
                categoryColumn = 5, kindColumn = 6, incomeValues = listOf("in", "Income"), payerColumn = 7, accountColumn = 8,
                fixedColumn = 9, fixedValues = listOf("fixed")))
        assertEquals(p, io.github.sirallap.fulla.client.local.ImportProfiles.fromJson(io.github.sirallap.fulla.client.local.ImportProfiles.toJson(p)))
    }
}

class BudgetCopyTest {
    @Test
    fun `copying a month's budgets keeps what the target already has`() {
        var bundle = io.github.sirallap.fulla.client.local.LocalHousehold.create("Demo household", "EUR", "en-GB", "Alice", "A", 0)
        val cats = Wire.config(bundle).categories.map { it.id }
        fun budget(cat: String, period: String?, amount: Long) = kotlinx.serialization.json.buildJsonObject {
            put("id", Fixtures.newId()); put("category_id", cat); put("period", period); put("amount_minor", amount)
        }
        val L = io.github.sirallap.fulla.client.local.LocalHousehold
        bundle = L.upsert(bundle, io.github.sirallap.fulla.client.remote.Structure.BUDGET, budget(cats[0], null, 1000))
        bundle = L.upsert(bundle, io.github.sirallap.fulla.client.remote.Structure.BUDGET, budget(cats[1], "2030-01", 2000))
        bundle = L.upsert(bundle, io.github.sirallap.fulla.client.remote.Structure.BUDGET, budget(cats[1], "2030-02", 5000))
        val copied = Wire.config(L.copyBudgets(bundle, "2030-01", "2030-02")).budgets.filter { it.period == "2030-02" }
        assertEquals(mapOf(cats[0] to 1000L, cats[1] to 5000L), copied.associate { it.categoryId to it.amountMinor })
    }
}

class LocalTripsTest {
    @Test
    fun `a trip saved locally carries through the bundle, and an edit keeps its id`() {
        var bundle = io.github.sirallap.fulla.client.local.LocalHousehold.create("Demo household", "EUR", "en-GB", "Alice", "A", 0)
        val version = io.github.sirallap.fulla.client.local.LocalHousehold.version(bundle)
        val L = io.github.sirallap.fulla.client.local.LocalHousehold
        val trip = kotlinx.serialization.json.buildJsonObject {
            put("id", "00000000-0000-4000-8000-000000000501"); put("name", "Porto")
            put("start_date", "2030-08-12"); put("end_date", "2030-08-19"); put("budget_minor", 30000)
            put("in_category_budgets", false); put("archived", false)
        }
        bundle = L.upsert(bundle, io.github.sirallap.fulla.client.remote.Structure.TRIP, trip)
        assertEquals(version + 1, L.version(bundle))
        assertEquals(listOf("Porto"), Wire.config(bundle).trips.map { it.name })

        val renamed = kotlinx.serialization.json.buildJsonObject {
            put("id", "00000000-0000-4000-8000-000000000501"); put("name", "Porto, again")
            put("start_date", "2030-08-12"); put("end_date", "2030-08-19"); put("budget_minor", 30000)
            put("in_category_budgets", false); put("archived", false)
        }
        bundle = L.upsert(bundle, io.github.sirallap.fulla.client.remote.Structure.TRIP, renamed)
        assertEquals(1, Wire.config(bundle).trips.size)
        assertEquals("Porto, again", Wire.config(bundle).trips.single().name)
    }
}
