// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.widget.WidgetFigures
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetFiguresTest {
    private val jan = YearMonth.of(2030, 1)
    private val analytics = Analytics(Fixtures.config(), PeriodRule())
    private fun d(day: Int) = LocalDate.of(2030, 1, day)

    @Test
    fun `the widget shows the overview's own figures - spent, left and left per day`() {
        val rows = listOf(Fixtures.income(200_000, d(1)), Fixtures.expense(50_000, d(3)), Fixtures.expense(9_000, d(9)))
        val w = WidgetFigures.of(analytics, rows, jan, today = d(10))
        val forecast = analytics.forecast(rows, jan, d(10))!!
        assertEquals(analytics.summary(rows, jan).expenseMinor, w.spentMinor)
        assertEquals(59_000, w.spentMinor)
        assertEquals(forecast.leftToSpendMinor, w.leftMinor)
        assertEquals(forecast.perDayMinor, w.perDayMinor)
        assertEquals(22, w.daysToGo) // the 10th to the 31st, today included
        assertNull(w.overMinor)
    }

    @Test
    fun `over is said as over, never as a negative left, and there is nothing per day to spend`() {
        val rows = listOf(Fixtures.income(10_000, d(1)), Fixtures.expense(25_000, d(3)))
        val w = WidgetFigures.of(analytics, rows, jan, today = d(10))
        assertEquals(0, w.leftMinor)
        assertEquals(15_000, w.overMinor)
        assertNull(w.perDayMinor)
    }

    @Test
    fun `without income only what was spent is shown`() {
        val w = WidgetFigures.of(analytics, listOf(Fixtures.expense(4_000, d(2))), jan, today = d(10))
        assertEquals(4_000, w.spentMinor)
        assertNull(w.leftMinor); assertNull(w.overMinor); assertNull(w.perDayMinor)
    }
}
