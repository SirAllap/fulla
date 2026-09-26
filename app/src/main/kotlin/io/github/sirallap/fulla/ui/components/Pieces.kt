// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import io.github.sirallap.fulla.ui.theme.FullaMotion
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import io.github.sirallap.fulla.core.trips.Trip
import io.github.sirallap.fulla.core.trips.TripKind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.remote.FullaApi
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.core.model.MoneyMode
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/*
 * The pieces every screen is made of. One component per idea, so the same
 * thing looks the same everywhere: a screen is an arrangement of these, and
 * a screen that draws its own row is a bug waiting to drift.
 */

/** One entry of a ⋮ menu. */
data class MenuItem(val label: String, val icon: ImageVector, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * The top of a tab: its title, then whatever the app puts on every tab
 * (sync, settings), then the tab's own ⋮.
 */
@Composable
fun TabHeader(
    title: String,
    modifier: Modifier = Modifier,
    menu: List<MenuItem> = emptyList(),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = FullaTheme.colors
    Row(
        modifier.fillMaxWidth().height(64.dp).background(c.paper).padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = FullaType.screenTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        actions()
        if (menu.isNotEmpty()) OverflowMenu(menu)
    }
}

/** The top of a screen reached from a tab: back, title, ⋮. */
@Composable
fun BackHeader(title: String, onBack: () -> Unit, menu: List<MenuItem> = emptyList(), modifier: Modifier = Modifier) {
    val c = FullaTheme.colors
    Row(
        modifier.fillMaxWidth().height(64.dp).background(c.paper).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), tint = c.accent)
        }
        Text(
            title, style = FullaType.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
        )
        if (menu.isNotEmpty()) OverflowMenu(menu)
    }
}

@Composable
fun OverflowMenu(items: List<MenuItem>) {
    val c = FullaTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_options), tint = c.inkMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in items) {
                val tint = if (item.danger) c.danger else c.ink
                DropdownMenuItem(
                    text = { Text(item.label, style = FullaType.body, color = tint) },
                    leadingIcon = { Icon(item.icon, null, tint = if (item.danger) c.danger else c.inkMuted) },
                    onClick = { open = false; item.onClick() },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(FullaTheme.colors.line))
}

/** The name of a group of rows. Rows draw their own line underneath. */
/** Side inset of rows and section titles: 20 dp on a screen, 0 inside a dialog that already pads its content. */
val LocalRowInset = androidx.compose.runtime.staticCompositionLocalOf { 20.dp }

@Composable
fun Section(text: String, modifier: Modifier = Modifier, top: Dp = 24.dp, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = LocalRowInset.current, end = LocalRowInset.current, top = top, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text.uppercase(), style = FullaType.section, color = FullaTheme.colors.inkMuted,
                modifier = Modifier.weight(1f).semantics { heading() })
            trailing?.invoke(this)
        }
        Hairline()
    }
}

