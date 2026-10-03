// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.guide.Guide
import io.github.sirallap.fulla.core.guide.GuideCursor
import io.github.sirallap.fulla.core.guide.GuideOrigin
import io.github.sirallap.fulla.core.guide.GuidePlan
import io.github.sirallap.fulla.core.guide.GuideStepState
import io.github.sirallap.fulla.core.guide.SetupStep
import io.github.sirallap.fulla.core.guide.TourStop
import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.MemberStatus
import io.github.sirallap.fulla.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals

class GuideTest {
    private val household = Fixtures.config().household
    private val fullTour = TourStop.entries.toList()
    private val fullSetupWithLock = listOf(SetupStep.MONTH_START, SetupStep.OPENING_BALANCES, SetupStep.LOCK)
    private val fullSetupNoLock = listOf(SetupStep.MONTH_START, SetupStep.OPENING_BALANCES)

    private fun member(role: Role, active: Boolean = true) =
        Member("m", "Alice", "A", role = role, status = if (active) MemberStatus.ACTIVE else MemberStatus.ARCHIVED, hasAccount = true)

    @Test
    fun an_inactive_member_gets_nothing_whatever_the_origin() {
        val inactive = member(Role.OWNER, active = false)
        for (origin in GuideOrigin.entries) {
            for (lock in listOf(true, false)) {
                assertEquals(GuidePlan.NONE, Guide.plan(origin, inactive, household, lock), "$origin lock=$lock")
            }
        }
        assertEquals(GuidePlan.NONE, Guide.plan(GuideOrigin.CREATED, null, household, true))
    }

    @Test
    fun created_by_an_owner_or_admin_sets_up_the_household_and_takes_the_full_tour() {
        for (role in listOf(Role.OWNER, Role.ADMIN)) {
            val plan = Guide.plan(GuideOrigin.CREATED, member(role), household, lockAvailable = true)
            assertEquals(GuidePlan(fullSetupWithLock, fullTour), plan, role.name)
        }
    }

    @Test
    fun created_without_lock_available_skips_the_lock_step_but_nothing_else() {
        val plan = Guide.plan(GuideOrigin.CREATED, member(Role.OWNER), household, lockAvailable = false)
        assertEquals(GuidePlan(fullSetupNoLock, fullTour), plan)
    }

    @Test
    fun created_by_a_plain_member_defensive_has_no_setup_only_the_tour() {
        val plan = Guide.plan(GuideOrigin.CREATED, member(Role.MEMBER), household, lockAvailable = true)
        assertEquals(GuidePlan(emptyList(), fullTour), plan)
    }

    @Test
    fun joined_never_sets_up_the_household_but_everyone_still_gets_the_tour() {
        for (role in listOf(Role.OWNER, Role.ADMIN, Role.MEMBER)) {
            val plan = Guide.plan(GuideOrigin.JOINED, member(role), household, lockAvailable = true)
            assertEquals(GuidePlan(listOf(SetupStep.LOCK), fullTour), plan, role.name)
        }
    }

    @Test
    fun joined_member_gets_tour_only_when_the_lock_is_not_available() {
        val plan = Guide.plan(GuideOrigin.JOINED, member(Role.MEMBER), household, lockAvailable = false)
        assertEquals(GuidePlan(emptyList(), fullTour), plan)
    }

    @Test
    fun demo_never_sets_up_the_household_whoever_is_looking() {
        for (role in listOf(Role.OWNER, Role.ADMIN, Role.MEMBER)) {
            val plan = Guide.plan(GuideOrigin.DEMO, member(role), household, lockAvailable = true)
            assertEquals(GuidePlan(emptyList(), fullTour), plan, role.name)
        }
    }

    @Test
    fun replay_by_an_admin_re_runs_setup_a_plain_member_only_gets_the_lock_step() {
        val admin = Guide.plan(GuideOrigin.REPLAY, member(Role.ADMIN), household, lockAvailable = true)
        assertEquals(GuidePlan(fullSetupWithLock, fullTour), admin)

        val member = Guide.plan(GuideOrigin.REPLAY, member(Role.MEMBER), household, lockAvailable = true)
        assertEquals(GuidePlan(listOf(SetupStep.LOCK), fullTour), member)

        val memberNoLock = Guide.plan(GuideOrigin.REPLAY, member(Role.MEMBER), household, lockAvailable = false)
        assertEquals(GuidePlan(emptyList(), fullTour), memberNoLock)
    }

    @Test
    fun cursor_round_trips_every_step() {
        val plan = GuidePlan(fullSetupWithLock, fullTour)
        for (step in fullSetupWithLock) {
            val state = GuideStepState.Setup(step)
            assertEquals(state, GuideCursor.decode(GuideCursor.encode(state), plan))
        }
        for (i in fullTour.indices) {
            val state = GuideStepState.Tour(i)
            assertEquals(state, GuideCursor.decode(GuideCursor.encode(state), plan))
        }
        assertEquals(GuideStepState.Done, GuideCursor.decode(GuideCursor.encode(GuideStepState.Done), plan))
    }

    @Test
    fun an_unknown_or_garbage_cursor_falls_back_to_the_first_remaining_step() {
        val withSetup = GuidePlan(listOf(SetupStep.OPENING_BALANCES), listOf(TourStop.JAR, TourStop.GEAR))
        assertEquals(GuideStepState.Setup(SetupStep.OPENING_BALANCES), GuideCursor.decode("nonsense", withSetup))
        assertEquals(GuideStepState.Setup(SetupStep.OPENING_BALANCES), GuideCursor.decode(null, withSetup))
        assertEquals(GuideStepState.Setup(SetupStep.OPENING_BALANCES), GuideCursor.decode("", withSetup))
        assertEquals(GuideStepState.Setup(SetupStep.OPENING_BALANCES), GuideCursor.decode("tour:99", withSetup))

        val tourOnly = GuidePlan(emptyList(), listOf(TourStop.JAR, TourStop.GEAR))
        assertEquals(GuideStepState.Tour(0), GuideCursor.decode("garbage", tourOnly))
        assertEquals(GuideStepState.Done, GuideCursor.decode("garbage", GuidePlan.NONE))
    }

    @Test
    fun decoding_GuideCursor_START_always_lands_on_the_plan_s_first_remaining_step() {
        val withSetup = GuidePlan(listOf(SetupStep.OPENING_BALANCES), listOf(TourStop.JAR, TourStop.GEAR))
        assertEquals(GuideStepState.Setup(SetupStep.OPENING_BALANCES), GuideCursor.decode(GuideCursor.START, withSetup))

        val tourOnly = GuidePlan(emptyList(), listOf(TourStop.JAR, TourStop.GEAR))
        assertEquals(GuideStepState.Tour(0), GuideCursor.decode(GuideCursor.START, tourOnly))

        assertEquals(GuideStepState.Done, GuideCursor.decode(GuideCursor.START, GuidePlan.NONE))
    }

    @Test
    fun a_step_no_longer_present_in_an_updated_plan_falls_back_too() {
        // A phone stored "setup:LOCK" before an app update dropped LOCK from this household's plan.
        val plan = GuidePlan(listOf(SetupStep.MONTH_START), listOf(TourStop.JAR))
        assertEquals(GuideStepState.Setup(SetupStep.MONTH_START), GuideCursor.decode("setup:LOCK", plan))
    }
}
