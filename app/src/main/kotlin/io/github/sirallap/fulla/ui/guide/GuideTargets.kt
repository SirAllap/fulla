// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.guide

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.sirallap.fulla.core.guide.TourStop

/** Where each tour stop's element currently is on screen, in window coordinates. */
typealias GuideTargets = androidx.compose.runtime.snapshots.SnapshotStateMap<TourStop, Rect>

/** Null (the default) when nothing is tracking targets: [guideTarget] is then a no-op, safe in screenshot tests. */
val LocalGuideTargets = compositionLocalOf<GuideTargets?> { null }

/** A fresh, empty target map, for whoever hosts the tour to provide with [LocalGuideTargets]. */
@androidx.compose.runtime.Composable
fun rememberGuideTargets(): GuideTargets = remember { mutableStateMapOf<TourStop, Rect>() }

/**
 * Marks an element as the thing the tour points at for [stop]: while
 * [LocalGuideTargets] is provided, its window bounds are kept in the map for
 * as long as this composable stays on screen, and removed the moment it
 * leaves (a tab that scrolls off, a sheet that closes).
 */
fun Modifier.guideTarget(stop: TourStop): Modifier = composed {
    val targets = LocalGuideTargets.current
    if (targets == null) {
        this
    } else {
        DisposableEffect(stop) { onDispose { targets.remove(stop) } }
        this.onGloballyPositioned { coordinates -> targets[stop] = coordinates.boundsInWindow() }
    }
}
