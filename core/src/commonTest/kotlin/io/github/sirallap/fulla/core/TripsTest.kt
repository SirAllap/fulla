// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.time.YearMonth
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.trips.Trip
import io.github.sirallap.fulla.core.trips.TripKind
import io.github.sirallap.fulla.core.trips.TripPhase
import io.github.sirallap.fulla.core.trips.Trips
import io.github.sirallap.fulla.core.time.LocalDate
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
    fun spent_a_refund_and_what_is_left() {
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
    fun deleted_rows_are_ignored() {
        val txs = listOf(expense(21_500, porto.id).copy(status = Status.DELETED))
        val totals = Trips.totals(porto, txs)
        assertEquals(0, totals.spentMinor)
        assertEquals(30_000, totals.leftMinor)
    }

    @Test
    fun spending_past_the_budget_is_over_not_negative_left() {
        val txs = listOf(expense(35_000, porto.id))
        val totals = Trips.totals(porto, txs)
        assertEquals(0, totals.leftMinor)
        assertEquals(5_000, totals.overMinor)
    }

    @Test
    fun a_trip_with_no_budget_only_tracks_spending() {
        val untracked = porto.copy(budgetMinor = null)
        val totals = Trips.totals(untracked, listOf(expense(1_000, untracked.id)))
        assertNull(totals.leftMinor)
        assertEquals(0, totals.overMinor)
        assertNull(Trips.perDay(untracked, totals, LocalDate.of(2030, 8, 13)))
    }

    @Test
    fun per_day_before_during_first_and_last_day_included_and_after_the_trip() {
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
    fun over_budget_shows_zero_a_day_not_a_negative_figure() {
        val over = Trips.totals(porto, listOf(expense(35_000, porto.id)))
        val perDay = Trips.perDay(porto, over, LocalDate.of(2030, 8, 15))
        assertEquals(0, perDay?.amountMinor)
        assertEquals(true, perDay?.over)
    }

    @Test
    fun phases_are_upcoming_active_and_finished() {
        assertEquals(TripPhase.UPCOMING, Trips.phase(porto, LocalDate.of(2030, 8, 1)))
        assertEquals(TripPhase.ACTIVE, Trips.phase(porto, LocalDate.of(2030, 8, 15)))
        assertEquals(TripPhase.FINISHED, Trips.phase(porto, LocalDate.of(2030, 9, 1)))
    }

    @Test
    fun zero_decimal_currencies_never_see_a_fractional_minor_unit() {
        // A yen trip: no cents to round to, so every figure is a whole number already.
        val tokyo = porto.copy(id = "00000000-0000-4000-8000-000000000502", budgetMinor = 100_000)
        val totals = Trips.totals(tokyo, listOf(expense(37_000, tokyo.id)))
        assertEquals(63_000, totals.leftMinor)
        val perDay = Trips.perDay(tokyo, totals, LocalDate.of(2030, 8, 16))
        assertEquals(63_000 / 4, perDay?.amountMinor)
    }

    @Test
    fun an_edited_row_with_no_trip_does_not_gain_the_active_one() {
        val alice = "00000000-0000-4000-8000-0000000000a1"
        val onTrip = porto.copy(memberIds = listOf(alice))
        // Groceries at home, "No trip", dated inside Porto: re-saving the
        // edit must not silently add Porto (H2).
        assertNull(Trips.initialTripId(isNew = false, existingTripId = null, trips = listOf(onTrip), date = LocalDate.of(2030, 8, 14), me = alice))
        // A new row picks up whatever trip is active on its date, for a
        // person who is actually on it.
        assertEquals(onTrip.id, Trips.initialTripId(isNew = true, existingTripId = null, trips = listOf(onTrip), date = LocalDate.of(2030, 8, 14), me = alice))
        // An edit keeps its own trip even outside every trip's dates.
        assertEquals(onTrip.id, Trips.initialTripId(isNew = false, existingTripId = onTrip.id, trips = listOf(onTrip), date = LocalDate.of(2030, 7, 1), me = alice))
    }

    @Test
    fun defaultFor_only_auto_selects_a_trip_the_person_is_actually_on() {
        val alice = "00000000-0000-4000-8000-0000000000a1"
        val bob = "00000000-0000-4000-8000-0000000000b2"
        val day = LocalDate.of(2030, 8, 14)
        // Bob's spouse Alice is a household member but never on his work
        // trip: a new expense of hers dated inside it must not default onto
        // it (the owner-feedback bug this whole thing exists to fix).
        val bobsTrip = porto.copy(memberIds = listOf(bob))
        assertNull(Trips.defaultFor(listOf(bobsTrip), day, alice))
        assertEquals(bobsTrip, Trips.defaultFor(listOf(bobsTrip), day, bob))
        // A trip saved before member_ids existed (empty list): nobody
        // auto-selects it, not even whoever actually created it.
        val legacy = porto.copy(memberIds = emptyList())
        assertNull(Trips.defaultFor(listOf(legacy), day, alice))
        assertNull(Trips.defaultFor(listOf(legacy), day, bob))
        // Nobody entering it at all: never a default.
        assertNull(Trips.defaultFor(listOf(bobsTrip), day, null))
        // Two trips active on the same day, only one includes this person.
        val shared = porto.copy(
            id = "00000000-0000-4000-8000-000000000603",
            startDate = LocalDate.of(2030, 8, 10), endDate = LocalDate.of(2030, 8, 20),
            memberIds = listOf(alice, bob),
        )
        assertEquals(bobsTrip, Trips.defaultFor(listOf(bobsTrip, shared), day, bob))
        assertEquals(shared, Trips.defaultFor(listOf(shared), day, alice))
        // An archived trip never auto-selects, even if the person is on it.
        assertNull(Trips.defaultFor(listOf(bobsTrip.copy(archived = true)), day, bob))
    }

    @Test
    fun editing_a_row_that_never_learned_a_trip_keeps_it_unknown_unless_the_chip_was_touched() {
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
    fun on_overlap_the_latest_start_wins_ties_go_to_the_lowest_id() {
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
    fun a_trip_spanning_two_periods_splits_its_budget_spending_correctly() {
        val spansMonths = porto.copy(startDate = LocalDate.of(2030, 7, 28), endDate = LocalDate.of(2030, 8, 3), inCategoryBudgets = true)
        val config = Fixtures.config().copy(trips = listOf(spansMonths))
        val analytics = io.github.sirallap.fulla.core.analytics.Analytics(config, io.github.sirallap.fulla.core.rules.PeriodRule())
        val txs = listOf(
            expense(1_000, spansMonths.id, date = LocalDate.of(2030, 7, 29)),
            expense(2_000, spansMonths.id, date = LocalDate.of(2030, 8, 1)),
        )
        val july = analytics.budgetSpend(txs, YearMonth.of(2030, 7), config.trips).sumOf { it.amountMinor }
        val august = analytics.budgetSpend(txs, YearMonth.of(2030, 8), config.trips).sumOf { it.amountMinor }
        assertEquals(1_000, july)
        assertEquals(2_000, august)
    }

    @Test
    fun a_trip_that_counts_in_monthly_budgets_is_not_excluded_from_budgetSpend() {
        val counted = porto.copy(inCategoryBudgets = true)
        val config = Fixtures.config().copy(trips = listOf(counted))
        val analytics = io.github.sirallap.fulla.core.analytics.Analytics(config, io.github.sirallap.fulla.core.rules.PeriodRule())
        val txs = listOf(expense(1_000, counted.id))
        val included = analytics.budgetSpend(txs, YearMonth.of(2030, 8), config.trips)
        assertEquals(1, included.size)
        val excludedTrip = counted.copy(inCategoryBudgets = false)
        val excluded = analytics.budgetSpend(txs.map { it.copy(tripId = excludedTrip.id) }, YearMonth.of(2030, 8), listOf(excludedTrip))
        assertTrue(excluded.isEmpty())
    }

    @Test
    fun a_brand_new_trip_defaults_to_holiday_and_every_wire_value_round_trips() {
        assertEquals(TripKind.HOLIDAY, porto.kind)
        for (kind in TripKind.entries) assertEquals(kind, TripKind.of(kind.wire))
    }

    @Test
    fun an_unrecognised_or_absent_trip_kind_falls_back_to_other_never_a_crash() {
        // A kind a future app version added, read by this one: forward
        // compatible, not refused.
        assertEquals(TripKind.OTHER, TripKind.of("safari"))
        assertEquals(TripKind.OTHER, TripKind.of(""))
        assertEquals(TripKind.OTHER, TripKind.of(null))
    }
}
