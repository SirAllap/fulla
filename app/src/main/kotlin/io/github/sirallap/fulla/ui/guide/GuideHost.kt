// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.guide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sirallap.fulla.core.guide.Guide
import io.github.sirallap.fulla.core.guide.GuideCursor
import io.github.sirallap.fulla.core.guide.GuideOrigin
import io.github.sirallap.fulla.core.guide.GuideStepState
import io.github.sirallap.fulla.core.guide.SetupStep
import io.github.sirallap.fulla.core.guide.TourStop
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.Tab
import io.github.sirallap.fulla.ui.biometricAvailable
import kotlinx.coroutines.launch

/**
 * The getting-started guide, for whichever household [view] is the active
 * one: setup steps first, then the tour. Not shown at all when the phone
 * isn't running the guide for this household (a different one is active, or
 * nobody started it) — switching households simply stops rendering this,
 * and switching back resumes at the stored step, no extra state needed.
 */
@Composable
fun GuideHost(view: HouseholdView, tab: Tab, setTab: (Tab) -> Unit) {
    val container = LocalContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return
    if (s.guideHousehold != view.id || s.guideStep == null) return

    val context = LocalContext.current
    val lockAvailable = remember { biometricAvailable(context) }
    val origin = GuideOrigin.entries.firstOrNull { it.name.equals(s.guideOrigin, ignoreCase = true) } ?: GuideOrigin.REPLAY
    val me = view.me
    val plan = remember(origin, me?.id, view.config.household, lockAvailable) { Guide.plan(origin, me, view.config.household, lockAvailable) }
    val scope = rememberCoroutineScope()

    if (plan.isEmpty) {
        LaunchedEffect(Unit) { container.settings.finishGuide() }
        return
    }

    val state = remember(s.guideStep, plan) { GuideCursor.decode(s.guideStep, plan) }

    // Only the sync stop depends on whether this household is shared and
    // reachable right now; the other six are always shown.
    val effectiveTour = remember(plan.tour, view.state.connected) {
        if (view.state.connected) plan.tour else plan.tour.filter { it != TourStop.SYNC_CLOUD }
    }

    fun store(next: GuideStepState) {
        scope.launch {
            if (next == GuideStepState.Done) container.settings.finishGuide()
            else container.settings.setGuideStep(GuideCursor.encode(next))
        }
    }

    fun firstTourState(): GuideStepState {
        var i = 0
        while (i < plan.tour.size && plan.tour[i] !in effectiveTour) i++
        return if (i < plan.tour.size) GuideStepState.Tour(i) else GuideStepState.Done
    }

    /** The next state after [from], skipping tour stops [effectiveTour] leaves out. */
    fun forward(from: GuideStepState): GuideStepState = when (from) {
        is GuideStepState.Setup -> {
            val i = plan.setup.indexOf(from.step)
            when {
                i + 1 < plan.setup.size -> GuideStepState.Setup(plan.setup[i + 1])
                plan.tour.isNotEmpty() -> firstTourState()
                else -> GuideStepState.Done
            }
        }
        is GuideStepState.Tour -> {
            var i = from.index + 1
            while (i < plan.tour.size && plan.tour[i] !in effectiveTour) i++
            if (i < plan.tour.size) GuideStepState.Tour(i) else GuideStepState.Done
        }
        GuideStepState.Done -> GuideStepState.Done
    }

    /** The state before a tour index, skipping hidden stops; before the first visible one, finishing (the spec's "Back at index 0"). */
    fun backward(index: Int): GuideStepState {
        var i = index - 1
        while (i >= 0 && plan.tour[i] !in effectiveTour) i--
        return if (i >= 0) GuideStepState.Tour(i) else GuideStepState.Done
    }

    // The guide opens on Overview, once, the moment it becomes visible for this household.
    LaunchedEffect(view.id) { setTab(Tab.OVERVIEW) }

    when (state) {
        is GuideStepState.Setup -> {
            val stepOf = plan.setup.indexOf(state.step) + 1 to plan.setup.size
            when (state.step) {
                SetupStep.MONTH_START -> MonthStartStep(view, stepOf, onNext = { store(forward(state)) }, onSkip = { store(forward(state)) })
                SetupStep.OPENING_BALANCES -> OpeningBalancesStep(view, stepOf, onNext = { store(forward(state)) }, onSkip = { store(forward(state)) })
                SetupStep.LOCK -> LockStep(stepOf, onNext = { store(forward(state)) }, onSkip = { store(forward(state)) })
            }
        }
        is GuideStepState.Tour -> {
            val stop = plan.tour.getOrNull(state.index)
            if (stop == null) {
                LaunchedEffect(state) { store(GuideStepState.Done) }
            } else if (stop !in effectiveTour) {
                // Not reachable right now (offline SYNC_CLOUD): move straight past it.
                LaunchedEffect(state) { store(forward(state)) }
            } else {
                val posOf = effectiveTour.indexOf(stop) + 1
                TourOverlay(
                    stop = stop,
                    stepOf = posOf to effectiveTour.size,
                    isLast = stop == effectiveTour.last(),
                    tab = tab,
                    setTab = setTab,
                    onNext = { store(forward(state)) },
                    onBack = { store(backward(state.index)) },
                    onSkip = { store(GuideStepState.Done) },
                )
            }
        }
        GuideStepState.Done -> Unit
    }
}