/**
 * A row: a title, at most a line of context and a line of detail. Anything
 * that needs a fourth line is a screen. [below] is for the one thing that is
 * not text (a progress line); [end] holds an amount, a chevron or a switch.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    context: String? = null,
    detail: String? = null,
    titleColor: Color = Color.Unspecified,
    detailColor: Color = Color.Unspecified,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    start: (@Composable () -> Unit)? = null,
    indent: Dp = 0.dp,
    divider: Boolean = true,
    clickLabel: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    end: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = FullaTheme.colors
    val clickable = when {
        onLongClick != null -> Modifier.combinedClickable(onClickLabel = clickLabel, onClick = { onClick?.invoke() }, onLongClick = onLongClick)
        onClick != null -> Modifier.clickable(onClickLabel = clickLabel, onClick = onClick)
        else -> Modifier
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().then(clickable).heightIn(min = 56.dp)
                .padding(start = 20.dp + indent, end = 20.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            start?.invoke()
            if (icon != null) Icon(icon, null, tint = if (iconTint == Color.Unspecified) c.inkMuted else iconTint)
            Column(Modifier.weight(1f)) {
                Text(title, style = FullaType.body, color = if (titleColor == Color.Unspecified) c.ink else titleColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                below?.invoke(this)
                if (!context.isNullOrBlank()) {
                    Text(context, style = FullaType.secondary, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!detail.isNullOrBlank()) {
                    Text(detail, style = FullaType.secondary, color = if (detailColor == Color.Unspecified) c.inkMuted else detailColor,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            end?.invoke(this)
        }
        if (divider) Hairline()
    }
}

/** An amount at the end of a row: tabular, right-aligned. */
@Composable
fun AmountText(text: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Text(text, style = FullaType.amount, color = if (color == Color.Unspecified) FullaTheme.colors.ink else color,
        textAlign = TextAlign.End, maxLines = 1, modifier = modifier)
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = FullaTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
                .heightIn(min = 56.dp).padding(horizontal = LocalRowInset.current, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = FullaType.body, color = c.ink)
                subtitle?.let { Text(it, style = FullaType.secondary, color = c.inkMuted) }
            }
            // The row takes the tap, so TalkBack reads one control, not two.
            Switch(checked = checked, onCheckedChange = null)
        }
        Hairline()
    }
}

/** A chip: a check when it is on, never colour alone. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    val c = FullaTheme.colors
    val reduced = FullaMotion.reduced()
    val container by animateColorAsState(if (selected) c.highlight else c.paper, FullaMotion.functional(reduced), label = "chip")
    val content by animateColorAsState(if (selected) c.onHighlight else c.ink, FullaMotion.functional(reduced), label = "chipText")
    val source = remember { MutableInteractionSource() }
    FilterChip(
        selected = selected,
        onClick = onClick,
        interactionSource = source,
        modifier = Modifier.yields(source),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = c.line,
            selectedBorderColor = container),
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (selected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else leading,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = container,
            selectedContainerColor = container,
            selectedLabelColor = content,
            selectedLeadingIconColor = content,
            labelColor = content,
        ),
    )
}

@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((value, label) in options) Chip(label, value == selected, { onSelect(value) })
    }
}

/**
 * The prominent, always-visible "Everyday / ✈ trip" choice on the Add
 * screen, above the amount and keypad rather than one tap deep in the
 * details sheet: assigning a trip is an explicit, reversible step before
 * saving, never something a person only discovers after the fact (owner
 * feedback: a household member who wasn't on a trip could otherwise have a
 * personal expense land on it by accident). [trips] is every trip active on
 * the entry's date, for anyone in the household, not filtered to who is on
 * it — everyone can still explicitly choose any of them. When there is more
 * than one, tapping the trip side opens a picker instead of guessing;
 * otherwise it toggles straight to the one trip.
 */
/** Which icon names a trip everywhere it shows one: the Add toggle, Settings › Trips, and the Overview row. */
fun tripKindIcon(kind: TripKind): ImageVector = when (kind) {
    TripKind.HOLIDAY -> Icons.Outlined.BeachAccess
    TripKind.WORK -> Icons.Outlined.Work
    TripKind.EVENT -> Icons.Outlined.Celebration
    TripKind.FAMILY -> Icons.Outlined.FamilyRestroom
    TripKind.OTHER -> Icons.Outlined.Luggage
}

@Composable
fun TripToggle(
    everydayLabel: String,
    trips: List<Trip>,
    tripPickerTitle: String,
    selectedTripId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (trips.isEmpty()) return
    val c = FullaTheme.colors
    var pickerOpen by remember { mutableStateOf(false) }
    val selected = trips.firstOrNull { it.id == selectedTripId }
    val onTrip = selected != null
    val shown = selected ?: trips.first()
    val tripLabel = shown.name

    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp)).background(c.paperHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToggleOption(everydayLabel, icon = null, selected = !onTrip, modifier = Modifier.weight(1f)) { onSelect(null) }
        ToggleOption(tripLabel, icon = tripKindIcon(shown.kind), selected = onTrip, modifier = Modifier.weight(1f)) {
            if (trips.size > 1) pickerOpen = true else onSelect(trips.first().id)
        }
    }

    if (pickerOpen) {
        ModalBottomSheet(onDismissRequest = { pickerOpen = false }, containerColor = c.paper) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
                Section(tripPickerTitle, top = 0.dp)
                for (t in trips) {
                    ListRow(t.name, icon = tripKindIcon(t.kind), onClick = { onSelect(t.id); pickerOpen = false })
                }
            }
        }
    }
}

