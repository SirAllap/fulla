// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.core.design.Liquid
import io.github.sirallap.fulla.ui.theme.FullaMotion
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlin.math.roundToInt

/** One vial: what it is, how full (0..1 of the fullest), its share of the whole, and what a screen reader says. */
data class Vial(val label: String, val icon: ImageVector?, val level: Float, val share: Float, val description: String)

/**
 * Where the spending went, as a row of vials filled with the jar's spending
 * liquid: one hue for one measure, so a vial's name sits under it and its
 * share above it, never a colour to decode. The fullest vial is nearly full;
 * the others are to scale with it. They fill when first shown, the way the
 * jar does, unless motion is reduced.
 */
@Composable
fun SpendingVials(vials: List<Vial>, modifier: Modifier = Modifier) {
    val c = FullaTheme.colors
    val reduced = FullaMotion.reduced()
    val fill = remember(vials) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(vials) { fill.animateTo(1f, FullaMotion.liquid(reduced)) }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom) {
        vials.forEachIndexed { index, v ->
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = v.description },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(v.share * 100).roundToInt()} %", style = FullaType.label, color = c.ink, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Canvas(Modifier.fillMaxWidth().height(132.dp)) {
                    val w = minOf(size.width * 0.72f, 44.dp.toPx())
                    val left = (size.width - w) / 2
                    val top = 0f
                    val bottom = size.height
                    val inside = bottom - top
                    val outline = Path().apply { addRoundRect(RoundRect(left, top, left + w, bottom, CornerRadius(w / 2, w / 2))) }
                    clipPath(outline) {
                        drawRect(c.paperHigh, Offset(left, top), Size(w, inside))
                        val level = bottom - inside * (0.04f + 0.9f * v.level.coerceIn(0f, 1f)) * fill.value
                        val samples = 24
                        // Each vial's surface is out of step with its neighbours', like liquids poured separately.
                        val wave = Liquid.surface(samples, 3.dp.toPx() / inside, index * 1.7f + 0.4f, 1.1f, meniscus = 4.dp.toPx() / inside)
                        val liquid = Path().apply {
                            moveTo(left, bottom)
                            for (i in 0 until samples) lineTo(left + w * i / (samples - 1), level + wave[i] * inside)
                            lineTo(left + w, bottom)
                            close()
                        }
                        drawPath(liquid, Brush.verticalGradient(listOf(c.outSurface, c.outBody), startY = minOf(level, bottom - 1f), endY = bottom))
                    }
                    drawPath(outline, c.inkMuted.copy(alpha = 0.35f), style = Stroke(1.5.dp.toPx()))
                }
                Spacer(Modifier.height(6.dp))
                v.icon?.let { Icon(it, null, tint = c.inkMuted, modifier = Modifier.size(18.dp)) }
                Text(v.label, style = FullaType.label, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center)
            }
        }
    }
}
