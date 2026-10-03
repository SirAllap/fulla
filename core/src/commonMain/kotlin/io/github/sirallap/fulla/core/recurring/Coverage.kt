// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.recurring

import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.text.normalizeName
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.ChronoUnit
import kotlin.math.abs

/** One day a recurring item falls due on. Its [id] is the id of the row that writes it. */
data class Occurrence(val rule: RecurringRule, val date: LocalDate) {
    val id: String get() = DeterministicId.occurrence(rule.id, date)
}

/**
 * Whether somebody already wrote an occurrence down by hand.
 *
 * A person who tracked their rent by hand and then adds it as a recurring
 * item that writes itself must not be charged twice for the same month. An
 * occurrence counts as written by hand when a row nobody generated stands for
 * it: same kind, an amount within [AMOUNT_PERCENT] % and a date within
 * [toleranceDays] of the day it falls due, and it is the same thing, by its
 * category or by its name (a note that reads like the recurring item's name:
 * "Rent" typed under another category is still the rent). Each such row
 * stands for one occurrence at most, the nearest in time.
 *
 * The phone's planner (what to write) and the forecast (what is still to be
 * charged) both ask here, so they always agree.
 */
object Coverage {

    /** An amount that differs by no more than this much still is "the same payment". */
    const val AMOUNT_PERCENT = 5L

    /**
     * How many days away from its day a hand-written row may be and still
     * stand for the occurrence: a payment is made a day or two early or late.
     * A rule that falls due every day, or on several days of a week, leaves
     * no room: its days are too close together to tell apart.
     */
    fun toleranceDays(schedule: Schedule): Long = when (schedule.frequency) {
        Frequency.DAILY -> 0
        Frequency.WEEKLY -> if (schedule.byWeekday.size == 1) 1 else 0
        Frequency.MONTHLY, Frequency.YEARLY -> 3
    }

    /** Whether [row] could be the hand-written version of an occurrence of [rule]: everything but the date. */
    fun looksLike(rule: RecurringRule, row: Transaction): Boolean {
        val template = rule.template
        if (!row.isActive || row.recurringRuleId != null || row.tripId != null || row.kind != template.kind) return false
        if (template.amountMinor <= 0 || abs(row.amountMinor - template.amountMinor) * 100 > template.amountMinor * AMOUNT_PERCENT) return false
        val sameCategory = template.categoryId != null && row.categoryId == template.categoryId
        val sameName = row.note.isNotBlank() && row.note.normalizeName() == rule.name.normalizeName()
        return sameCategory || sameName
    }

    /**
     * The row written by hand that stands for each of [occurrences], for the
     * occurrences that have one. [rows] are the household's rows; only the
     * ones that look like a recurring item's are used.
     */
    fun byHand(occurrences: List<Occurrence>, rows: Iterable<Transaction>): Map<Occurrence, Transaction> {
        if (occurrences.isEmpty()) return emptyMap()
        val candidates = rows.filter { it.isActive && it.recurringRuleId == null && it.tripId == null }.groupBy { it.kind }
        if (candidates.isEmpty()) return emptyMap()
        val taken = HashSet<String>()
        val out = LinkedHashMap<Occurrence, Transaction>()
        // Oldest first, so the answer does not depend on the order the caller listed them in.
        for (o in occurrences.sortedWith(compareBy({ it.date }, { it.rule.id }))) {
            val template = o.rule.template
            val tolerance = toleranceDays(o.rule.schedule)
            val best = candidates[template.kind].orEmpty()
                .filter { it.id !in taken && looksLike(o.rule, it) }
                .map { it to abs(ChronoUnit.DAYS.between(it.date, o.date)) }
                .filter { (_, days) -> days <= tolerance }
                .minWithOrNull(compareBy({ it.second }, { abs(it.first.amountMinor - template.amountMinor) }, { it.first.id }))
                ?: continue
            taken += best.first.id
            out[o] = best.first
        }
        return out
    }
}
