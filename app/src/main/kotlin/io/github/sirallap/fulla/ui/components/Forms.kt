// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType

/*
 * The pieces of docs/ux.md that Pieces.kt did not have: a form as a screen,
 * the sheet that picks one thing, the chip that shows a detail, a segmented
 * choice that stacks when it no longer fits, a confirmation, a notice, and
 * the primary action a section hands to the screen around it.
 */

/**
 * A form (ux.md, piece 2): a screen of its own over whatever opened it,
 * never a dialog with a scrolling body. The fields scroll; the primary
 * button stays pinned at the bottom, above the navigation bar and, while
 * one is open, above the keyboard (piece 3): the screen shrinks by the
 * keyboard, and a focused field scrolls itself into what is left. Back and
 * [onClose] leave it without saving.
 *
 * [blocker] says why the button cannot run yet ("Still needed: name"); the
 * button is never just grey. [neutral] is an action that is neither (delete),
 * a quieter line under the button.
 */
@Composable
fun FormScreen(
    title: String,
    onClose: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    blocker: String? = null,
    busy: Boolean = false,
    neutral: (@Composable ColumnScope.() -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable () -> Unit = {
        val c = FullaTheme.colors
        Column(
            Modifier.fillMaxSize().background(c.paper)
                // Taps on the form never reach the screen under it.
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
        ) {
            BackHeader(title, onBack = onClose)
            androidx.compose.runtime.CompositionLocalProvider(LocalRowInset provides 0.dp) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
            Hairline()
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                blocker?.let { Text(it, style = FullaType.secondary, color = c.warning) }
                SaveDock(primaryLabel, onPrimary, enabled = primaryEnabled && blocker == null, busy = busy, onDiscard = onClose)
                neutral?.invoke(this)
            }
        }
    }
    val layer = LocalFormLayer.current
    if (layer == null) {
        // Only where no FormLayerHost is drawn (a test): a dialog window of its own.
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { body() }
        return
    }
    // Drawn in the app's own window, over everything, by FormLayerHost: a
    // dialog window of its own got the phone's edges wrong (it sat under the
    // status bar and ran past the bottom, the button out of reach).
    val latest = androidx.compose.runtime.rememberUpdatedState(body)
    val close = androidx.compose.runtime.rememberUpdatedState(onClose)
    androidx.compose.runtime.DisposableEffect(layer) {
        val entry: @Composable () -> Unit = {
            androidx.activity.compose.BackHandler { close.value() }
            latest.value()
        }
        layer.entries.add(entry)
        onDispose { layer.entries.remove(entry) }
    }
}

/** Forms open in the app's own window, over the screen that opened them; [FormLayerHost] draws them. */
class FormLayer {
    internal val entries = androidx.compose.runtime.mutableStateListOf<@Composable () -> Unit>()
}

val LocalFormLayer = androidx.compose.runtime.staticCompositionLocalOf<FormLayer?> { null }

/** Draws the open forms over everything else; the last one on top. Goes last in the app's root box. */
@Composable
fun FormLayerHost(layer: FormLayer) {
    for (entry in layer.entries.toList()) androidx.compose.runtime.key(entry) {
        Box(Modifier.fillMaxSize()) { entry() }
    }
}

/** The name of a group of fields in a [FormScreen]: what the fields under it are about. */
@Composable
fun FormLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = FullaType.label, color = FullaTheme.colors.inkMuted,
        modifier = modifier.padding(top = 8.dp).semantics { heading() })
}

/**
 * What a choice opened (ux.md, piece 4): right under the choice, set apart
 * on the raised paper so it is seen to be new, never at the end of the form.
 */
@Composable
fun Revealed(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FullaTheme.colors.paperHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * A sheet that picks one thing (ux.md, piece 5): opened at its full height,
 * a title, the options; a tap picks and closes. It keeps clear of the
 * navigation bar and the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = FullaTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.paper,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            // The sheet's head fills with a light liquid as it opens.
            var open by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(Unit) { open = true }
            Box(Modifier.fillMaxWidth()) {
                LiquidSurface(if (open) 1.05f else 0f, c.highlight.copy(alpha = if (c.isDark) 0.22f else 0.32f), Modifier.matchParentSize(), maxAmplitude = 5.dp, gloss = false)
                Text(title, style = FullaType.title, color = c.ink,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp).semantics { heading() })
            }
            content()
        }
    }
}

