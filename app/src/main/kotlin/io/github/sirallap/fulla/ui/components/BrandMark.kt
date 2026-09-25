// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import io.github.sirallap.fulla.core.design.BrandColors
import io.github.sirallap.fulla.ui.theme.FullaTheme

/**
 * Fulla's mark: a golden band, filled more than half. The same drawing as the
 * launcher icon, with the three things the welcome animates exposed.
 *
 * [band] is how much of the ring is drawn, 0..1, clockwise from the top.
 * [level] is how full it is, 0..1 of the inner height.
 * [slosh] tilts the surface, in fractions of the inner radius; 0 is at rest.
 */
@Composable
fun BrandMark(modifier: Modifier, band: Float = 1f, level: Float = 0.58f, slosh: Float = 0f) {
    val c = FullaTheme.colors
    val gold = Color(if (c.isDark) BrandColors.GOLD else BrandColors.GOLD_DEEP)
    val liquid = c.ink
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        val outer = size.minDimension / 2f - stroke / 2f
        val inner = outer - stroke * 0.95f
        val center = Offset(size.width / 2f, size.height / 2f)
        val circle = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(center, inner))
        }
        clipPath(circle) {
            // The surface: a gentle S across the width, tilted by the slosh.
            val top = center.y + inner - 2f * inner * level
            val tilt = slosh * inner
            val swell = inner * 0.08f
            val left = center.x - inner
            val right = center.x + inner
            val surface = Path().apply {
                moveTo(left, top + tilt)
                cubicTo(left + inner * 0.6f, top + tilt - swell, center.x - inner * 0.4f, top - swell, center.x, top)
                cubicTo(center.x + inner * 0.4f, top + swell, right - inner * 0.6f, top - tilt + swell, right, top - tilt)
                lineTo(right, center.y + inner)
                lineTo(left, center.y + inner)
                close()
            }
            drawPath(surface, liquid)
        }
        if (band > 0f) {
            drawArc(gold, startAngle = -90f, sweepAngle = 360f * band.coerceAtMost(1f), useCenter = false,
                topLeft = Offset(center.x - outer, center.y - outer), size = Size(outer * 2, outer * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}
