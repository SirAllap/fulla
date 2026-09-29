// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleWireTest {
    private fun rule(schedule: Schedule) = RecurringRule(
        id = "00000000-0000-4000-8000-00000000abcd", name = "Tax",
        template = Transaction(id = "", kind = TransactionKind.EXPENSE, date = LocalDate.of(2030, 1, 1), amountMinor = 30_000, createdAt = "", clientUpdatedAt = ""),
        schedule = schedule, startDate = LocalDate.of(2030, 1, 1), autoCreate = true,
    )

    @Test
    fun `chosen months and an interval survive the wire`() {
        for (s in listOf(
            Schedule(Frequency.MONTHLY, byMonthDay = 10, byMonths = listOf(10, 12)),
            Schedule(Frequency.MONTHLY, interval = 3, byMonthDay = 15),
            Schedule(Frequency.MONTHLY, byMonthDay = -1),
        )) assertEquals(s, Wire.recurring(Wire.recurring(rule(s)))?.schedule)
    }
}