/** One option of a [PickSheet]: its name, maybe a line about it, a tick when it is the one chosen. */
@Composable
fun PickRow(label: String, selected: Boolean, onClick: () -> Unit, context: String? = null, start: (@Composable () -> Unit)? = null) {
    val c = FullaTheme.colors
    ListRow(label, modifier = Modifier.semantics { this.selected = selected }, context = context, start = start, onClick = onClick,
        end = { if (selected) Icon(Icons.Outlined.Check, null, tint = c.accent) })
}

/**
 * A detail of what is being written (ux.md, piece 6), always in sight. With
 * a value it shows the value and ▾, and opens what changes it; without one
 * ([empty]) it is dashed and says "+ [value]", what can be added. It grows
 * with its text and wraps rather than cuts.
 */
@Composable
fun DetailChip(value: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, empty: Boolean = false) {
    val c = FullaTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val source = remember { MutableInteractionSource() }
    val outline = if (empty) Modifier.drawBehind {
        val r = 8.dp.toPx()
        drawRoundRect(c.inkMuted, cornerRadius = CornerRadius(r, r),
            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    } else Modifier.border(1.dp, c.line, shape)
    Row(
        modifier.minimumInteractiveComponentSize().yields(source).jelly(value).clip(shape).then(outline)
            .clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick)
            .heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            empty -> Icon(Icons.Outlined.Add, null, tint = c.ink, modifier = Modifier.size(18.dp))
            icon != null -> Icon(icon, null, tint = c.inkMuted, modifier = Modifier.size(18.dp))
        }
        Text(value, style = FullaType.secondary, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!empty) Icon(Icons.Outlined.ExpandMore, null, tint = c.inkMuted, modifier = Modifier.size(18.dp))
    }
}

/**
 * Two to four choices of which one is on, side by side and equal while
 * their words fit; one above the other when they do not (a narrow phone,
 * a large font), never cut.
 */
@Composable
fun <T> SegmentedChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = FullaTheme.colors
    Layout(
        content = {
            for ((value, label) in options) {
                val on = value == selected
                val ink by androidx.compose.animation.animateColorAsState(if (on) c.onHighlight else c.ink,
                    androidx.compose.animation.core.tween(220), label = "choice")
                Box(
                    Modifier.jelly(on).clip(RoundedCornerShape(10.dp))
                        .selectable(on, role = Role.RadioButton, onClick = { onSelect(value) }),
                    contentAlignment = Alignment.Center,
                ) {
                    // The choice that is on is filled with a liquid that rises (LiquidSurface), not painted flat.
                    // The liquid fills the whole choice, so the padding is on the words, not on the box.
                    LiquidSurface(if (on) 1.05f else 0f, c.highlight, Modifier.matchParentSize(), maxAmplitude = 3.dp)
                    Text(label, style = FullaType.secondary, color = ink, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp))
                }
            }
        },
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.paperHigh).padding(4.dp).selectableGroup(),
    ) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val minHeight = 40.dp.roundToPx()
        val width = constraints.maxWidth
        val n = measurables.size.coerceAtLeast(1)
        // All in one row; when they don't fit (big text, a narrow phone),
        // two columns for four or more; else one under the other.
        val columns = listOf(n, if (n >= 4) (n + 1) / 2 else n, 1).first { cols ->
            val share = (width - gap * (cols - 1)) / cols
            cols == 1 || measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share }
        }
        val share = (width - gap * (columns - 1)) / columns
        val rows = measurables.chunked(columns)
        val heights = rows.map { row -> maxOf(minHeight, row.maxOf { it.minIntrinsicHeight(share) }) }
        val placeables = rows.mapIndexed { r, row -> row.map { it.measure(Constraints.fixed(share, heights[r])) } }
        layout(width, heights.sum() + gap * (rows.size - 1)) {
            var y = 0
            placeables.forEachIndexed { r, row ->
                row.forEachIndexed { i, p -> p.place(i * (share + gap), y) }
                y += heights[r] + gap
            }
        }
    }
}

