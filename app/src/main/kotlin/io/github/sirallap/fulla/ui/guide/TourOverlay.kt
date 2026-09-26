// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.guide.TourStop
import io.github.sirallap.fulla.ui.Tab
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.components.SecondaryButton
import io.github.sirallap.fulla.ui.theme.FullaMotion
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType

/** The tab that must be showing for [stop]'s element to exist at all, or null when it is reachable from any tab. */
private fun tabFor(stop: TourStop): Tab? = when (stop) {
    TourStop.JAR, TourStop.OVERVIEW_TAB -> Tab.OVERVIEW
    TourStop.ADD_AND_KEYPAD -> Tab.ADD
    TourStop.HISTORY_TAB -> Tab.HISTORY
    TourStop.BALANCES_TAB -> Tab.BALANCES
    TourStop.SYNC_CLOUD, TourStop.GEAR -> null
}

private fun copyFor(stop: TourStop): Int = when (stop) {
    TourStop.JAR -> R.string.tour_jar
    TourStop.OVERVIEW_TAB -> R.string.tour_overview
    TourStop.ADD_AND_KEYPAD -> R.string.tour_add
    TourStop.HISTORY_TAB -> R.string.tour_history
    TourStop.BALANCES_TAB -> R.string.tour_balances
    TourStop.SYNC_CLOUD -> R.string.tour_sync
    TourStop.GEAR -> R.string.tour_gear
}

/**
 * The coach marks: a dimmed screen with a hole over the current stop's
 * element, a card naming it, and Back/Next (or Done, on the last stop).
 * Switches to whatever tab the stop needs, then waits (up to half a second)
 * for [LocalGuideTargets] to report the element's position; if it never
 * does, the card is shown centred with no hole rather than waiting forever.
 */
@Composable
fun TourOverlay(
    stop: TourStop,
    stepOf: Pair<Int, Int>,
    isLast: Boolean,
    tab: Tab,
    setTab: (Tab) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val c = FullaTheme.colors
    val reduced = FullaMotion.reduced()
    val targets = LocalGuideTargets.current
    val needed = tabFor(stop)

    BackHandler { onBack() }

    // Keyed on (stop, tab), not just (stop): GuideHost moves the app to
    // Overview once, on its own effect, the moment the guide becomes visible
    // for a household — which can run *after* this one if the guide resumes
    // straight into a stop that needs a different tab (app killed or
    // household switched mid-tour). Keying on tab too makes this effect
    // re-fire once GuideHost's later change lands, instead of never
    // switching to the tab this stop actually needs.
    LaunchedEffect(stop, tab) { needed?.let { if (tab != it) setTab(it) } }

    // targets is a SnapshotStateMap: reading it here directly (not copying it
    // into a separate mutableStateOf the first time it's found, then never
    // looking again) means this recomposes on every later write too, so a
    // target that keeps moving while a tab's slide-in animation runs is
    // tracked for the whole stop, not just wherever it happened to be on the
    // first successful read. Nothing here waits: if the target never
    // reports a position, rect simply stays null and hasHole (below) stays
    // false, which already shows the card centred with no hole rather than
    // waiting forever — the same outcome the old 500ms timeout produced,
    // without a separate timer to keep in sync with it.
    val rect = targets?.get(stop)

    var windowOffset by remember { mutableStateOf(Offset.Zero) }
    val left by animateFloatAsState((rect?.left ?: 0f) - windowOffset.x, FullaMotion.settle(reduced), label = "holeL")
    val top by animateFloatAsState((rect?.top ?: 0f) - windowOffset.y, FullaMotion.settle(reduced), label = "holeT")
    val right by animateFloatAsState((rect?.right ?: 0f) - windowOffset.x, FullaMotion.settle(reduced), label = "holeR")
    val bottom by animateFloatAsState((rect?.bottom ?: 0f) - windowOffset.y, FullaMotion.settle(reduced), label = "holeB")

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(stop) { focusRequester.requestFocus() }

    val label = stringResource(copyFor(stop))
    val hasHole = rect != null

    Box(
        Modifier.fillMaxSize().onGloballyPositioned { windowOffset = it.positionInWindow() }
            .pointerInput(hasHole, left, top, right, bottom) {
                detectTapGestures { offset ->
                    val inside = hasHole && offset.x in left..right && offset.y in top..bottom
                    if (inside) onNext()
                    // Outside the hole: the tap is consumed and does nothing else.
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(c.ink.copy(alpha = 0.6f))
            if (hasHole) {
                val hole = Rect(left, top, right, bottom).inflate(8.dp.toPx())
                drawRoundRect(
                    color = androidx.compose.ui.graphics.Color.Black,
                    topLeft = hole.topLeft, size = hole.size,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    blendMode = BlendMode.Clear,
                )
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            val screenH = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }
            val below = !hasHole || (screenH - bottom) >= top
            Box(Modifier.fillMaxSize(), contentAlignment = if (!hasHole) Alignment.Center else if (below) Alignment.BottomCenter else Alignment.TopCenter) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.paper)
                        .focusRequester(focusRequester).focusable()
                        .semantics { paneTitle = label; liveRegion = LiveRegionMode.Polite }
                        .padding(20.dp),
                ) {
                    Text(stringResource(R.string.guide_step_of, stepOf.first, stepOf.second), style = FullaType.secondary, color = c.inkMuted)
                    Text(label, style = FullaType.body, color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(R.string.back), onBack, modifier = Modifier.weight(1f))
                        PrimaryButton(
                            stringResource(if (isLast) R.string.guide_done else R.string.guide_next),
                            onNext,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    androidx.compose.material3.TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) {
                        Text(stringResource(R.string.guide_skip))
                    }
                }
            }
        }
    }
}
