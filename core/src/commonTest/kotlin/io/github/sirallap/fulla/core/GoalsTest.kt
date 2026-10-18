// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.goals.Goal
import io.github.sirallap.fulla.core.goals.Goals
import io.github.sirallap.fulla.core.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalsTest {
    private val today = LocalDate.of(2030, 1, 18)
    private val summer = Goal("00000000-0000-4000-8000-000000000a01", "Summer", 160_000, Fixtures.MAIN, LocalDate.of(2030, 6, 30))

    @Test
    fun what_is_saved_is_the_account_s_balance_and_what_is_missing_is_spread_over_the_months_left_this_one_included() {
        val p = Goals.progress(summer, 70_000, today)
        assertEquals(70_000, p.savedMinor)
        assertEquals(90_000, p.leftMinor)
        assertEquals(6, p.monthsLeft, "January to June")
        assertEquals(15_000, p.perMonthMinor)
        assertFalse(p.late)
        assertEquals(0.4375, p.fraction, 1e-9)
    }

    @Test
    fun putting_aside_the_monthly_figure_always_gets_there_rounding_up() {
        val p = Goals.progress(summer, 0, today)
        assertTrue(p.perMonthMinor!! * p.monthsLeft!! >= p.leftMinor)
        assertEquals(26_667, p.perMonthMinor)
    }

    @Test
    fun a_goal_reached_without_a_date_or_past_its_date_says_so_and_asks_for_nothing_a_month() {
        val reached = Goals.progress(summer, 200_000, today)
        assertTrue(reached.reached)
        assertEquals(0, reached.leftMinor)
        assertEquals(1.0, reached.fraction)
        assertNull(reached.perMonthMinor)

        val undated = Goals.progress(summer.copy(targetDate = null), 10_000, today)
        assertNull(undated.perMonthMinor)
        assertNull(undated.monthsLeft)

        val late = Goals.progress(summer.copy(targetDate = LocalDate.of(2029, 12, 31)), 10_000, today)
        assertTrue(late.late)
        assertNull(late.perMonthMinor)
    }

    @Test
    fun an_overdrawn_account_has_saved_nothing_never_a_negative_amount() {
        val p = Goals.progress(summer, -5_000, today)
        assertEquals(0, p.savedMinor)
        assertEquals(160_000, p.leftMinor)
        assertEquals(0.0, p.fraction)
    }

    @Test
    fun a_date_this_month_leaves_one_month() {
        val p = Goals.progress(summer.copy(targetDate = LocalDate.of(2030, 1, 31)), 150_000, today)
        assertEquals(1, p.monthsLeft)
        assertEquals(10_000, p.perMonthMinor)
    }
}
