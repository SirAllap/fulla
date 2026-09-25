// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.local

import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.recurring.Scheduler
import java.time.LocalDate

/**
 * The occurrences of recurring items that are due and not yet written.
 *
 * Each occurrence's id is derived from its rule and date, so two phones that
 * both generate it write the same row and the sync merges them. An occurrence
 * somebody deleted keeps its id as a tombstone, is in [existingIds], and is
 * never generated again.
 */
object RecurringPlanner {

    /** How far back a rule that was just created, or a phone that was off, catches up. */
    const val LOOKBACK_DAYS = 62L

    fun due(config: Config, existingIds: Set<String>, today: LocalDate): List<Transaction> =
        config.recurringRules.filter { it.active && it.autoCreate }.flatMap { rule ->
            Scheduler.occurrences(rule, today.minusDays(LOOKBACK_DAYS), today)
                .map { date -> date to DeterministicId.occurrence(rule.id, date) }
                .filter { (_, id) -> id !in existingIds }
                .map { (date, id) ->
                    rule.template.copy(
                        id = id,
                        date = date,
                        status = Status.ACTIVE,
                        recurringRuleId = rule.id,
                        occurrenceDate = date,
                        createdByMemberId = config.meMemberId,
                    )
                }
        }
}
