// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.local.RecurringPlanner
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import io.github.sirallap.fulla.core.split.SharedPot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.asKotlinRandom

/**
 * What a phone writes for the fixed costs that came due: every day it looks,
 * once after a long time away, and the same rows either way.
 */
class RecurringPlannerTest {

    private fun d(month: Int, day: Int, year: Int = 2030) = LocalDate.of(year, month, day)

    private val bundle = LocalHousehold.create("Demo household", "EUR", "en-GB", "Alice", "A", 0)
    private val category = Wire.config(bundle).categories.first().id

    private fun rule(
        id: String, name: String, schedule: JsonObject, start: LocalDate = d(1, 1), amount: Long = 50_000, auto: Boolean = true,
    ) = buildJsonObject {
        put("id", id); put("name", name); put("start_date", start.toString()); put("auto_create", auto); put("active", true)
        put("schedule", schedule)
        put("template", buildJsonObject {
            put("kind", "expense"); put("amount_minor", amount); put("recurrence", "fixed"); put("note", name); put("category_id", category)
        })
    }

    private fun monthly(day: Int) = buildJsonObject { put("freq", "monthly"); put("interval", 1); put("by_month_day", day) }
    private fun weeklyMonday() = buildJsonObject {
        put("freq", "weekly"); put("interval", 1)
        put("by_weekday", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(1))))
    }

    private fun config(vararg rules: JsonObject): Config =
        Wire.config(rules.fold(bundle) { b, r -> LocalHousehold.upsert(b, Structure.RECURRING, r) })

    private val rentId = "00000000-0000-4000-8000-000000000501"
    private val gymId = "00000000-0000-4000-8000-000000000502"
    private val lotteryId = "00000000-0000-4000-8000-000000000503"

    private fun held(rows: List<Transaction>) = rows.map { it.id }.toSet()

    @Test
    fun `each fixed cost is charged to the account it was given, and the rows it writes carry it`() {
        val savings = "00000000-0000-4000-8000-0000000009a1"
        val withAccount = rule(rentId, "Rent", monthly(20)).let { r ->
            val t = r["template"] as JsonObject
            JsonObject(r + ("template" to JsonObject(t + ("account_id" to kotlinx.serialization.json.JsonPrimitive(savings)))))
        }
        val c = config(withAccount, rule(gymId, "Gym", monthly(20)))
        assertEquals(savings, c.recurringRules.first { it.id == rentId }.template.accountId)
        val due = RecurringPlanner.due(c, emptySet(), d(3, 21)).filter { it.date == d(3, 20) }
        assertEquals(savings, due.first { it.recurringRuleId == rentId }.accountId, "the row goes to the account chosen")
        assertEquals(c.recurringRules.first { it.id == gymId }.template.accountId, due.first { it.recurringRuleId == gymId }.accountId, "the other keeps its own")
    }

    @Test
    fun `a financing writes its payments and then stops, even for a phone opened long after`() {
        val six = rule(rentId, "Sofa", monthly(27), start = d(1, 1)).let { kotlinx.serialization.json.JsonObject(it + ("end_date" to kotlinx.serialization.json.JsonPrimitive(d(6, 27).toString()))) }
        val c = config(six)
        assertEquals(d(6, 27), c.recurringRules.single().endDate, "the end travels through the wire")
        val written = (1..6).map { DeterministicId.occurrence(rentId, d(it, 27)) }.toSet()
        // Opened in March: three so far, nothing after the end is written ahead of time.
        assertEquals(listOf(d(1, 27), d(2, 27), d(3, 27)), RecurringPlanner.due(c, emptySet(), d(3, 28)).map { it.date })
        // Opened a year later: what is left is written, and nothing past the sixth.
        assertEquals(emptyList(), RecurringPlanner.due(c, written, d(1, 15, 2031)).map { it.date })
        assertEquals(listOf(d(6, 27)), RecurringPlanner.due(c, written - DeterministicId.occurrence(rentId, d(6, 27)), d(7, 20)).map { it.date })
    }

    @Test
    fun `a rule that never wrote a row starts with the current period, not the day it says`() {
        val c = config(rule(rentId, "Rent", monthly(20), start = d(12, 1, 2029)))
        val today = d(3, 25)
        // Nobody told the planner where the period began: it catches up as far as it always did.
        assertEquals(listOf(d(2, 20), d(3, 20)), RecurringPlanner.due(c, emptySet(), today).map { it.date })
        // Told that this period began on 15 March, a rule that never ran starts there: February is not written.
        assertEquals(listOf(d(3, 20)), RecurringPlanner.due(c, emptySet(), today, emptyList(), d(3, 15)).map { it.date })
        // A rule that has written before keeps the whole catch-up, whatever the period says.
        val before = setOf(DeterministicId.occurrence(rentId, d(12, 20, 2029)))
        assertEquals(listOf(d(2, 20), d(3, 20)), RecurringPlanner.due(c, before, today, emptyList(), d(3, 15)).map { it.date })
    }

    @Test
    fun `what was left out the first time is not written the second`() = runTest {
        val c = config(rule(rentId, "Rent", monthly(1), start = d(1, 1)))
        val first = RecurringPlanner.plan(c, emptySet(), d(3, 10)) { emptyList() }
        assertEquals(listOf(d(3, 1)), first.map { it.date }, "this period, not the months before it")
        assertEquals(emptyList(), RecurringPlanner.plan(c, held(first), d(3, 10)) { first }, "and the same on the next look")
        assertEquals(emptyList(), RecurringPlanner.plan(c, held(first), d(3, 28)) { first })
        assertEquals(listOf(d(4, 1)), RecurringPlanner.plan(c, held(first), d(4, 5)) { first }.map { it.date }, "then it carries on")
    }

    @Test
    fun `the first look after the update writes this period, for fixed costs an older version never ran`() = runTest {
        // Fixed costs made last week, each starting on the 1st of September as the screen used to say, in a household whose
        // period began with the salary of 28 September. None of them has ever written a row.
        val c = config(
            rule(rentId, "Rent", monthly(1), start = d(9, 1, 2030)),
            rule(gymId, "Gym", monthly(1), start = d(9, 1, 2030), amount = 5_200),
            rule(lotteryId, "Lottery", weeklyMonday(), start = d(9, 1, 2030), amount = 250),
            rule("00000000-0000-4000-8000-000000000504", "School", monthly(25), start = d(9, 1, 2030), amount = 6_550),
        )
        val salary = io.github.sirallap.fulla.core.rules.PeriodAnchors.mark(
            Fixtures.expense(152_525).copy(kind = io.github.sirallap.fulla.core.model.TransactionKind.INCOME, date = d(9, 28)), true)
        val due = RecurringPlanner.plan(c, held(listOf(salary)), d(10, 2)) { listOf(salary) }
        // Rent and gym of 1 October, and the Monday of the period's first day: nothing from before the period.
        assertTrue(due.all { it.date >= d(9, 28) && it.date <= d(10, 2) }, due.map { it.date }.toString())
        assertEquals(listOf(d(10, 1)), due.filter { it.recurringRuleId == rentId }.map { it.date })
        assertEquals(listOf(d(10, 1)), due.filter { it.recurringRuleId == gymId }.map { it.date })
        assertEquals(emptyList(), due.filter { it.recurringRuleId == "00000000-0000-4000-8000-000000000504" }, "the 25th of September is a period ago")
        assertEquals(setOf(d(9, 30)), due.filter { it.recurringRuleId == lotteryId }.map { it.date }.toSet(), "2030-09-30 is a Monday")
        // And looking again, with those written, writes nothing more.
        val written = listOf(salary) + due
        assertEquals(emptyList(), RecurringPlanner.plan(c, held(written), d(10, 2)) { written })

        // What was left out is offered, not written: the person says. Nothing from this period is in it.
        val periodStart = RecurringPlanner.currentPeriodStart(c, written, d(10, 2))
        assertEquals(d(9, 28), periodStart)
        val left = RecurringPlanner.leftOut(c, held(written), written, d(10, 2), periodStart)
        assertTrue(left.isNotEmpty() && left.all { it.date < d(9, 28) }, left.map { it.date }.toString())
        assertEquals(listOf(d(9, 1)), left.filter { it.rule.id == rentId }.map { it.date })
        assertEquals(listOf(d(9, 25)), left.filter { it.rule.id == "00000000-0000-4000-8000-000000000504" }.map { it.date })
        assertEquals(listOf(d(9, 2), d(9, 9), d(9, 16), d(9, 23)), left.filter { it.rule.id == lotteryId }.map { it.date })
        // Written (or let go: a deleted row keeps its id) they stop being offered.
        val applied = written + left.map { RecurringPlanner.occurrence(it.rule, it.date, c) }
        assertEquals(emptyList(), RecurringPlanner.leftOut(c, held(applied), applied, d(10, 2), periodStart))
        // One that was written by hand is not offered.
        val rentByHand = Fixtures.expense(50_000).copy(date = d(9, 2), categoryId = category)
        val withHand = written + rentByHand
        assertEquals(emptyList(), RecurringPlanner.leftOut(c, held(withHand), withHand, d(10, 2), periodStart).filter { it.rule.id == rentId })
    }

    @Test
    fun `an occurrence somebody wrote down by hand is not written again`() {
        val c = config(rule(rentId, "Rent", monthly(1)))
        val today = d(3, 10)
        val mine = Fixtures.expense(50_000).copy(date = d(3, 2), categoryId = category)
        val due = RecurringPlanner.due(c, held(listOf(mine)), today, listOf(mine), null)
        assertEquals(listOf(d(2, 1)), due.map { it.date }, "March is on the books, February is not")
        // A different amount is a different payment.
        val other = mine.copy(id = Fixtures.newId(), amountMinor = 60_000)
        assertEquals(listOf(d(2, 1), d(3, 1)), RecurringPlanner.due(c, held(listOf(other)), today, listOf(other), null).map { it.date })
        // Deleted, it stands for nothing.
        val gone = mine.copy(status = io.github.sirallap.fulla.core.model.Status.DELETED)
        assertEquals(listOf(d(2, 1), d(3, 1)), RecurringPlanner.due(c, held(listOf(gone)), today, listOf(gone), null).map { it.date })
    }

    @Test
    fun `the rows are read only when something may be due, and once`() = runTest {
        val c = config(rule(rentId, "Rent", monthly(1), start = d(4, 1)))
        var reads = 0
        // Nothing has fallen due yet: no row is read.
        assertEquals(emptyList(), RecurringPlanner.plan(c, emptySet(), d(3, 10)) { reads++; emptyList() })
        assertEquals(0, reads)
        // Something is: the rows are read once, and the period decides where a new rule starts.
        val due = RecurringPlanner.plan(c, emptySet(), d(4, 10)) { reads++; emptyList() }
        assertEquals(1, reads)
        assertEquals(listOf(d(4, 1)), due.map { it.date })
    }

    @Test
    fun `what a rule writes is the same row on every phone, and follows the shared pot`() {
        val c = config(rule(rentId, "Rent", monthly(1)))
        val row = RecurringPlanner.occurrence(c.recurringRules.single(), d(3, 1), c)
        assertEquals(DeterministicId.occurrence(rentId, d(3, 1)), row.id)
        assertEquals(rentId, row.recurringRuleId)
        assertEquals(d(3, 1), row.occurrenceDate)
        assertEquals(c.meMemberId, row.createdByMemberId)
        assertEquals(row, RecurringPlanner.due(c, emptySet(), d(3, 10)).last())
        assertEquals(SharedPot.forNew(row, c.household), row)
    }

    @Test
    fun `a phone opened every day and one opened once after a month write the same rows`() = runTest {
        val c = config(
            rule(rentId, "Rent", monthly(1)),
            rule(gymId, "Gym", monthly(15), amount = 3_000),
            rule(lotteryId, "Lottery", weeklyMonday(), amount = 250),
        )
        // Opened every day of the quarter.
        val everyDay = ArrayList<Transaction>()
        var day = d(1, 1)
        while (day <= d(3, 31)) {
            val new = RecurringPlanner.plan(c, held(everyDay), day) { everyDay.toList() }
            assertTrue(new.all { it.date <= day }, "nothing is written for a day that has not come")
            everyDay += new
            day = day.plusDays(1)
        }
        assertEquals(everyDay.size, everyDay.map { it.id }.toSet().size, "never twice")
        fun datesOf(id: String) = everyDay.filter { it.recurringRuleId == id }.map { it.date }.sorted()
        assertEquals(listOf(d(1, 1), d(2, 1), d(3, 1)), datesOf(rentId))
        assertEquals(listOf(d(1, 15), d(2, 15), d(3, 15)), datesOf(gymId))
        assertEquals(generateSequence(d(1, 7)) { it.plusWeeks(1) }.takeWhile { it <= d(3, 31) }.toList(), datesOf(lotteryId))
        // Looked at again, nothing is due.
        assertEquals(emptyList(), RecurringPlanner.plan(c, held(everyDay), d(3, 31)) { everyDay.toList() })
        // A phone that was away all of March writes what is due since then: the same rows.
        val away = RecurringPlanner.due(c, held(everyDay.filter { it.date < d(3, 1) }), d(3, 31))
        assertEquals(everyDay.filter { it.date >= d(3, 1) }.map { it.id }.toSet(), away.map { it.id }.toSet())
        assertEquals(everyDay.filter { it.date >= d(3, 1) }.associateBy { it.id }, away.associateBy { it.id })
    }

    private fun weekly(day: Int) = buildJsonObject {
        put("freq", "weekly"); put("interval", 1)
        put("by_weekday", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(day))))
    }
    private fun quarterly(day: Int) = buildJsonObject { put("freq", "monthly"); put("interval", 3); put("by_month_day", day) }
    private fun daily() = buildJsonObject { put("freq", "daily"); put("interval", 1) }

    @Test
    fun `looking only ever adds rows, never ahead of the day, never twice, never over what is held`() = runTest {
        var written = 0
        for (seed in 1..60) {
            val random = java.util.Random(seed.toLong())
            val rules = (0 until 1 + random.nextInt(4)).map { i ->
                val schedule = when (random.nextInt(5)) {
                    0, 1 -> monthly(1 + random.nextInt(31))
                    2 -> weekly(1 + random.nextInt(7))
                    3 -> quarterly(1 + random.nextInt(28))
                    else -> daily()
                }
                rule("00000000-0000-4000-8000-0000000006%02d".format(i), "Item $i", schedule,
                    start = d(1, 1).minusDays(random.nextInt(200).toLong()), amount = 1_000L * (1 + random.nextInt(90)),
                    auto = random.nextInt(6) != 0)
            }
            val c = config(*rules.toTypedArray())
            // What the person wrote by hand, some of it looking like a fixed cost, some of it deleted.
            val held = ArrayList<Transaction>()
            repeat(random.nextInt(25)) {
                val like = c.recurringRules.randomOrNull(random.asKotlinRandom())
                held += Fixtures.expense(like?.template?.amountMinor ?: 1_234, id = Fixtures.newId())
                    .copy(date = d(1, 1).plusDays(random.nextInt(120).toLong()), categoryId = category,
                        note = if (random.nextBoolean()) like?.name ?: "" else "GROCERY STORE 01",
                        status = if (random.nextInt(8) == 0) io.github.sirallap.fulla.core.model.Status.DELETED else io.github.sirallap.fulla.core.model.Status.ACTIVE)
            }
            val before = held.toList()
            var day = d(1, 1)
            while (day <= d(5, 1)) {
                val new = RecurringPlanner.plan(c, held(held), day) { held.toList() }
                for (row in new) {
                    assertTrue(row.id !in held(held), "seed $seed: never over what is held")
                    assertTrue(row.date <= day, "seed $seed: never ahead of the day ($day): ${row.date}")
                    assertTrue(row.date >= day.minusDays(RecurringPlanner.LOOKBACK_DAYS), "seed $seed: never further back than the catch-up")
                    assertEquals(DeterministicId.occurrence(row.recurringRuleId!!, row.date), row.id, "seed $seed: the id every phone derives")
                    assertEquals(io.github.sirallap.fulla.core.model.Status.ACTIVE, row.status)
                    assertEquals(row.date, row.occurrenceDate)
                }
                held += new
                assertEquals(emptyList(), RecurringPlanner.plan(c, held(held), day) { held.toList() }, "seed $seed: looking twice writes once")
                day = day.plusDays(1)
            }
            assertEquals(held.size, held.map { it.id }.toSet().size, "seed $seed: no id twice")
            assertEquals(before, held.take(before.size), "seed $seed: what was there is exactly as it was")
            val generated = held.drop(before.size)
            assertEquals(generated.size, generated.map { it.recurringRuleId to it.occurrenceDate }.toSet().size, "seed $seed: an occurrence once")
            assertTrue(generated.all { g -> c.recurringRules.single { it.id == g.recurringRuleId }.let { it.active && it.autoCreate } },
                "seed $seed: only rules that write themselves")
            written += generated.size
        }
        assertTrue(written > 500, "the scenarios do write things ($written)")
    }

    @Test
    fun `a rule that has already written keeps exactly the catch-up it always had`() = runTest {
        // What every phone did before this version: every day of the last 62 that nothing wrote. For a rule that has written
        // before (the households where fixed costs already worked) the answer must not have changed, however rarely the app is opened.
        var total = 0
        for (seed in 1..60) {
            val random = java.util.Random(seed.toLong())
            val rules = (0 until 1 + random.nextInt(4)).map { i ->
                val schedule = when (random.nextInt(4)) {
                    0 -> monthly(1 + random.nextInt(31))
                    1 -> weekly(1 + random.nextInt(7))
                    2 -> quarterly(1 + random.nextInt(28))
                    else -> monthly(1 + random.nextInt(31))
                }
                rule("00000000-0000-4000-8000-0000000007%02d".format(i), "Item $i", schedule, start = d(1, 1).minusDays(random.nextInt(120).toLong()))
            }
            val c = config(*rules.toTypedArray())
            val held = ArrayList<Transaction>()
            var day = d(1, 1)
            var compared = 0
            while (day <= d(9, 30)) {
                val new = RecurringPlanner.plan(c, held(held), day) { held.toList() }
                val wrote = held.mapNotNull { it.recurringRuleId }.toSet()
                val old = c.recurringRules.flatMap { r ->
                    io.github.sirallap.fulla.core.recurring.Scheduler.occurrences(r, day.minusDays(RecurringPlanner.LOOKBACK_DAYS), day)
                        .map { DeterministicId.occurrence(r.id, it) to r.id }
                }.filter { it.first !in held(held) && it.second in wrote }
                assertEquals(old.map { it.first }.toSet(), new.filter { it.recurringRuleId in wrote }.map { it.id }.toSet(), "seed $seed, $day")
                compared += old.size
                // The household's history is what the old version wrote: every rule's whole catch-up, the first time too.
                held += c.recurringRules.flatMap { r ->
                    io.github.sirallap.fulla.core.recurring.Scheduler.occurrences(r, day.minusDays(RecurringPlanner.LOOKBACK_DAYS), day)
                        .filter { DeterministicId.occurrence(r.id, it) !in held(held) }.map { RecurringPlanner.occurrence(r, it, c) }
                }
                day = day.plusDays(1L + random.nextInt(25))
            }
            total += compared
        }
        assertTrue(total > 200, "the scenarios do compare things ($total)")
    }

    @Test
    fun `the period a rule starts with is the household's own`() {
        val c = config()
        assertEquals(d(3, 1), RecurringPlanner.currentPeriodStart(c, emptyList(), d(3, 17)), "calendar months by default")
        val salary = io.github.sirallap.fulla.core.rules.PeriodAnchors.mark(
            Fixtures.expense(150_000).copy(kind = io.github.sirallap.fulla.core.model.TransactionKind.INCOME, date = d(2, 27)), true)
        assertEquals(d(2, 27), RecurringPlanner.currentPeriodStart(c, listOf(salary), d(3, 17)), "from the salary that started it")
    }

    @Test
    fun `where a rule begins when it is saved`() {
        val monthlyRule = RecurringRule(rentId, "Rent", Fixtures.expense(), Schedule(Frequency.MONTHLY, byMonthDay = 1), d(2, 1), autoCreate = true)
        val every = Schedule(Frequency.MONTHLY, byMonthDay = 1)
        val periodStart = d(3, 1)
        val today = d(3, 10)
        // A new rule applies from the start of this period, and never from a day that has not come.
        assertEquals(d(3, 1), RecurringPlanner.startFor(null, every, true, null, periodStart, today))
        assertEquals(d(3, 10), RecurringPlanner.startFor(null, every, true, null, d(3, 20), today))
        // One that is changed keeps its start.
        assertEquals(d(2, 1), RecurringPlanner.startFor(monthlyRule, every, true, null, periodStart, today))
        // Resumed, or on another day, it applies from today on: what it wrote stays, a pause is not charged for.
        assertEquals(d(3, 10), RecurringPlanner.startFor(monthlyRule.copy(active = false), every, true, null, periodStart, today))
        assertEquals(d(3, 10), RecurringPlanner.startFor(monthlyRule, Schedule(Frequency.MONTHLY, byMonthDay = 5), true, null, periodStart, today))
        // Every three months from a month: the first month of the cycle that is not behind.
        val quarterly = Schedule(Frequency.MONTHLY, interval = 3, byMonthDay = 15)
        assertEquals(d(4, 1), RecurringPlanner.startFor(null, quarterly, true, 4, periodStart, today), "April, July, October, January")
        assertEquals(d(3, 1), RecurringPlanner.startFor(null, quarterly, true, 12, periodStart, today), "December, March: this one")
        assertEquals(d(5, 1), RecurringPlanner.startFor(null, quarterly, true, 5, periodStart, today), "May, August, November, February: May")
        assertEquals(d(3, 1), RecurringPlanner.startFor(null, quarterly, true, 6, periodStart, today), "June, September, December, March: this one too")
        // A cycle already running keeps its start; moving its first month moves the start from today on.
        val running = monthlyRule.copy(schedule = quarterly, startDate = d(3, 1))
        assertEquals(d(3, 1), RecurringPlanner.startFor(running, quarterly, true, 3, periodStart, today))
        assertEquals(d(4, 1), RecurringPlanner.startFor(running, quarterly, true, 4, periodStart, today))
    }
}