@Composable
private fun RowScope.ToggleOption(label: String, icon: ImageVector?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = FullaTheme.colors
    val reduced = FullaMotion.reduced()
    val container by animateColorAsState(if (selected) c.highlight else c.paper, FullaMotion.functional(reduced), label = "tripToggleBg")
    val content by animateColorAsState(if (selected) c.onHighlight else c.ink, FullaMotion.functional(reduced), label = "tripToggleFg")
    val source = remember { MutableInteractionSource() }
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(container)
            .clickable(source, LocalIndication.current, onClickLabel = label, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Arrows around a period's name. */
@Composable
fun PeriodSelector(label: String, onPrevious: () -> Unit, onNext: () -> Unit, canGoNext: Boolean = true) {
    val c = FullaTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.Outlined.ChevronLeft, stringResource(R.string.previous_period), tint = c.accent) }
        Text(label, style = FullaType.amount, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(Icons.Outlined.ChevronRight, stringResource(R.string.next_period), tint = if (canGoNext) c.accent else c.line)
        }
    }
}

/** A screen's one primary action, at the bottom where the thumb is. */
@Composable
fun ActionBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(FullaTheme.colors.paper).navigationBarsPadding()) {
        Hairline()
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp), content = content)
    }
}

/**
 * Full width, 56dp, the accent. Disabled is shown, not hidden. While [busy],
 * the work it started is still running: it keeps its colour, shows a small
 * turning ring and takes no second tap.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    busy: Boolean = false,
) {
    val c = FullaTheme.colors
    val background = when {
        busy -> if (danger) c.danger else c.accent
        !enabled -> c.line
        danger -> c.danger
        else -> c.accent
    }
    val tint = if (enabled || busy) c.onAccent else c.inkMuted
    val source = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).yields(source).clip(RoundedCornerShape(16.dp)).background(background)
            .clickable(source, LocalIndication.current, enabled = enabled && !busy, role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = FullaType.body, color = tint, textAlign = TextAlign.Center)
    }
}

/** The quieter sibling of [PrimaryButton], for a second choice. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = FullaTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxWidth().heightIn(min = 48.dp).yields(source).clip(RoundedCornerShape(16.dp))
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .clickable(source, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = FullaType.body, color = if (enabled) c.ink else c.inkMuted) }
}

/** An empty list, explained: what would be here and how to get it there. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = FullaTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = c.inkMuted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(6.dp))
        Text(title, style = FullaType.body, color = c.ink, textAlign = TextAlign.Center)
        Text(text, style = FullaType.secondary, color = c.inkMuted, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

/** Text somebody may need to pass on (an invite code, an error), with a copy button. */
@Composable
fun CopyableText(text: String, modifier: Modifier = Modifier) {
    val c = FullaTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_500); copied = false } }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(c.paperHigh).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = FullaType.secondary.copy(fontFamily = FontFamily.Monospace), color = c.ink, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }) {
            Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                stringResource(if (copied) R.string.copied else R.string.copy), tint = c.accent)
        }
    }
}

/** A person: their colour and their initials, never the colour alone. */
@Composable
fun MemberBadge(initials: String, colorIndex: Int, modifier: Modifier = Modifier, size: Dp = 32.dp, description: String? = null) {
    val c = FullaTheme.colors
    Box(
        modifier.size(size).clip(CircleShape).background(c.member(colorIndex))
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials.take(2), style = FullaType.label, color = c.paper, maxLines = 1)
    }
}

