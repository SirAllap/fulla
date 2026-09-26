// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.trips.Trip
import io.github.sirallap.fulla.core.trips.TripPhase
import io.github.sirallap.fulla.core.trips.Trips
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Porto: 2030-08-12 to 2030-08-19, invented, like every fixture in this file. */
class TripsTest {

    private val porto = Trip(
        id = "00000000-0000-4000-8000-000000000501", name = "Porto",
        startDate = LocalDate.of(2030, 8, 12), endDate = LocalDate.of(2030, 8, 19), budgetMinor = 30_000,
    )

    private fun expense(amount: Long, trip: String?, date: LocalDate = LocalDate.of(2030, 8, 13), kind: TransactionKind = TransactionKind.EXPENSE) =
        Fixtures.expense(amount = amount, date = date).copy(kind = kind, tripId = trip)

    @Test
    fun `spent, a refund and what is left`() {
        val txs = listOf(
            expense(21_500, porto.id),
            expense(2_000, porto.id, kind = TransactionKind.REFUND),
            expense(999, null), // another household expense, not on the trip
        )
        val totals = Trips.totals(porto, txs)
        assertEquals(19_500, totals.spentMinor)
        assertEquals(10_500, totals.leftMinor)
        assertEquals(0, totals.overMinor)
    }

    @Test
    fun `deleted rows are ignored`() {
        val txs = listOf(expense(21_500, porto.id).copy(status = Status.DELETED))
        val totals = Trips.totals(porto, txs)
        assertEquals(0, totals.spentMinor)
        assertEquals(30_000, totals.leftMinor)
    }

    @Test
    fun `spending past the budget is over, not negative left`() {
        val txs = listOf(expense(35_000, porto.id))
        val totals = Trips.totals(porto, txs)
        assertEquals(0, totals.leftMinor)
        assertEquals(5_000, totals.overMinor)
    }

    @Test
    fun `a trip with no budget only tracks spending`() {
        val untracked = porto.copy(budgetMinor = null)
        val totals = Trips.totals(untracked, listOf(expense(1_000, untracked.id)))
        assertNull(totals.leftMinor)
        assertEquals(0, totals.overMinor)
        assertNull(Trips.perDay(untracked, totals, LocalDate.of(2030, 8, 13)))
    }

    @Test
    fun `per day before, during (first and last day included) and after the trip`() {
        val txs = listOf(expense(21_500, porto.id), expense(2_000, porto.id, kind = TransactionKind.REFUND))
        val totals = Trips.totals(porto, txs) // spent 19500, left 10500
        // Before the start: the plain average over all 8 days (12..19 inclusive).
        val before = Trips.perDay(porto, Trips.totals(porto, emptyList()), LocalDate.of(2030, 8, 1))
        assertEquals(30_000 / 8, before?.amountMinor)
        assertEquals(false, before?.over)
        // During, today included: 4 days remain (16, 17, 18, 19).
        val during = Trips.perDay(porto, totals, LocalDate.of(2030, 8, 16))
        assertEquals(2_625, during?.amountMinor)
        // The first day of the trip still counts as "during".
        val firstDay = Trips.perDay(porto, Trips.totals(porto, emptyList()), porto.startDate)
        assertEquals(TripPhase.ACTIVE, Trips.phase(porto, porto.startDate))
        assertTrue(firstDay != null)
        // The last day still counts too.
        assertEquals(TripPhase.ACTIVE, Trips.phase(porto, porto.endDate))
        // After the end: null.
        assertNull(Trips.perDay(porto, totals, porto.endDate.plusDays(1)))
    }

    @Test
    fun `over budget shows zero a day, not a negative figure`() {
        val over = Trips.totals(porto, listOf(expense(35_000, porto.id)))
        val perDay = Trips.perDay(porto, over, LocalDate.of(2030, 8, 15))
        assertEquals(0, perDay?.amountMinor)
        assertEquals(true, perDay?.over)
    }

    @Test
    fun `phases are upcoming, active and finished`() {
        assertEquals(TripPhase.UPCOMING, Trips.phase(porto, LocalDate.of(2030, 8, 1)))
        assertEquals(TripPhase.ACTIVE, Trips.phase(porto, LocalDate.of(2030, 8, 15)))
        assertEquals(TripPhase.FINISHED, Trips.phase(porto, LocalDate.of(2030, 9, 1)))
    }

    @Test
    fun `zero-decimal currencies never see a fractional minor unit`() {
        // A yen trip: no cents to round to, so every figure is a whole number already.
        val tokyo = porto.copy(id = "00000000-0000-4000-8000-000000000502", budgetMinor = 100_000)
        val totals = Trips.totals(tokyo, listOf(expense(37_000, tokyo.id)))
        assertEquals(63_000, totals.leftMinor)
        val perDay = Trips.perDay(tokyo, totals, LocalDate.of(2030, 8, 16))
        assertEquals(63_000 / 4, perDay?.amountMinor)
    }

