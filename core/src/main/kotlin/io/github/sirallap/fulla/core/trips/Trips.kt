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

data class TripPerDay(val amountMinor: Long, val over: Boolean)

/**
 * Everything a trip's own screen needs, computed from the phone's rows. No
 * rule here exists twice: there is no SQL trip report view in v1, so totals
 * live only on the phone.
 */
object Trips {

    /** The trip [date] falls in, or null. Overlaps are allowed: the latest start wins, ties go to the lowest id. */
    fun activeOn(trips: List<Trip>, date: LocalDate): Trip? = trips
        .filter { !it.archived && it.startDate <= date && date <= it.endDate }
        .sortedWith(compareByDescending<Trip> { it.startDate }.thenBy { it.id })
        .firstOrNull()

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
        val totalDays = ChronoUnit.DAYS.between(trip.startDate, trip.endDate) + 1
        if (today < trip.startDate) return TripPerDay(budget / totalDays, false)
        if (totals.overMinor > 0) return TripPerDay(0, true)
        val daysRemaining = ChronoUnit.DAYS.between(today, trip.endDate) + 1
        return TripPerDay((totals.leftMinor ?: 0) / daysRemaining, false)
    }
}
