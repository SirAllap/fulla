// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.core.guide.TourStop
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import io.github.sirallap.fulla.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import io.github.sirallap.fulla.ui.Tab
import io.github.sirallap.fulla.ui.guide.guideTarget
import io.github.sirallap.fulla.ui.theme.FullaMotion
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType

/**
 * Something that gives a little under the finger: 3 % smaller while pressed,
 * back on a spring when let go. Pass the same [source] to the clickable.
 */
@Composable
fun Modifier.yields(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, FullaMotion.snappy(FullaMotion.reduced()), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

data class TabItem(val label: String, val icon: ImageVector)

/**
 * The tab bar. The selected tab sits on a pill of the accent whose two edges
 * ride different springs: the edge in the direction of travel is quick, the
 * other one lazy, so the pill stretches towards the new tab and gathers
 * itself up there, like a drop.
 *
 * The round button in the middle is Add. While something is being written
 * there ([dock]) it becomes the Save button: a pill that grows out of the
 * circle, with a little red drop to throw the entry away, which leaves the
 * pill and goes back into it. The bar is tall enough to hold all of that, so
 * nothing ever sits on the keypad above it.
 */
@Composable
fun LiquidTabBar(items: List<TabItem>, selected: Int, center: Int? = null, dock: AddDock? = null, onSelect: (Int) -> Unit) {
    val c = FullaTheme.colors
    val reduced = FullaMotion.reduced()
    val last = remember { intArrayOf(selected) }
    val rightward = selected >= last[0]
    last[0] = selected
    val lead = FullaMotion.snappy<Float>(reduced)
    val lag = FullaMotion.trail<Float>(reduced)
    val left by animateFloatAsState(selected.toFloat(), if (rightward) lag else lead, label = "left")
    val right by animateFloatAsState(selected + 1f, if (rightward) lead else lag, label = "right")
    val phase = if (center != null && selected == center) dock?.phase ?: AddPhase.IDLE else AddPhase.IDLE
    val taken = phase != AddPhase.IDLE
    val tabsAlpha by animateFloatAsState(if (taken) 0f else 1f, FullaMotion.functional<Float>(reduced), label = "tabs")
    Column(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding()) {
        Hairline()
        // As tall as its icon and a line of label at the person's text size, never less than 80 dp:
        // the round button (56) and the Save pill (60) fit inside, with room to breathe.
        val barHeight = with(LocalDensity.current) { maxOf(80.dp, 22.dp + FullaType.label.lineHeight.toDp() + 24.dp) }
        Box(Modifier.fillMaxWidth().height(barHeight)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp).graphicsLayer { alpha = tabsAlpha }) {
                val slot = maxWidth / items.size
                // The round button in the middle is its own highlight: the pill fades out while it is the one on.
                val pill by animateFloatAsState(if (selected == center) 0f else 1f, FullaMotion.snappy<Float>(reduced), label = "pill")
                Box(
                    Modifier.offset(x = slot * left + 4.dp).width(slot * (right - left) - 8.dp).fillMaxHeight()
                        .graphicsLayer { alpha = pill }
                        .background(c.highlight, RoundedCornerShape(16.dp))
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    items.forEachIndexed { i, item ->
                        if (i == center) {
                            // Its place is kept; the button itself is drawn over the whole bar (CenterButton).
                            Spacer(Modifier.weight(1f).fillMaxHeight())
                            return@forEachIndexed
                        }
                        // A tab is "on" while the pill covers most of it.
                        val covered = (minOf(right, i + 1f) - maxOf(left, i.toFloat())).coerceIn(0f, 1f) > 0.5f
                        val tint = if (covered) c.onHighlight else c.inkMuted
                        val source = remember { MutableInteractionSource() }
                        // The Add tab has no tour stop of its own: once there, the tour
                        // points at the keypad instead (ADD_AND_KEYPAD), so it is not tagged here.
                        val stop = when (Tab.entries.getOrNull(i)) {
                            Tab.OVERVIEW -> TourStop.OVERVIEW_TAB
                            Tab.HISTORY -> TourStop.HISTORY_TAB
                            Tab.BALANCES -> TourStop.BALANCES_TAB
                            else -> null
                        }
                        Column(
                            Modifier.weight(1f).fillMaxHeight().yields(source)
                                .then(if (stop != null) Modifier.guideTarget(stop) else Modifier)
                                .semantics { this.selected = i == selected }
                                .clickable(source, indication = null, enabled = !taken, role = Role.Tab) { onSelect(i) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(item.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                            // Big text shrinks a step to fit its tab rather than ending in "…".
                            FitText(item.label, FullaType.label, tint)
                        }
                    }
                }
            }
            if (center != null) CenterButton(items[center], selected == center, dock, phase) { onSelect(center) }
        }
    }
}

/**
 * The middle of the bar. A round "+" (Add); once something is being written, a pill that
 * grows out of it with the liquid rising inside; when it is all there, "Save · amount".
 * Beside it a red drop that throws the entry away: it comes out of the pill and goes back
 * into it.
 */
@Composable
private fun CenterButton(item: TabItem, onAdd: Boolean, dock: AddDock?, phase: AddPhase, onSelect: () -> Unit) {
    val c = FullaTheme.colors
    val reduced = FullaMotion.reduced()
    val density = LocalDensity.current
    val open = phase == AddPhase.NEEDS_AMOUNT || phase == AddPhase.NEEDS_CATEGORY || phase == AddPhase.READY
    // The pill widens a moment after the drop has gone back in, not at the same time.
    var widen by remember { mutableStateOf(false) }
    LaunchedEffect(open, reduced) {
        if (open || reduced) widen = open else { delay(260); widen = false }
    }
    var kept by remember { mutableStateOf("") }
    val label = dock?.label.orEmpty()
    LaunchedEffect(label) { if (label.isNotEmpty()) kept = label }
    val shake = remember { Animatable(0f) }
    val nudges = dock?.nudges ?: 0
    LaunchedEffect(nudges) {
        if (nudges == 0 || reduced) return@LaunchedEffect
        shake.snapTo(with(density) { 12.dp.toPx() })
        shake.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 700f))
    }
    val discardText = stringResource(R.string.discard_entry)
    val chosen = LocalSaveColor.current
    val saveFill = chosen?.let { Color(it.fill) } ?: c.accent
    val saveInk = chosen?.let { Color(it.onFill) } ?: c.onAccent
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val circle = with(density) { 56.dp.toPx() }
        val dropSize = with(density) { 46.dp.toPx() }
        val half = minOf(with(density) { 134.dp.toPx() }, wPx / 2 - with(density) { 64.dp.toPx() })
        val dropRight = maxOf(with(density) { 10.dp.toPx() }, wPx / 2 - with(density) { 190.dp.toPx() })
        val mid = wPx / 2
        val dropEnd = wPx - dropRight - dropSize / 2
        val wide = widen && phase != AddPhase.SAVED
        val leftPx by animateFloatAsState(if (wide) mid - half else mid - circle / 2,
            if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 450f), label = "pillLeft")
        val rightPx by animateFloatAsState(if (wide) mid + half else mid + circle / 2,
            if (reduced) snap() else spring(dampingRatio = 0.5f, stiffness = 180f), label = "pillRight")
        val tall by animateFloatAsState(with(density) { if (phase == AddPhase.READY) 60.dp.toPx() else 56.dp.toPx() },
            if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 400f), label = "pillTall")
        val dropP by animateFloatAsState(if (open) 1f else 0f,
            if (reduced) snap() else spring(dampingRatio = 0.7f, stiffness = 110f), label = "drop")
        val dropCx = mid + (dropEnd - mid) * dropP

        if (dropP > 0.01f) {
            val scale = 0.4f + 0.6f * dropP.coerceIn(0f, 1f)
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier.offset { IntOffset((dropCx - dropSize / 2).roundToInt(), ((hPx - dropSize) / 2).roundToInt()) }
                    .size(with(density) { dropSize.toDp() })
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .yields(source)
                    .clip(CircleShape).background(c.danger)
                    .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent),
                        center = Offset(dropSize * 0.32f, dropSize * 0.24f), radius = dropSize * 0.55f))
                    .semantics { contentDescription = discardText }
                    .clickable(source, indication = null, enabled = open, role = Role.Button) { dock?.onDiscard() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }

        val base by animateColorAsState(
            when (phase) {
                AddPhase.IDLE -> c.highlight
                AddPhase.NEEDS_AMOUNT, AddPhase.NEEDS_CATEGORY -> c.paper
                AddPhase.READY, AddPhase.SAVED -> saveFill
            }, tween(300), label = "base")
        val dashed by animateFloatAsState(if (phase == AddPhase.NEEDS_AMOUNT || phase == AddPhase.NEEDS_CATEGORY) 1f else 0f,
            FullaMotion.functional<Float>(reduced), label = "dashed")
        val plusA by animateFloatAsState(if (phase == AddPhase.IDLE) 1f else 0f, tween(180), label = "plus")
        val labelA by animateFloatAsState(if (open) 1f else 0f, tween(200), label = "label")
        val checkA by animateFloatAsState(if (phase == AddPhase.SAVED) 1f else 0f, tween(220), label = "check")
        val text = if (phase == AddPhase.READY) saveInk else c.ink
        val source = remember { MutableInteractionSource() }
        val described = if (phase == AddPhase.IDLE) item.label else kept
        Box(
            Modifier.offset { IntOffset(leftPx.roundToInt(), ((hPx - tall) / 2).roundToInt()) }
                .size(width = with(density) { (rightPx - leftPx).toDp() }, height = with(density) { tall.toDp() })
                .graphicsLayer { translationX = shake.value }
                .jelly(phase)
                .yields(source)
                .clip(RoundedCornerShape(50))
                .background(base)
                .drawBehind {
                    if (dashed > 0.01f) {
                        val inset = 1.dp.toPx()
                        drawRoundRect(c.highlight.copy(alpha = dashed), topLeft = Offset(inset, inset),
                            size = Size(size.width - 2 * inset, size.height - 2 * inset), cornerRadius = CornerRadius(size.height / 2f),
                            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))))
                    }
                }
                .semantics { this.selected = onAdd; contentDescription = described }
                .clickable(source, indication = null, role = Role.Button) {
                    when (phase) {
                        AddPhase.READY -> dock?.onSave()
                        AddPhase.NEEDS_AMOUNT, AddPhase.NEEDS_CATEGORY -> dock?.onMissing()
                        AddPhase.SAVED -> Unit
                        AddPhase.IDLE -> onSelect()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // The liquid rises as the entry fills in: a little for "something is missing", a lot for "ready".
            val level = when (phase) {
                AddPhase.IDLE -> 0f
                AddPhase.NEEDS_AMOUNT, AddPhase.NEEDS_CATEGORY -> 0.28f
                AddPhase.READY -> 1.05f
                AddPhase.SAVED -> 1.05f
            }
            val liquidColor = if (phase == AddPhase.READY || phase == AddPhase.SAVED) androidx.compose.ui.graphics.lerp(saveFill, Color.White, 0.08f) else c.highlight.copy(alpha = 0.6f)
            LiquidSurface(level, liquidColor, Modifier.fillMaxSize(), maxAmplitude = 5.dp, gloss = false)
            Icon(androidx.compose.material.icons.Icons.Filled.Add, null, tint = c.onHighlight, modifier = Modifier.size(30.dp).graphicsLayer { alpha = plusA })
            Icon(Icons.Filled.Check, null, tint = saveInk, modifier = Modifier.size(28.dp).graphicsLayer { alpha = checkA })
            FitText(kept, FullaType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), text,
                Modifier.padding(horizontal = 18.dp).graphicsLayer { alpha = labelA })
        }
    }
}
