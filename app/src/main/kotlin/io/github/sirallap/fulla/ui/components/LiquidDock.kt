// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.ui.theme.FullaMotion
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlin.math.roundToInt

/**
 * A form's Save, in the bar's own language (see the round button of the tab
 * bar): while there is nothing to save it is a quiet full-width pill; once
 * there is, the pill fills with liquid, shortens, and lets out a little red
 * drop that closes the form without saving, which goes back in when the form
 * is empty again. [busy] falls back to the plain button with its spinner.
 */
@Composable
fun SaveDock(label: String, onSave: () -> Unit, enabled: Boolean, busy: Boolean, onDiscard: () -> Unit, modifier: Modifier = Modifier) {
    if (busy) { PrimaryButton(label, onSave, modifier = modifier, busy = true); return }
    val c = FullaTheme.colors
    val reduced = FullaMotion.reduced()
    val density = LocalDensity.current
    val discardText = stringResource(R.string.discard_entry)
    BoxWithConstraints(modifier.fillMaxWidth().height(56.dp)) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val drop = with(density) { 48.dp.toPx() }
        val gap = with(density) { 8.dp.toPx() }
        val right by animateFloatAsState(if (enabled) w - drop - gap else w,
            if (reduced) snap() else spring(dampingRatio = 0.55f, stiffness = 200f), label = "dockRight")
        val dropP by animateFloatAsState(if (enabled) 1f else 0f,
            if (reduced) snap() else spring(dampingRatio = 0.7f, stiffness = 120f), label = "dockDrop")
        val dropCx = (w - drop * 1.5f) + (drop) * dropP
        if (dropP > 0.01f) {
            val s = 0.4f + 0.6f * dropP.coerceIn(0f, 1f)
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier.offset { IntOffset((dropCx - drop / 2).roundToInt(), ((h - drop) / 2).roundToInt()) }
                    .size(with(density) { drop.toDp() })
                    .graphicsLayer { scaleX = s; scaleY = s }
                    .yields(source).clip(CircleShape).background(c.danger)
                    .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent),
                        center = Offset(drop * 0.32f, drop * 0.24f), radius = drop * 0.55f))
                    .semantics { contentDescription = discardText }
                    .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onDiscard),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        }
        val base by animateColorAsState(if (enabled) c.accent else c.line, tween(300), label = "dockBase")
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier.size(width = with(density) { right.coerceIn(0f, w).toDp() }, height = 56.dp)
                .yields(source).clip(RoundedCornerShape(50)).background(base)
                .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onSave),
            contentAlignment = Alignment.Center,
        ) {
            LiquidSurface(if (enabled) 1.05f else 0f, lerp(c.accent, Color.White, 0.08f), Modifier.fillMaxSize(), maxAmplitude = 5.dp, gloss = false)
            FitText(label, FullaType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                if (enabled) c.onAccent else c.inkMuted, Modifier.padding(horizontal = 18.dp))
        }
    }
}

/**
 * The on/off switch: a pill that fills with liquid when it is on, and a
 * thumb that stretches towards where it is going and gathers there, like a
 * drop. Only the row around it takes the tap (SwitchRow).
 */
@Composable
fun LiquidSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = FullaTheme.colors
    val reduced = FullaMotion.reduced()
    val density = LocalDensity.current
    val p by animateFloatAsState(if (checked) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 380f), label = "switch")
    val track = 52.dp
    val thumb = 24.dp
    val pad = 4.dp
    Box(modifier.size(width = track, height = 32.dp).clip(RoundedCornerShape(50)).background(c.line)) {
        LiquidSurface(if (checked) 1.05f else 0f, c.accent, Modifier.fillMaxSize(), maxAmplitude = 3.dp, gloss = false)
        val run = track - thumb - pad * 2
        val stretch = (16f * 4f * p * (1f - p)).coerceAtLeast(0f)
        val wDp = thumb + stretch.dp
        val x = pad + run * p - (if (p > 0.5f) stretch.dp else 0.dp)
        Box(Modifier.offset(x = x, y = (32.dp - thumb) / 2).size(width = wDp, height = thumb).clip(CircleShape)
            .background(if (checked) c.onAccent else c.paper))
    }
}