    @Test
    fun `an edited row with no trip does not gain the active one`() {
        // Groceries at home, "No trip", dated inside Porto: re-saving the
        // edit must not silently add Porto (H2).
        assertNull(Trips.initialTripId(isNew = false, existingTripId = null, trips = listOf(porto), date = LocalDate.of(2030, 8, 14)))
        // A new row still picks up whatever trip is active on its date.
        assertEquals(porto.id, Trips.initialTripId(isNew = true, existingTripId = null, trips = listOf(porto), date = LocalDate.of(2030, 8, 14)))
        // An edit keeps its own trip even outside every trip's dates.
        assertEquals(porto.id, Trips.initialTripId(isNew = false, existingTripId = porto.id, trips = listOf(porto), date = LocalDate.of(2030, 7, 1)))
    }

    @Test
    fun `editing a row that never learned a trip keeps it unknown unless the chip was touched`() {
        // An old app version's own row: tripKnown false, no trip. Fixing the
        // amount must not push an explicit "trip_id": null and wipe a trip
        // another phone set (H1).
        assertEquals(false, Trips.tripKnownForEdit(templateTripKnown = false, chipTouched = false, tripId = null))
        // The person picked a trip, or explicitly chose "No trip": tripKnown
        // must be sent from now on.
        assertEquals(true, Trips.tripKnownForEdit(templateTripKnown = false, chipTouched = true, tripId = null))
        assertEquals(true, Trips.tripKnownForEdit(templateTripKnown = false, chipTouched = true, tripId = porto.id))
        // A row that already knew its trip always sends the key.
        assertEquals(true, Trips.tripKnownForEdit(templateTripKnown = true, chipTouched = false, tripId = null))
        // A brand new row (no template) always knows.
        assertEquals(true, Trips.tripKnownForEdit(templateTripKnown = null, chipTouched = false, tripId = null))
    }

    @Test
    fun `on overlap the latest start wins, ties go to the lowest id`() {
        val early = porto.copy(id = "00000000-0000-4000-8000-000000000601", startDate = LocalDate.of(2030, 8, 10), endDate = LocalDate.of(2030, 8, 20))
        val late = porto.copy(id = "00000000-0000-4000-8000-000000000602", startDate = LocalDate.of(2030, 8, 14), endDate = LocalDate.of(2030, 8, 18))
        assertEquals(late, Trips.activeOn(listOf(early, late), LocalDate.of(2030, 8, 15)))
        val tieLow = late.copy(id = "00000000-0000-4000-8000-000000000602")
        val tieHigh = late.copy(id = "00000000-0000-4000-8000-000000000603")
        assertEquals(tieLow, Trips.activeOn(listOf(tieHigh, tieLow), LocalDate.of(2030, 8, 15)))
        // An archived trip never wins, even if it covers the date.
        assertNull(Trips.activeOn(listOf(late.copy(archived = true)), LocalDate.of(2030, 8, 15)))
    }

    @Test
    fun `a trip spanning two periods splits its budget spending correctly`() {
        val spansMonths = porto.copy(startDate = LocalDate.of(2030, 7, 28), endDate = LocalDate.of(2030, 8, 3), inCategoryBudgets = true)
        val config = Fixtures.config().copy(trips = listOf(spansMonths))
        val analytics = io.github.sirallap.fulla.core.analytics.Analytics(config, io.github.sirallap.fulla.core.rules.PeriodRule())
        val txs = listOf(
            expense(1_000, spansMonths.id, date = LocalDate.of(2030, 7, 29)),
            expense(2_000, spansMonths.id, date = LocalDate.of(2030, 8, 1)),
        )
        val july = analytics.budgetSpend(txs, java.time.YearMonth.of(2030, 7), config.trips).sumOf { it.amountMinor }
        val august = analytics.budgetSpend(txs, java.time.YearMonth.of(2030, 8), config.trips).sumOf { it.amountMinor }
        assertEquals(1_000, july)
        assertEquals(2_000, august)
    }

    @Test
    fun `a trip that counts in monthly budgets is not excluded from budgetSpend`() {
        val counted = porto.copy(inCategoryBudgets = true)
        val config = Fixtures.config().copy(trips = listOf(counted))
        val analytics = io.github.sirallap.fulla.core.analytics.Analytics(config, io.github.sirallap.fulla.core.rules.PeriodRule())
        val txs = listOf(expense(1_000, counted.id))
        val included = analytics.budgetSpend(txs, java.time.YearMonth.of(2030, 8), config.trips)
        assertEquals(1, included.size)
        val excludedTrip = counted.copy(inCategoryBudgets = false)
        val excluded = analytics.budgetSpend(txs.map { it.copy(tripId = excludedTrip.id) }, java.time.YearMonth.of(2030, 8), listOf(excludedTrip))
        assertTrue(excluded.isEmpty())
    }
}
