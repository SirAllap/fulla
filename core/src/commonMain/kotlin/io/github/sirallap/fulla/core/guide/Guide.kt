// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.guide

import io.github.sirallap.fulla.core.model.Household
import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.roles.Permissions

/** Why the guide is running, which decides what it shows. */
enum class GuideOrigin {
    /** Just created this household. */
    CREATED,

    /** Joined a household somebody else made. */
    JOINED,

    /** Looking at the built-in demo data. */
    DEMO,

    /** Asked to see the guide again from the gear. */
    REPLAY,
}

/** A one-off setting the guide can walk a person through. */
enum class SetupStep {
    MONTH_START, OPENING_BALANCES, LOCK
}

/**
 * A stop on the tour, in the fixed order the guide shows them.
 *
 * The spec's "Add tab, then the keypad" is modelled as one stop
 * ([ADD_AND_KEYPAD]) rather than two: the keypad only exists once the Add tab
 * is open, so it is one focus with two things to point at, not two places to
 * visit. That keeps the tour at 7 stops, matching the spec's count.
 */
enum class TourStop {
    JAR, OVERVIEW_TAB, ADD_AND_KEYPAD, HISTORY_TAB, BALANCES_TAB, SYNC_CLOUD, GEAR
}

/** What the guide will show: setup steps first, then the tour. Either may be empty. */
data class GuidePlan(val setup: List<SetupStep>, val tour: List<TourStop>) {
    val isEmpty: Boolean get() = setup.isEmpty() && tour.isEmpty()

    companion object {
        val NONE = GuidePlan(emptyList(), emptyList())
    }
}

/**
 * Decides what the guide shows, from why it is running, who is looking, and
 * the household they are looking at. Nothing here is Android: the app maps
 * [SetupStep] and [TourStop] to screens and highlights.
 */
object Guide {
    private val FULL_TOUR = TourStop.entries.toList()

    fun plan(origin: GuideOrigin, me: Member?, household: Household, lockAvailable: Boolean): GuidePlan {
        if (me == null || !me.isActive) return GuidePlan.NONE
        val isAdmin = Permissions.canEditHouseholdSettings(me)
        val lockStep = if (lockAvailable) listOf(SetupStep.LOCK) else emptyList()
        val fullSetup = listOf(SetupStep.MONTH_START, SetupStep.OPENING_BALANCES) + lockStep

        return when (origin) {
            GuideOrigin.CREATED -> GuidePlan(if (isAdmin) fullSetup else emptyList(), FULL_TOUR)
            GuideOrigin.JOINED -> GuidePlan(lockStep, FULL_TOUR)
            GuideOrigin.DEMO -> GuidePlan(emptyList(), FULL_TOUR)
            GuideOrigin.REPLAY -> if (isAdmin) GuidePlan(fullSetup, FULL_TOUR) else GuidePlan(lockStep, FULL_TOUR)
        }
    }
}

/** Where a person is in the guide: mid-setup, mid-tour, or finished. */
sealed class GuideStepState {
    data class Setup(val step: SetupStep) : GuideStepState()
    data class Tour(val index: Int) : GuideStepState()
    data object Done : GuideStepState()
}

/**
 * Turns a [GuideStepState] into the string a phone stores (the `guide_step`
 * key) and back. Decoding never throws: garbage, an unknown step, or a step
 * the current [GuidePlan] no longer has (an app update dropped it) all fall
 * back to the plan's first remaining step, so a stored cursor from an older
 * version of the guide can never strand a phone mid-guide.
 */
object GuideCursor {
    /**
     * What a phone should write to start (or restart) the guide, instead of
     * leaving `guide_step` null: any string with no recognised `kind:` prefix
     * decodes to the plan's first remaining step anyway, so this is just a
     * named, self-documenting way to spell that, not new decoding logic.
     */
    const val START = "start"

    fun encode(step: GuideStepState): String = when (step) {
        is GuideStepState.Setup -> "setup:${step.step.name}"
        is GuideStepState.Tour -> "tour:${step.index}"
        GuideStepState.Done -> "done"
    }

    fun decode(s: String?, plan: GuidePlan): GuideStepState {
        val fallback = firstStep(plan)
        if (s == null) return fallback
        if (s == "done") return GuideStepState.Done
        val (kind, rest) = s.split(":", limit = 2).let { if (it.size == 2) it[0] to it[1] else return fallback }
        return when (kind) {
            "setup" -> SetupStep.entries.firstOrNull { it.name == rest }
                ?.takeIf { it in plan.setup }
                ?.let { GuideStepState.Setup(it) } ?: fallback
            "tour" -> rest.toIntOrNull()
                ?.takeIf { it in plan.tour.indices }
                ?.let { GuideStepState.Tour(it) } ?: fallback
            else -> fallback
        }
    }

    private fun firstStep(plan: GuidePlan): GuideStepState =
        plan.setup.firstOrNull()?.let { GuideStepState.Setup(it) }
            ?: if (plan.tour.isNotEmpty()) GuideStepState.Tour(0) else GuideStepState.Done
}
