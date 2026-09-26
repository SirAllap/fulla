// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.trips

import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A trip or event with its own budget (docs/data-model.md). Structure, like a
 * category or an account: it changes rarely and only with a connection, and
 * arrives in the config bundle. `budgetMinor` null means "track only, no
 * jar". `inCategoryBudgets` off (the default) keeps the trip's spending out
 * of category budgets, which already has its own jar; the money still counts
 * in the month's totals either way (Analytics.summary never looks at trips).
 *
 * Example (invented): "Porto", 2030-08-12 to 2030-08-19, 300.00 EUR.
 */
data class Trip(
    val id: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val budgetMinor: Long? = null,
    val inCategoryBudgets: Boolean = false,
    val archived: Boolean = false,
)

enum class TripPhase { UPCOMING, ACTIVE, FINISHED }

data class TripTotals(
    val spentMinor: Long,
    /** Null when the trip has no budget: there is nothing to be "left". */
    val leftMinor: Long?,
    /** How far spending went past the budget, 0 when within it or untracked. */
    val overMinor: Long,
)

data class TripPerDay(val amountMinor: Long, val over: Boolean, val days: Int)

/**
 * Everything a trip's own screen needs, computed from the phone's rows. No
 * rule here exists twice: there is no SQL trip report view in v1, so totals
 * live only on the phone.
 */
object Trips {

    /**
     * The trip a row starts with: a brand new expense or refund picks up
     * whatever is [activeOn] its date, but an edit always keeps the row's own
     * trip, even none, so re-saving a row dated inside a trip never adds one
     * behind the person's back.
     */
    fun initialTripId(isNew: Boolean, existingTripId: String?, trips: List<Trip>, date: LocalDate): String? =
        if (!isNew) existingTripId else activeOn(trips, date)?.id

    /** The trip [date] falls in, or null. Overlaps are allowed: the latest start wins, ties go to the lowest id. */
    fun activeOn(trips: List<Trip>, date: LocalDate): Trip? = trips
        .filter { !it.archived && it.startDate <= date && date <= it.endDate }
        .sortedWith(compareByDescending<Trip> { it.startDate }.thenBy { it.id })
        .firstOrNull()

    /**
     * Whether an edited row should carry an explicit trip_id key on its next
     * push. A row this phone never learned a trip for ([templateTripKnown]
     * false: an old app version's own row, re-encoded without ever having
     * heard of the column) must keep that unknown state unless the person
     * touched the trip chip in this very edit ([chipTouched]): otherwise the
     * edit would push an explicit "trip_id": null and clear a trip another
     * phone set, the same trap `fulla.merge_extras` avoids for custom fields.
     * [templateTripKnown] is null for a brand new row, which always knows.
     */
    fun tripKnownForEdit(templateTripKnown: Boolean?, chipTouched: Boolean, tripId: String?): Boolean =
        !(templateTripKnown == false && !chipTouched && tripId == null)

    fun phase(trip: Trip, today: LocalDate): TripPhase = when {
        today < trip.startDate -> TripPhase.UPCOMING
        today > trip.endDate -> TripPhase.FINISHED
        else -> TripPhase.ACTIVE
    }

    /** Spending contribution: expenses count positive, refunds negative. Same rule as Analytics. */
    private fun spend(t: Transaction): Long = when (t.kind) {
        TransactionKind.EXPENSE -> t.amountMinor
        TransactionKind.REFUND -> -t.amountMinor
        else -> 0
    }

    /** Active rows of [trip] only; deleted rows never count. */
    fun totals(trip: Trip, txs: Iterable<Transaction>): TripTotals {
        val spent = txs.filter { it.isActive && it.tripId == trip.id }.sumOf(::spend)
        val budget = trip.budgetMinor ?: return TripTotals(spent, null, 0)
        val diff = budget - spent
        return TripTotals(spent, maxOf(diff, 0), maxOf(-diff, 0))
    }

    /**
     * A daily figure for the trip's card, or null once it is over or with no
     * budget: before it starts, the plain average; during it, what is left
     * divided by the days remaining, today included; once spending has
     * passed the budget, 0 and `over`.
     */
    fun perDay(trip: Trip, totals: TripTotals, today: LocalDate): TripPerDay? {
        val budget = trip.budgetMinor ?: return null
        if (today > trip.endDate) return null
        val totalDays = (ChronoUnit.DAYS.between(trip.startDate, trip.endDate) + 1).toInt()
        if (today < trip.startDate) return TripPerDay(budget / totalDays, false, totalDays)
        val daysRemaining = (ChronoUnit.DAYS.between(today, trip.endDate) + 1).toInt()
        if ((totals.leftMinor ?: 0) <= 0) return TripPerDay(0, true, daysRemaining)
        return TripPerDay((totals.leftMinor ?: 0) / daysRemaining, false, daysRemaining)
    }
}
