// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.entry

import androidx.compose.foundation.layout.width
import io.github.sirallap.fulla.ui.components.yields
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.keypad.Keypad
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.model.TransactionValidator
import io.github.sirallap.fulla.core.schema.SchemaEngine
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.BackHeader
import io.github.sirallap.fulla.ui.components.Chip
import io.github.sirallap.fulla.ui.components.MemberBadge
import io.github.sirallap.fulla.ui.components.MenuItem
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.SwitchRow
import io.github.sirallap.fulla.ui.components.TabHeader
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** What is being written down, while it is being written. */
private class Draft(view: HouseholdView, existing: Transaction?) {
    private val config = view.config
    private val firstAccount = config.accounts.firstOrNull { !it.archived }?.id
    private val everyone = config.activeMembers.map { it.id }

    val id: String = existing?.id ?: UUID.randomUUID().toString()
    var kind by mutableStateOf(existing?.kind ?: TransactionKind.EXPENSE)
    var keypad by mutableStateOf(Keypad(view.formats.currency).let { k -> existing?.let { k.withAmount(it.amountMinor) } ?: k })
    var categoryId by mutableStateOf(existing?.categoryId)
    var date by mutableStateOf(existing?.date ?: LocalDate.now())
    var accountId by mutableStateOf(existing?.accountId ?: firstAccount)
    var toAccountId by mutableStateOf(existing?.toAccountId)
    var paidBy by mutableStateOf(existing?.paidByMemberId ?: config.meMemberId ?: everyone.firstOrNull())
    var toMember by mutableStateOf(existing?.toMemberId)
    var splitWith by mutableStateOf(existing?.split?.memberIds ?: everyone.toSet())
    var note by mutableStateOf(existing?.note ?: "")
    var fixed by mutableStateOf(existing?.recurrence == Recurrence.FIXED)
    var extras by mutableStateOf<Map<String, Any?>>(existing?.extras ?: emptyMap())
    val template: Transaction? = existing

    fun build(): Transaction {
        val splits = kind == TransactionKind.EXPENSE || kind == TransactionKind.REFUND
        val members = everyone.filter { it in splitWith }
        return (template ?: Transaction(id = id, kind = kind, date = date, amountMinor = 0, createdAt = "", clientUpdatedAt = "")).copy(
            kind = kind,
            date = date,
            amountMinor = keypad.totalMinor,
            categoryId = if (kind.isCategorised) categoryId else null,
            accountId = accountId,
            toAccountId = if (kind == TransactionKind.TRANSFER) toAccountId else null,
            paidByMemberId = paidBy,
            toMemberId = if (kind == TransactionKind.SETTLEMENT) toMember else null,
            split = if (splits && everyone.size >= 2) Split.Equal(members) else null,
            recurrence = if (fixed) Recurrence.FIXED else Recurrence.VARIABLE,
            note = note.trim(),
            extras = extras,
        )
    }
}

/**
 * Writing something down: the amount first, then what it was, then save.
 * Everything else has a sensible default and sits one tap away on the
 * details line, so the common case is three taps.
 */
