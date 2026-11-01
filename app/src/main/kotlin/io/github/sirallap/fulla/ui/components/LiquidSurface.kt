// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.ui.theme.FullaMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val TAU = (2 * PI).toFloat()

/**
 * A liquid held in whatever it is drawn inside (clip it to the shape of its
 * container). [level] is the fraction of the height it fills, 0 to 1 (a
 * little over 1 is allowed, to overshoot).
 *
 * It behaves like water, not like a bar:
 *  - the level follows a spring that overshoots a little and settles;
 *  - the waves on the surface are as tall as the change was sudden, and die
 *    away in under two seconds;
 *  - the whole surface tilts from side to side, then settles, as in a glass
 *    that was shaken;
 *  - there is a soft highlight under the surface and a bright rim along it.
 *
 * At rest the surface is flat and nothing is redrawn: it only moves because
 * [level] changed (design.md: nothing moves on its own). With the system's
 * "remove animations" on, the level simply jumps.
 */
@Composable
fun LiquidSurface(
    level: Float,
    color: Color,
    modifier: Modifier = Modifier,
    maxAmplitude: Dp = 5.dp,
) {
    val reduced = FullaMotion.reduced()
    val shown = remember { Animatable(level) }
    val energy = remember { Animatable(0f) }
    val phase = remember { Animatable(0f) }
    val tilt = remember { Animatable(0f) }
    LaunchedEffect(level, reduced) {
        if (reduced) { shown.snapTo(level); return@LaunchedEffect }
        val change = level - shown.value
        if (abs(change) < 0.001f) return@LaunchedEffect
        coroutineScope {
            launch { shown.animateTo(level, spring(dampingRatio = 0.55f, stiffness = 130f)) }
            launch {
                energy.snapTo(min(1f, abs(change) * 2.2f + 0.35f))
                energy.animateTo(0f, tween(1700, easing = LinearOutSlowInEasing))
            }
            launch { phase.animateTo(phase.value + 14f, tween(1700, easing = LinearEasing)) }
            launch {
                tilt.snapTo(0f)
                tilt.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 45f), initialVelocity = change * 6f)
            }
        }
    }
    Canvas(modifier) {
        val lvl = shown.value
        if (lvl <= 0.004f) return@Canvas
        val w = size.width
        val h = size.height
        val amp = maxAmplitude.toPx() * energy.value
        val ph = phase.value
        val sl = tilt.value
        val wavelength = max(70.dp.toPx(), w * 0.6f)
        val steps = max(12, (w / 4.dp.toPx()).toInt())
        val foot = h + 6.dp.toPx()

        fun surfaceY(x: Float, a: Float, p: Float, offset: Float): Float =
            h * (1f - lvl) + offset +
                a * sin(x / wavelength * TAU + p) +
                a * 0.4f * sin(x / (wavelength * 0.55f) * TAU - p * 1.3f) +
                sl * h * 0.14f * ((x / w) - 0.5f) * 2f

        fun layer(a: Float, p: Float, offset: Float, closed: Boolean): Path {
            val path = Path()
            if (closed) {
                path.moveTo(0f, foot)
                path.lineTo(0f, surfaceY(0f, a, p, offset))
            } else {
                path.moveTo(0f, surfaceY(0f, a, p, offset))
            }
            for (i in 1..steps) {
                val x = w * i / steps
                path.lineTo(x, surfaceY(x, a, p, offset))
            }
            if (closed) { path.lineTo(w, foot); path.close() }
            return path
        }

        val front = layer(amp, ph, 0f, closed = true)
        drawPath(layer(amp * 0.8f, ph + 1.9f, 2.5.dp.toPx(), closed = true), color.copy(alpha = color.alpha * 0.55f))
        drawPath(front, color)
        val top = h * (1f - lvl)
        drawPath(front, Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.34f), Color.Transparent), startY = top, endY = top + h * 0.5f))
        drawPath(layer(amp, ph, 0f, closed = false), Color.White.copy(alpha = 0.55f),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * A squash and stretch whenever [trigger] changes (not on the first
 * composition): the thing wobbles like a drop that was nudged, then settles.
 */
@Composable
fun Modifier.jelly(trigger: Any?): Modifier {
    val reduced = FullaMotion.reduced()
    val p = remember { Animatable(0f) }
    val seen = remember { booleanArrayOf(false) }
    LaunchedEffect(trigger, reduced) {
        if (!seen[0]) { seen[0] = true; return@LaunchedEffect }
        if (reduced) return@LaunchedEffect
        p.snapTo(1f)
        p.animateTo(0f, spring(dampingRatio = 0.28f, stiffness = 260f))
    }
    return graphicsLayer {
        val v = p.value
        scaleX = 1f + 0.08f * v
        scaleY = 1f - 0.11f * v
    }
}

/**
 * The neck of liquid between a pill and a drop leaving it (or entering it):
 * thick while they are close, thinner as they part, gone when they are
 * further than [reach] apart. [pillEnd] is the pill's right edge, [dropCenter]
 * the drop's centre, both in px along the same line [centerY].
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNeck(
    color: Color, pillEnd: Float, dropCenter: Float, dropRadius: Float, centerY: Float, reach: Float,
) {
    val gap = (dropCenter - dropRadius) - pillEnd
    if (gap <= 0f || gap >= reach) return
    val t = dropRadius * (1f - gap / reach) * 0.85f
    val left = pillEnd
    val right = dropCenter - dropRadius
    val mid = (left + right) / 2f
    val path = Path()
    path.moveTo(left, centerY - dropRadius * 0.9f)
    path.cubicTo(mid, centerY - t, mid, centerY - t, right, centerY - dropRadius * 0.9f)
    path.lineTo(right, centerY + dropRadius * 0.9f)
    path.cubicTo(mid, centerY + t, mid, centerY + t, left, centerY + dropRadius * 0.9f)
    path.close()
    drawPath(path, color)
    drawCircle(color, dropRadius, Offset(dropCenter, centerY))
}