/** A thin bar for how much of something is used: a budget, a share. */
@Composable
fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier, over: Boolean = false) {
    val c = FullaTheme.colors
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp))
            .background(if (over) c.danger else color))
    }
}

/** A password, hidden by default, with an eye that shows it while someone checks what they typed. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    var shown by remember { mutableStateOf(false) }
    androidx.compose.material3.OutlinedTextField(
        value, onValueChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, isError = isError,
        visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None
        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(
                    if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    stringResource(if (shown) R.string.hide_password else R.string.show_password),
                )
            }
        },
        supportingText = supportingText?.let { { Text(it) } },
    )
}

/**
 * How the household handles money together: one shared pot, or splitting
 * expenses. Asked before somebody else joins while nobody has chosen, and
 * offered again from Household settings, where [current] is ticked.
 */
@Composable
fun MoneyModeSheet(
    current: MoneyMode?,
    onChoose: (MoneyMode) -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.not_now),
) {
    val c = FullaTheme.colors
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.paper) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.money_mode_ask_title), style = FullaType.title, color = c.ink,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
            for ((mode, title, help) in listOf(
                Triple(MoneyMode.SHARED, R.string.money_mode_shared, R.string.money_mode_shared_help),
                Triple(MoneyMode.SPLIT, R.string.money_mode_split, R.string.money_mode_split_help),
            )) {
                ListRow(stringResource(title), context = stringResource(help), onClick = { onChoose(mode) },
                    end = { if (current == mode) Icon(Icons.Outlined.Check, null, tint = c.accent) })
            }
            Text(stringResource(R.string.money_mode_ask_footer), style = FullaType.secondary, color = c.inkMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp)) {
                Text(dismissLabel)
            }
        }
    }
}

/**
 * A structure change, on the phone or through the server for a shared
 * household, with [onError] told of the result. The one place a screen's
 * "runs a change against `change: Change`" plumbing is written; Settings and
 * the guide's setup steps both call this instead of writing their own copy.
 */
@Composable
fun rememberChange(view: HouseholdView, onError: (String?) -> Unit = {}): (suspend (FullaApi?) -> Unit) -> Unit {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.something_failed)
    return { block ->
        scope.launch {
            onError(null)
            val api = if (view.state.connected) container.api() else null
            if (view.state.connected && api == null) { onError(failed); return@launch }
            runCatching { block(api) }.onFailure { onError((it as? FullaError)?.message ?: failed) }
        }
    }
}

/**
 * One screen of the getting-started guide: a bottom sheet on [FullaTheme]'s
 * paper, a step indicator, the screen's own [content], and one primary action
 * in the bottom third next to a quieter "Skip". Modelled on [MoneyModeSheet].
 */
@Composable
fun GuideSheet(
    title: String,
    onSkip: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    stepOf: Pair<Int, Int>? = null,
    primaryEnabled: Boolean = true,
    /**
     * False for a step that holds typed input a person could lose: an
     * accidental scrim tap or system Back then does nothing, rather than
     * silently discarding it the way an explicit Skip would. The step is
     * still left the moment Skip or the primary button is pressed.
     */
    dismissOnOutsideTap: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val c = FullaTheme.colors
    val onOutsideTap: () -> Unit = if (dismissOnOutsideTap) onSkip else ({})
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onOutsideTap, containerColor = c.paper, modifier = modifier) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = FullaType.title, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                stepOf?.let { (i, n) -> Text(stringResource(R.string.guide_step_of, i, n), style = FullaType.secondary, color = c.inkMuted) }
            }
            body?.let { Text(it, style = FullaType.body, color = c.inkMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            Column(Modifier.padding(horizontal = 20.dp), content = content)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                PrimaryButton(primaryLabel, onPrimary, enabled = primaryEnabled)
            }
            androidx.compose.material3.TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.guide_skip))
            }
        }
    }
}