@Composable
fun EntryScreen(view: HouseholdView, editingId: String?, headerActions: (@Composable () -> Unit)?, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val existing = remember(editingId) { editingId?.let { id -> view.rows.firstOrNull { it.id == id }?.transaction } }
    val draft = remember(editingId, view.id) { Draft(view, existing) }
    var detailsOpen by remember { mutableStateOf(false) }
    var allCategories by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val c = FullaTheme.colors
    val fieldProblem = stringResource(R.string.field_value_problem)

    var saved by remember { mutableStateOf(false) }
    val reducedMotion = io.github.sirallap.fulla.ui.theme.FullaMotion.reduced()

    fun save() {
        if (saved) return
        val built = draft.build()
        val problems = TransactionValidator.problems(built, view.config)
        if (problems.isNotEmpty()) { problem = problems.first(); return }
        // Custom fields: checked and put in canonical form the way the server will.
        val merged = SchemaEngine.merge(view.config.fields, built.kind, draft.extras, emptyMap(),
            view.config.members.map { it.id }.toSet())
        merged.problems.firstOrNull()?.let { p ->
            val name = view.config.fields.firstOrNull { it.key == p.key }?.label(java.util.Locale.getDefault().language) ?: p.key
            problem = fieldProblem.format(name); return
        }
        // A cleared field travels as an explicit null: an absent key would keep the stored value.
        val cleared = draft.extras.filterValues { it == null }.keys.associateWith { null }
        val t = built.copy(extras = merged.extras + cleared)
        scope.launch {
            container.ledger.save(view.id, t)
            // The save key gathers into a ✓ before the screen moves on.
            saved = true
            if (!reducedMotion) kotlinx.coroutines.delay(460)
            onDone()
            saved = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (editingId == null) {
            TabHeader(stringResource(R.string.tab_add), actions = { headerActions?.invoke() })
        } else {
            val deleted = existing?.status == Status.DELETED
            BackHeader(stringResource(R.string.edit_transaction), onBack = onDone, menu = listOf(
                if (deleted) MenuItem(stringResource(R.string.restore), Icons.Outlined.RestoreFromTrash) {
                    scope.launch { container.ledger.restore(view.id, editingId); onDone() }
                } else MenuItem(stringResource(R.string.delete), Icons.Outlined.DeleteOutline, danger = true) {
                    scope.launch { container.ledger.delete(view.id, editingId); onDone() }
                },
            ))
        }

        KindRow(draft.kind) { draft.kind = it; draft.categoryId = null; problem = null }

        // The amount, as large as the screen allows.
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
            if (draft.keypad.isAdding) {
                Text(draft.keypad.addends.joinToString(" + ") { view.formats.plain(it) } + " +",
                    style = FullaType.secondary, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                view.formats.money(draft.keypad.totalMinor),
                style = FullaType.entryAmount,
                color = if (draft.kind == TransactionKind.INCOME) c.moneyIn else c.ink,
                maxLines = 1,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        // Categories and details sit just above the keypad, where the thumb already is.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (draft.kind.isCategorised) {
                CategoryTiles(view, draft.kind, draft.categoryId, showAll = allCategories,
                    onPick = { draft.categoryId = it; problem = null }, onMore = { allCategories = !allCategories })
            }
            DetailsLine(view, draft) { detailsOpen = true }
            problem?.let { Text(it, style = FullaType.secondary, color = c.danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        }
        }

        KeypadPad(
            keypad = draft.keypad,
            decimal = view.formats.decimalStyle.decimal,
            onChange = { draft.keypad = it; problem = null },
            onSave = ::save,
            saved = saved,
            saveLabel = stringResource(if (editingId == null) R.string.save else R.string.save_changes),
        )
    }

    if (detailsOpen) DetailsSheet(view, draft, onDismiss = { detailsOpen = false })
}

@Composable
private fun KindRow(kind: TransactionKind, onPick: (TransactionKind) -> Unit) {
    val kinds = listOf(
        TransactionKind.EXPENSE to R.string.kind_expense,
        TransactionKind.INCOME to R.string.kind_income,
        TransactionKind.REFUND to R.string.kind_refund,
        TransactionKind.TRANSFER to R.string.kind_transfer,
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, label) in kinds) Chip(stringResource(label), k == kind, { onPick(k) })
    }
}

