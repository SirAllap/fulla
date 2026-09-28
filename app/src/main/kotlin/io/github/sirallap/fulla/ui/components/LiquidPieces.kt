// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.core.design.Liquid
import io.github.sirallap.fulla.ui.theme.FullaMotion
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType

/** Which of the jar's two liquids a piece is filled with. */
enum class LiquidTone { OUT, IN }

@Composable
private fun toneColors(tone: LiquidTone): Pair<Color, Color> {
    val c = FullaTheme.colors
    return if (tone == LiquidTone.OUT) c.outSurface to c.outBody else c.inSurface to c.inBody
}

/** 0 to 1 once, when first shown, the way the jar fills; at once when motion is reduced. */
@Composable
private fun rememberFill(key: Any?): Animatable<Float, *> {
    val reduced = FullaMotion.reduced()
    val fill = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key) { fill.animateTo(1f, FullaMotion.liquid(reduced)) }
    return fill
}

/**
 * A pane of the jar's liquid behind other content, translucent so text on it
 * keeps its contrast. [level] 0..1 of the height when [vertical], of the
 * width otherwise; its edge waves like the jar's surface.
 */
private fun DrawScope.liquidSheet(level: Float, surface: Color, body: Color, vertical: Boolean, phase: Float, alpha: Float) {
    if (level <= 0f) return
    val samples = 32
    val w = size.width
    val h = size.height
    val path = Path()
    if (vertical) {
        val top = h - h * level.coerceIn(0f, 1f)
        val wave = Liquid.surface(samples, 4.dp.toPx() / h, phase, 1.2f)
        path.moveTo(0f, h)
        for (i in 0 until samples) path.lineTo(w * i / (samples - 1), top + wave[i] * h)
        path.lineTo(w, h)
        path.close()
        drawPath(path, Brush.verticalGradient(listOf(surface.copy(alpha = alpha), body.copy(alpha = alpha)), startY = top, endY = h))
    } else {
        val right = w * level.coerceIn(0f, 1f)
        val wave = Liquid.surface(samples, 5.dp.toPx() / w, phase, 0.8f)
        path.moveTo(0f, 0f)
        for (i in 0 until samples) path.lineTo(right + wave[i] * w, h * i / (samples - 1))
        path.lineTo(0f, h)
        path.close()
        drawPath(path, Brush.horizontalGradient(listOf(body.copy(alpha = alpha), surface.copy(alpha = alpha)), startX = 0f, endX = maxOf(right, 1f)))
    }
}

/**
 * A figure on a glass tile that fills with the jar's liquid to [level]: how
 * much of the income is gone, how far through the period, how many days had
 * no spending. Null [level] leaves the glass empty, for a figure with nothing
 * to measure against.
 */
@Composable
fun LiquidTile(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    context: String? = null,
    level: Float? = null,
    tone: LiquidTone = LiquidTone.OUT,
    valueColor: Color = Color.Unspecified,
    phase: Float = 0f,
) {
    val c = FullaTheme.colors
    val (surface, body) = toneColors(tone)
    val fill = rememberFill(level)
    Box(
        modifier.heightIn(min = 104.dp).clip(RoundedCornerShape(20.dp)).drawBehind {
            drawRect(c.paperHigh)
            if (level != null) liquidSheet(level * fill.value, surface, body, vertical = true, phase = phase, alpha = if (c.isDark) 0.42f else 0.30f)
        }.clearAndSetSemantics { contentDescription = listOfNotNull(title, value, context).joinToString(", ") }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = FullaType.label, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = FullaType.title, color = if (valueColor == Color.Unspecified) c.ink else valueColor, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (context != null) Text(context, style = FullaType.label, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Two tiles side by side, as tall as the taller. */
@Composable
fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top, content = content)
}

/**
 * A row with a sheet of the jar's liquid behind it, [fraction] of its width:
 * the largest expenses, what is bought again and again, each to scale with
 * the first.
 */
@Composable
fun LiquidBarRow(
    title: String,
    amount: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    context: String? = null,
    phase: Float = 0f,
    onClick: (() -> Unit)? = null,
) {
    val c = FullaTheme.colors
    val fill = rememberFill(fraction)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .drawBehind {
                drawRect(c.paperHigh)
                liquidSheet(fraction * fill.value, c.outSurface, c.outBody, vertical = false, phase = phase, alpha = if (c.isDark) 0.40f else 0.26f)
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = FullaType.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (context != null) Text(context, style = FullaType.label, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(amount, style = FullaType.amount, color = c.ink, maxLines = 1)
    }
}
