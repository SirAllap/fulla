// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType

/**
 * A search field as a pill of glass: while it is being typed in, a light
 * liquid of the accent rises in it and the magnifier gives a little wobble.
 * Nothing moves while it is not focused.
 */
@Composable
fun SearchPill(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = FullaTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ink by animateColorAsState(if (focused) c.ink else c.inkMuted, tween(200), label = "searchInk")
    Box(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).heightIn(min = 52.dp)
        .clip(RoundedCornerShape(50)).background(c.paperHigh)) {
        LiquidSurface(if (focused) 1.05f else 0f, c.highlight.copy(alpha = if (c.isDark) 0.22f else 0.30f), Modifier.matchParentSize(), maxAmplitude = 4.dp, gloss = false)
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, tint = ink, modifier = Modifier.jelly(focused).size(22.dp))
            Box(Modifier.padding(start = 12.dp)) {
                if (value.isEmpty()) Text(placeholder, style = FullaType.body, color = c.inkMuted, maxLines = 1)
                BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, interactionSource = source,
                    textStyle = FullaType.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink))
            }
        }
    }
}
