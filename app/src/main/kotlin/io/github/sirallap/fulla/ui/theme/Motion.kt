// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * How Fulla moves. The liquids settle over 420 ms and the figure counts up over
 * 300 ms; everything that responds to a touch moves on a spring, with a tiny
 * overshoot at most, never a bounce. Lists have no entrance animation and
 * nothing moves on its own except the welcome, once. With the system's
 * "remove animations" on, everything snaps.
 */
object FullaMotion {
    private val Smooth = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val LIQUID_MS = 420
    const val FIGURE_MS = 300
    const val FUNCTIONAL_MS = 120

    fun <T> liquid(reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else tween(LIQUID_MS, easing = Smooth)
    fun <T> figure(reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else tween(FIGURE_MS, easing = Smooth)
    fun <T> functional(reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else tween(FUNCTIONAL_MS)

    /** Things that follow a finger or a choice: quick, settled, no visible overshoot. */
    fun <T> snappy(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /** Shapes that change size or place: a hint of overshoot, like a liquid settling. */
    fun <T> settle(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow)

    /** The trailing edge of something that stretches: the same spring, a little lazier. */
    fun <T> trail(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow * 2.2f)

    @Composable
    @ReadOnlyComposable
    fun reduced(): Boolean {
        val resolver = LocalContext.current.contentResolver
        val scale = runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        return scale == 0f
    }
}
