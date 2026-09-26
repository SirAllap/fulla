// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.core.guide.TourStop
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
 */
@Composable
fun LiquidTabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit) {
    val c = FullaTheme.colors
    val reduced = FullaMotion.reduced()
    val last = remember { intArrayOf(selected) }
    val rightward = selected >= last[0]
    last[0] = selected
    val lead = FullaMotion.snappy<Float>(reduced)
    val lag = FullaMotion.trail<Float>(reduced)
    val left by animateFloatAsState(selected.toFloat(), if (rightward) lag else lead, label = "left")
    val right by animateFloatAsState(selected + 1f, if (rightward) lead else lag, label = "right")
    Column(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding()) {
        Hairline()
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
            val slot = maxWidth / items.size
            Box(
                Modifier.offset(x = slot * left + 4.dp).width(slot * (right - left) - 8.dp).fillMaxHeight()
                    .background(c.highlight, RoundedCornerShape(16.dp))
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                items.forEachIndexed { i, item ->
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
                            .clickable(source, indication = null, role = Role.Tab) { onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(item.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(item.label, style = FullaType.label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