@Composable
private fun CategoryTiles(view: HouseholdView, kind: TransactionKind, selected: String?, showAll: Boolean, onPick: (String) -> Unit, onMore: () -> Unit) {
    val c = FullaTheme.colors
    val usable = view.config.categories.filter { !it.archived && it.appliesTo.allows(kind) }
    // The categories used most in the last three months come first.
    val since = LocalDate.now().minusMonths(3)
    val use = view.active.filter { it.date >= since && it.kind == kind }.groupingBy { it.categoryId }.eachCount()
    val ordered = usable.sortedWith(compareByDescending<Category> { use[it.id] ?: 0 }.thenBy { it.sort })
    val shown = if (showAll || ordered.size <= 8) ordered else ordered.take(7).let { top ->
        if (selected != null && top.none { it.id == selected }) top.dropLast(1) + ordered.first { it.id == selected } else top
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 4,
    ) {
        for (cat in shown) {
            Tile(cat.name, CategoryIcons.of(cat.icon), selected = cat.id == selected, tint = c.category(cat.colorIndex)) { onPick(cat.id) }
        }
        if (ordered.size > 8) {
            Tile(stringResource(if (showAll) R.string.fewer else R.string.more), Icons.Outlined.ExpandMore, selected = false, tint = c.inkMuted, onClick = onMore)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.Tile(label: String, icon: ImageVector, selected: Boolean, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val c = FullaTheme.colors
    Column(
        Modifier.weight(1f).heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.highlight else c.paperHigh)
            .clickable(onClickLabel = label, onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) Icons.Outlined.Check else icon, null, tint = if (selected) c.onHighlight else tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = FullaType.label, color = if (selected) c.onHighlight else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Date · account · who paid · split, in one tappable line. */
@Composable
private fun DetailsLine(view: HouseholdView, draft: Draft, onClick: () -> Unit) {
    val c = FullaTheme.colors
    val parts = buildList {
        add(if (draft.date == LocalDate.now()) stringResource(R.string.today) else view.formats.day(draft.date))
        view.accountName(draft.accountId)?.let(::add)
        if (draft.kind == TransactionKind.TRANSFER) view.accountName(draft.toAccountId)?.let { add("→ $it") }
        if (view.config.activeMembers.size > 1 && draft.kind != TransactionKind.TRANSFER) {
            add(stringResource(R.string.paid_by, view.memberName(draft.paidBy)))
            if (draft.kind == TransactionKind.EXPENSE || draft.kind == TransactionKind.REFUND) {
                add(if (draft.splitWith.size == view.config.activeMembers.size) stringResource(R.string.split_everyone)
                    else stringResource(R.string.split_between, draft.splitWith.size))
            }
        }
        if (draft.note.isNotBlank()) add("“${draft.note}”")
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.details), onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(parts.joinToString(" · "), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Outlined.ExpandMore, null, tint = c.accent)
    }
}

@Composable
private fun DetailsSheet(view: HouseholdView, draft: Draft, onDismiss: () -> Unit) {
    val c = FullaTheme.colors
    var pickingDate by remember { mutableStateOf(false) }
    val accounts = view.config.accounts.filter { !it.archived }
    val members = view.config.activeMembers
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.paper) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Section(stringResource(R.string.date), top = 0.dp)
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = LocalDate.now()
                Chip(stringResource(R.string.today), draft.date == today, { draft.date = today })
                Chip(stringResource(R.string.yesterday), draft.date == today.minusDays(1), { draft.date = today.minusDays(1) })
                Chip(if (draft.date < today.minusDays(1) || draft.date > today) view.formats.day(draft.date) else stringResource(R.string.other_day),
                    draft.date < today.minusDays(1) || draft.date > today, { pickingDate = true })
            }
            Section(stringResource(if (draft.kind == TransactionKind.TRANSFER) R.string.from_account else R.string.account))
            ChoiceFlow(accounts.map { it.id to it.name }, draft.accountId) { draft.accountId = it }
            if (draft.kind == TransactionKind.TRANSFER) {
                Section(stringResource(R.string.to_account))
                ChoiceFlow(accounts.filter { it.id != draft.accountId }.map { it.id to it.name }, draft.toAccountId) { draft.toAccountId = it }
            }
            if (members.size > 1 && draft.kind != TransactionKind.TRANSFER) {
                Section(stringResource(if (draft.kind == TransactionKind.INCOME) R.string.received_by else R.string.who_paid))
                ChoiceFlow(members.map { it.id to it.displayName }, draft.paidBy) { draft.paidBy = it }
                if (draft.kind == TransactionKind.EXPENSE || draft.kind == TransactionKind.REFUND) {
                    Section(stringResource(R.string.split_between_title))
                    FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in members) {
                            val on = m.id in draft.splitWith
                            Chip(m.displayName, on, {
                                val next = if (on) draft.splitWith - m.id else draft.splitWith + m.id
                                if (next.isNotEmpty()) draft.splitWith = next
                            }, leading = { MemberBadge(m.initials, m.colorIndex, size = 20.dp) })
                        }
                    }
                }
            }
            for (field in SchemaEngine.fieldsForForm(view.config.fields, draft.kind)) {
                FieldControl(view, field, draft.extras[field.key]) { v -> draft.extras = draft.extras + (field.key to v) }
            }
            if (draft.kind == TransactionKind.INCOME || draft.kind == TransactionKind.EXPENSE) {
                SwitchRow(stringResource(R.string.fixed), stringResource(R.string.fixed_help), draft.fixed) { draft.fixed = it }
            }
            Section(stringResource(R.string.note))
            OutlinedTextField(draft.note, { if (it.length <= 500) draft.note = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.note_hint)) }, singleLine = false, maxLines = 3)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp)) { Text(stringResource(R.string.done)) }
        }
    }
    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = draft.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { draft.date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text(stringResource(R.string.done)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun ChoiceFlow(options: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((id, label) in options) Chip(label, id == selected, { onPick(id) })
    }
}

/** Digits, the decimal key (when the currency has decimals), delete, "+", and save. */
@Composable
private fun KeypadPad(keypad: Keypad, decimal: Char, onChange: (Keypad) -> Unit, onSave: () -> Unit, saveLabel: String, saved: Boolean = false) {
    val c = FullaTheme.colors
    val keyModifier = Modifier.height(56.dp)
    Row(Modifier.fillMaxWidth().background(c.paperHigh).navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in listOf("123", "456", "789")) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in row) Key(d.toString(), keyModifier.weight(1f)) { onChange(keypad.digit(d)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (keypad.hasDecimalKey) Key(decimal.toString(), keyModifier.weight(1f)) { onChange(keypad.decimal()) }
                else Spacer(keyModifier.weight(1f))
                Key("0", keyModifier.weight(1f)) { onChange(keypad.digit('0')) }
                Key("+", keyModifier.weight(1f), description = stringResource(R.string.add_another)) { onChange(keypad.plus()) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(null, keyModifier.fillMaxWidth(), icon = Icons.AutoMirrored.Outlined.Backspace, description = stringResource(R.string.delete_digit)) {
                onChange(keypad.delete())
            }
            // Saving: the tall key gathers into a circle of the accent and the ✓ pops.
            val reduced = io.github.sirallap.fulla.ui.theme.FullaMotion.reduced()
            val settle = io.github.sirallap.fulla.ui.theme.FullaMotion.settle<androidx.compose.ui.unit.Dp>(reduced)
            val height by androidx.compose.animation.core.animateDpAsState(if (saved) 72.dp else 56.dp * 3 + 12.dp, settle, label = "saveH")
            val corner by androidx.compose.animation.core.animateDpAsState(if (saved) 36.dp else 16.dp, settle, label = "saveR")
            val pop by androidx.compose.animation.core.animateFloatAsState(if (saved) 1.25f else 1f,
                io.github.sirallap.fulla.ui.theme.FullaMotion.settle(reduced), label = "savePop")
            val fill by androidx.compose.animation.animateColorAsState(when {
                saved -> c.highlight
                keypad.canSave -> c.accent
                else -> c.line
            }, io.github.sirallap.fulla.ui.theme.FullaMotion.functional(reduced), label = "saveFill")
            val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(Modifier.fillMaxWidth().height(56.dp * 3 + 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.then(if (saved) Modifier.width(72.dp) else Modifier.fillMaxWidth()).height(height)
                        .yields(source).clip(RoundedCornerShape(corner)).background(fill)
                        .clickable(source, androidx.compose.foundation.LocalIndication.current, enabled = keypad.canSave && !saved,
                            onClickLabel = saveLabel, onClick = onSave),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Check, saveLabel, tint = when {
                        saved -> c.onHighlight
                        keypad.canSave -> c.onAccent
                        else -> c.inkMuted
                    }, modifier = Modifier.size(32.dp).graphicsLayer { scaleX = pop; scaleY = pop })
                }
            }
        }
    }
}

@Composable
private fun Key(text: String?, modifier: Modifier, icon: ImageVector? = null, description: String? = null, onClick: () -> Unit) {
    val c = FullaTheme.colors
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier.yields(source).clip(RoundedCornerShape(14.dp)).background(c.paper)
            .clickable(source, androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = c.ink) else Text(text.orEmpty(), style = FullaType.key, color = c.ink)
    }
}