/**
 * Asking before something serious (ux.md, piece 8): what goes, one line on
 * what follows, and the exact verb on the button, in the colour of danger.
 */
@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    val c = FullaTheme.colors
    FullaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = FullaType.secondary) },
        confirmButton = {
            if (danger) {
                // Cannot be undone: press and hold, and the red rises (HoldToConfirm).
                Column(Modifier.widthIn(min = 160.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    HoldToConfirm(confirmLabel, onConfirm)
                    Text(stringResource(R.string.hold_hint), style = FullaType.label, color = c.inkMuted, modifier = Modifier.padding(top = 4.dp))
                }
            } else TextButton(onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = c.accent)) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Something the person should act on (ux.md, piece 10): a card at the top,
 * one sentence, one action, and a way to put it away when there is one.
 */
@Composable
fun Notice(text: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier, title: String? = null,
           dismissLabel: String? = null, onDismiss: (() -> Unit)? = null) {
    val c = FullaTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(20.dp))
            .background(c.paperHigh).glass().border(1.dp, c.warning, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        title?.let { Text(it, style = FullaType.body, color = c.ink, modifier = Modifier.semantics { heading() }) }
        Text(text, style = FullaType.secondary, color = c.inkMuted)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAction) { Text(actionLabel) }
            if (onDismiss != null && dismissLabel != null) TextButton(onClick = onDismiss) { Text(dismissLabel, color = c.inkMuted) }
        }
    }
}

/** A screen's one primary action as a section asks for it (ux.md, piece 1). */
data class PrimaryAction(val label: String, val icon: ImageVector?, val onClick: () -> Unit)

/** Where a screen keeps the primary action its content asked for, to draw it pinned at the bottom. */
class PrimaryActionHolder {
    var action by mutableStateOf<PrimaryAction?>(null)
}

val LocalPrimaryAction = staticCompositionLocalOf<PrimaryActionHolder?> { null }

/**
 * Asks the screen around this content to pin [label] at its bottom as its
 * primary action, for as long as this content is shown: "Add a fixed cost"
 * is the screen's button, never one more row of its list (ux.md, piece 11).
 * Outside such a screen it draws the button where it is.
 */
@Composable
fun ScreenPrimaryAction(label: String, icon: ImageVector? = Icons.Outlined.Add, onClick: () -> Unit) {
    val holder = LocalPrimaryAction.current
    val current by rememberUpdatedState(onClick)
    if (holder == null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) { PrimaryButton(label, { current() }, icon = icon) }
        return
    }
    DisposableEffect(holder, label, icon) {
        val action = PrimaryAction(label, icon) { current() }
        holder.action = action
        onDispose { if (holder.action === action) holder.action = null }
    }
}

/** The pinned bottom of a screen that holds a [PrimaryActionHolder]: its action, if any was asked for. */
@Composable
fun PrimaryActionBar(holder: PrimaryActionHolder) {
    val action = holder.action ?: return
    ActionBar { PrimaryButton(action.label, action.onClick, icon = action.icon) }
}

/**
 * A setting shown as the field it opens (the Hogar board): its label above,
 * its value in a box the shape of a text field, and its help under it. A
 * value that cannot be changed shows a lock and no ripple.
 */
@Composable
fun ValueField(label: String, value: String, modifier: Modifier = Modifier, help: String? = null, onClick: (() -> Unit)? = null) {
    val c = FullaTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = FullaType.label, color = c.inkMuted)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(if (onClick != null) c.paper else c.paperHigh)
                .then(if (onClick != null) Modifier.border(1.dp, c.line, shape).clickable(onClick = onClick) else Modifier)
                .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = FullaType.body, color = c.ink, modifier = Modifier.weight(1f))
            Icon(if (onClick != null) androidx.compose.material.icons.Icons.Outlined.Edit else androidx.compose.material.icons.Icons.Outlined.Lock,
                null, tint = c.inkMuted, modifier = Modifier.size(18.dp))
        }
        help?.let { Text(it, style = FullaType.secondary, color = c.inkMuted) }
    }
}
