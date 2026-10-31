// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import io.github.sirallap.fulla.ui.components.FullaDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sirallap.fulla.BuildConfig
import io.github.sirallap.fulla.core.version.ReleaseHistory
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.local.RecurringPlanner
import io.github.sirallap.fulla.client.remote.FullaApi
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.InviteLink
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.balance.Balances
import io.github.sirallap.fulla.core.balance.MemberBalance
import io.github.sirallap.fulla.core.balance.SettlementPlanner
import io.github.sirallap.fulla.core.design.MoneyPalette
import io.github.sirallap.fulla.core.guide.MonthStart
import io.github.sirallap.fulla.core.model.AppliesTo
import io.github.sirallap.fulla.core.model.MoneyMode
import io.github.sirallap.fulla.core.model.Role
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.roles.Permissions
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.Chip
import io.github.sirallap.fulla.ui.components.CopyableText
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.MemberBadge
import io.github.sirallap.fulla.ui.components.MoneyModeSheet
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.components.QrImage
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.SecondaryButton
import io.github.sirallap.fulla.ui.components.SwitchRow
import io.github.sirallap.fulla.ui.entry.CategoryIcons
import io.github.sirallap.fulla.ui.guide.toJsonObject
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import io.github.sirallap.fulla.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.UUID

typealias Change = (suspend (FullaApi?) -> Unit) -> Unit

/** A dialog with one or two text fields. */
@Composable
internal fun EditDialog(
    title: String,
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    number: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
    /** Beyond the plain not-blank check, e.g. a length limit or fields in [extra]. */
    enabledFor: (String) -> Boolean = { true },
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    // With more than its one field it is a form, and a form is a screen of
    // its own (docs/ux.md, piece 2); a lone name stays a small dialog.
    if (extra != null) {
        io.github.sirallap.fulla.ui.components.FormScreen(
            title = title,
            onClose = onDismiss,
            primaryLabel = stringResource(R.string.save),
            primaryEnabled = text.isNotBlank() && enabledFor(text.trim()),
            onPrimary = { onSave(text.trim()); onDismiss() },
        ) {
            OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text))
            extra()
        }
        return
    }
    FullaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              androidx.compose.runtime.CompositionLocalProvider(io.github.sirallap.fulla.ui.components.LocalRowInset provides 0.dp) {
                OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text))
                extra?.invoke()
              }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()); onDismiss() }, enabled = text.isNotBlank() && enabledFor(text.trim())) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ── household ────────────────────────────────────────────────────────────────

@Composable
fun HouseholdSettings(view: HouseholdView, canEdit: Boolean, change: Change) {
    val container = LocalContainer.current
    val ledger = container.ledger
    val scope = rememberCoroutineScope()
    val h = view.config.household
    var editing by remember { mutableStateOf<String?>(null) }
    val me = view.me
    val shared = SharedPot.isShared(h)
    var choosingMode by remember { mutableStateOf(false) }
    var owedFromBefore by remember { mutableStateOf<MemberBalance?>(null) }
    var splittingAgain by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var modeError by remember { mutableStateOf<String?>(null) }
    val syncFailed = stringResource(R.string.shared_pot_sync_failed)
    val failed = stringResource(R.string.something_failed)
    val startedNote = stringResource(R.string.shared_pot_started_note)
    val balances = remember(view) { Balances.of(view.active, view.config.members.map { it.id }) }

    fun setMode(mode: MoneyMode) = change { api -> ledger.updateHousehold(view.id, buildJsonObject { put("money_mode", mode.key) }, api) }

    /**
     * One shared pot from now on. What is owed from before is settled first
     * when [settle], with ordinary settlements dated today, and those must
     * reach the server before the switch: once the pot is shared the server
     * refuses new settlements. If they cannot be sent, nothing changes.
     */
    fun switchToShared(settle: Boolean) {
        switching = true
        modeError = null
        scope.launch {
            try {
                val api = if (view.state.connected) container.api() else null
                if (view.state.connected && api == null) { modeError = syncFailed; return@launch }
                val payments = if (!settle) emptyList() else SettlementPlanner.plan(balances).map { p ->
                    Transaction(
                        id = UUID.randomUUID().toString(), kind = TransactionKind.SETTLEMENT, date = LocalDate.now(),
                        amountMinor = p.amountMinor, paidByMemberId = p.fromMemberId, toMemberId = p.toMemberId,
                        note = startedNote, createdAt = "", clientUpdatedAt = "", createdByMemberId = view.config.meMemberId,
                    )
                }
                if (payments.isNotEmpty()) ledger.saveAll(view.id, payments)
                if (view.state.connected && ledger.hasUnsentSettlements(view.id)) {
                    container.syncAll()
                    // Refused (the pot may already be shared on the server): they would be debts on this phone only.
                    val refused = payments.filter { ledger.transaction(it.id)?.state == SyncState.REJECTED }
                    refused.forEach { ledger.delete(view.id, it.id) }
                    if (refused.isNotEmpty() || ledger.hasUnsentSettlements(view.id)) { modeError = syncFailed; return@launch }
                }
                ledger.updateHousehold(view.id, buildJsonObject { put("money_mode", MoneyMode.SHARED.key) }, api)
            } catch (e: Exception) {
                modeError = (e as? FullaError)?.message ?: failed
            } finally {
                switching = false
            }
        }
    }

    fun choose(mode: MoneyMode) {
        when {
            mode == h.moneyMode -> Unit
            mode == MoneyMode.SPLIT && shared -> splittingAgain = true
            mode == MoneyMode.SPLIT -> setMode(MoneyMode.SPLIT)
            else -> {
                // Whoever is owed the most names the question; the settling covers everyone.
                val owed = balances.filter { it.balanceMinor > 0 }.maxByOrNull { it.balanceMinor }
                if (owed == null) switchToShared(settle = false) else owedFromBefore = owed
            }
        }
    }

    Column {
        // As in the Hogar board: each setting as the field it opens, and how money is shared as one choice in sight.
        io.github.sirallap.fulla.ui.components.ValueField(stringResource(R.string.household_name), h.name,
            onClick = if (canEdit) ({ editing = "name" }) else null, modifier = Modifier.padding(top = 8.dp))
        io.github.sirallap.fulla.ui.components.ValueField(stringResource(R.string.currency), h.currency, help = stringResource(R.string.currency_change_note),
            onClick = if (me != null && Permissions.canChangeCurrencyOrLimit(me)) ({ editing = "currency" }) else null)
        io.github.sirallap.fulla.ui.components.ValueField(stringResource(R.string.period_start_day), stringResource(R.string.period_start_day_value, h.periodStartDay),
            help = stringResource(R.string.period_start_day_help), onClick = if (canEdit) ({ editing = "period_start_day" }) else null)
        io.github.sirallap.fulla.ui.components.ValueField(stringResource(R.string.income_shift_day),
            h.incomeShiftDay?.let { stringResource(R.string.income_shift_day_value, it) } ?: stringResource(R.string.off),
            help = stringResource(R.string.income_shift_day_help), onClick = if (canEdit) ({ editing = "income_shift_day" }) else null)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.money_between_members), style = FullaType.label, color = FullaTheme.colors.inkMuted)
            if (canEdit && !switching) {
                io.github.sirallap.fulla.ui.components.SegmentedChoice(
                    listOf(MoneyMode.SPLIT to stringResource(R.string.money_mode_split_value), MoneyMode.SHARED to stringResource(R.string.shared_pot_card_title)),
                    if (shared) MoneyMode.SHARED else MoneyMode.SPLIT, { choose(it) })
            } else {
                Text(stringResource(if (shared) R.string.shared_pot_card_title else R.string.money_mode_split_value), style = FullaType.body, color = FullaTheme.colors.ink)
            }
            Text(stringResource(if (shared) R.string.shared_pot_card_text else R.string.money_mode_split_help), style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
        }
        modeError?.let { Text(it, style = FullaType.secondary, color = FullaTheme.colors.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
    }
    if (choosingMode) {
        MoneyModeSheet(h.moneyMode, dismissLabel = stringResource(R.string.cancel), onDismiss = { choosingMode = false }, onChoose = { mode ->
            choosingMode = false
            choose(mode)
        })
    }
    owedFromBefore?.let { owed ->
        FullaDialog(
            onDismissRequest = { owedFromBefore = null },
            title = { Text(stringResource(R.string.shared_pot_existing_title)) },
            text = { Text(stringResource(R.string.shared_pot_existing_text, view.memberName(owed.memberId), view.formats.money(owed.balanceMinor))) },
            confirmButton = { TextButton(onClick = { owedFromBefore = null; switchToShared(settle = true) }) { Text(stringResource(R.string.settle_now)) } },
            dismissButton = { TextButton(onClick = { owedFromBefore = null; switchToShared(settle = false) }) { Text(stringResource(R.string.keep_it)) } },
        )
    }
    if (splittingAgain) {
        FullaDialog(
            onDismissRequest = { splittingAgain = false },
            title = { Text(stringResource(R.string.money_between_members)) },
            text = { Text(stringResource(R.string.split_again_text)) },
            confirmButton = { TextButton(onClick = { splittingAgain = false; setMode(MoneyMode.SPLIT) }) { Text(stringResource(R.string.money_mode_split_value)) } },
            dismissButton = { TextButton(onClick = { splittingAgain = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    editing?.let { field ->
        val initial = when (field) {
            "name" -> h.name
            "currency" -> h.currency
            "period_start_day" -> h.periodStartDay.toString()
            else -> h.incomeShiftDay?.toString() ?: ""
        }
        EditDialog(stringResource(R.string.edit), initial, stringResource(when (field) {
            "name" -> R.string.household_name
            "currency" -> R.string.currency
            "period_start_day" -> R.string.period_start_day
            else -> R.string.income_shift_day
        }), onDismiss = { editing = null }, number = field.endsWith("day")) { value ->
            // Built through MonthStart.toPatch() so both period_start_day and
            // income_shift_day are always sent together, one of them
            // explicitly null, the same as the guide's own month-start step:
            // editing one field here must clear the other, not leave it at
            // whatever the household had before.
            val patch = when (field) {
                "name" -> buildJsonObject { put("name", value) }
                "currency" -> buildJsonObject { put("currency", value.uppercase()) }
                "period_start_day" -> {
                    val day = value.toIntOrNull()?.coerceIn(1, 28) ?: 1
                    (if (day <= 1) MonthStart.Calendar else MonthStart.Payday(day)).toPatch().toJsonObject()
                }
                else -> {
                    val day = value.toIntOrNull()?.coerceIn(2, 31)
                    (if (day == null) MonthStart.Calendar else MonthStart.SalaryNextMonth(day)).toPatch().toJsonObject()
                }
            }
            change { api -> ledger.updateHousehold(view.id, patch, api) }
        }
    }
}

// ── categories and accounts ──────────────────────────────────────────────────

@Composable
fun CategoriesSettings(view: HouseholdView, canEdit: Boolean, change: Change) {
    val ledger = LocalContainer.current.ledger
    val c = FullaTheme.colors
    /** The categories open, one over the other: a category, then one of its subcategories opened from it. */
    var editing by remember { mutableStateOf(listOf<JsonObject>()) }
    var creating by remember { mutableStateOf<AppliesTo?>(null) }
    /** The category a new subcategory goes under, added from that category's own dialog. */
    var creatingUnder by remember { mutableStateOf<io.github.sirallap.fulla.core.model.Category?>(null) }
    /** The category being deleted: asks where its entries go, then archives it. */
    var deleting by remember { mutableStateOf<io.github.sirallap.fulla.core.model.Category?>(null) }
    val order = compareBy<io.github.sirallap.fulla.core.model.Category>({ it.archived }, { it.sort })
    // A category deleted (archived) that nothing refers to any more is not listed: it is gone. One with history stays, archived.
    val referenced = remember(view.config, view.active) { runCatching { io.github.sirallap.fulla.core.categories.CategoryUse.referenced(view.config, view.active) }.getOrNull() }
    // One kind at a time, chosen at the top; the screen's button adds to the kind shown (docs/ux.md, pieces 1 and 11).
    var showing by remember { mutableStateOf(AppliesTo.EXPENSE) }
    var expanded by remember { mutableStateOf<String?>(null) }
    if (canEdit) io.github.sirallap.fulla.ui.components.ScreenPrimaryAction(stringResource(R.string.add_category)) { creating = showing }
    Column {
        io.github.sirallap.fulla.ui.components.SegmentedChoice(
            listOf(AppliesTo.EXPENSE to stringResource(R.string.spending), AppliesTo.INCOME to stringResource(R.string.income)),
            showing, { showing = it }, Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        run {
            val applies = showing
            val list = view.config.categories.filter { it.appliesTo == applies || (applies == AppliesTo.EXPENSE && it.appliesTo == AppliesTo.BOTH) }
                .filter { referenced == null || !io.github.sirallap.fulla.core.categories.CategoryUse.isGone(view.config, referenced, it.id) }
            // Each category, then its subcategories just under it.
            val tops = list.filter { cat -> cat.parentId == null || list.none { it.id == cat.parentId } }.sortedWith(order)
            // A card per category, as in the mockup: its tile, name and how many subcategories; open, its subcategories
            // as chips and "+ Subcategory" right there.
            for (top in tops) {
                val children = list.filter { it.parentId == top.id }.sortedWith(order)
                val open = expanded == top.id
                val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).clip(shape)
                        .background(if (top.archived) c.paper else c.paperHigh).border(1.dp, c.line, shape),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded = if (open) null else top.id }.heightIn(min = 56.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        io.github.sirallap.fulla.ui.entry.CategoryBadge(top)
                        Column(Modifier.weight(1f)) {
                            Text(top.name, style = FullaType.body, color = if (top.archived) c.inkMuted else c.ink)
                            if (top.archived) Text(stringResource(R.string.archived), style = FullaType.secondary, color = c.inkMuted)
                        }
                        if (children.isNotEmpty()) Text("${children.size}", style = FullaType.secondary, color = c.inkMuted)
                        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = c.inkMuted)
                    }
                    if (open) {
                        androidx.compose.foundation.layout.FlowRow(
                            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (canEdit) io.github.sirallap.fulla.ui.components.DetailChip(stringResource(R.string.edit),
                                { editing = listOfNotNull(bundleItem(view, Structure.CATEGORY, top.id)) }, icon = Icons.Outlined.Edit)
                            for (child in children) {
                                Chip(child.name + if (child.archived) " · " + stringResource(R.string.archived) else "", false,
                                    { if (canEdit) bundleItem(view, Structure.CATEGORY, child.id)?.let { editing = listOf(it) } })
                            }
                            if (canEdit && !top.archived) io.github.sirallap.fulla.ui.components.DetailChip(stringResource(R.string.add_subcategory),
                                { creatingUnder = top }, empty = true)
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.categories_archived_note), style = FullaType.secondary, color = c.inkMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }
    for (item in editing) androidx.compose.runtime.key((item["id"] as? JsonPrimitive)?.content) {
        val use = view.config.category((item["id"] as? JsonPrimitive)?.content)?.let {
            runCatching { io.github.sirallap.fulla.core.categories.CategoryUse.of(view.config, view.active, it.id) }.getOrNull()
        }
        val category = view.config.category((item["id"] as? JsonPrimitive)?.content)
        val close = { editing = editing.filter { it !== item } }
        StructureDialog(item, onDismiss = close, iconPicker = true,
            onDeleteClick = if (use != null && category != null && use.children == 0) ({ deleting = category }) else null,
            deleteBlockedText = if (use != null && use.children > 0) stringResource(R.string.delete_category_children) else null,
            // A category's own subcategories, each opened over it, and a new one added right there.
            extra = if (category != null && category.parentId == null) ({
                Text(stringResource(R.string.subcategories), style = FullaType.label, color = c.inkMuted, modifier = Modifier.padding(top = 8.dp))
                val children = view.config.categories.filter { it.parentId == category.id }
                    .filter { referenced == null || !io.github.sirallap.fulla.core.categories.CategoryUse.isGone(view.config, referenced, it.id) }
                    .sortedWith(order)
                for (child in children) {
                    ListRow(child.name, icon = CategoryIcons.of(child.icon), iconTint = c.category(child.colorIndex),
                        titleColor = if (child.archived) c.inkMuted else c.ink,
                        context = if (child.archived) stringResource(R.string.archived) else null,
                        onClick = { bundleItem(view, Structure.CATEGORY, child.id)?.let { editing = editing + it } })
                }
                if (!category.archived) ListRow(stringResource(R.string.add_subcategory), icon = Icons.Outlined.Add, onClick = { creatingUnder = category })
            }) else null,
        ) { updated ->
            change { api -> ledger.upsert(view.id, Structure.CATEGORY, updated, api) }
        }
    }
    creatingUnder?.let { parent ->
        val item = buildJsonObject {
            put("id", UUID.randomUUID().toString()); put("name", ""); put("applies_to", parent.appliesTo.key); put("icon", parent.icon)
            put("parent_id", parent.id)
            put("color_index", parent.colorIndex); put("sort", view.config.categories.size); put("archived", false)
        }
        StructureDialog(item, onDismiss = { creatingUnder = null }, iconPicker = true) { updated ->
            change { api -> ledger.upsert(view.id, Structure.CATEGORY, updated, api) }
        }
    }
    deleting?.let { category ->
        DeleteCategoryDialog(view, category, onDismiss = { deleting = null }) { moveTo ->
            val item = bundleItem(view, Structure.CATEGORY, category.id)
            if (item != null) change { api ->
                // Its entries go first, then it is archived: one without entries, budgets, fixed costs or fields leaves the list.
                if (moveTo != null) ledger.saveAll(view.id, io.github.sirallap.fulla.core.categories.CategoryUse.move(view.active, category.id, moveTo))
                ledger.upsert(view.id, Structure.CATEGORY, JsonObject(item + mapOf("archived" to JsonPrimitive(true))), api)
            }
        }
    }
    creating?.let { applies ->
        val item = buildJsonObject {
            put("id", UUID.randomUUID().toString()); put("name", ""); put("applies_to", applies.key); put("icon", "label")
            put("color_index", view.config.categories.size % 12); put("sort", view.config.categories.size); put("archived", false)
        }
        StructureDialog(item, onDismiss = { creating = null }, iconPicker = true) { updated ->
            change { api -> ledger.upsert(view.id, Structure.CATEGORY, updated, api) }
        }
    }
}

@Composable
fun AccountsSettings(view: HouseholdView, canEdit: Boolean, change: Change) {
    val ledger = LocalContainer.current.ledger
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    // A card per account with what it holds today, as the Saldos board draws them.
    val today = io.github.sirallap.fulla.ui.components.rememberToday()
    val balances = remember(view, today) { view.analytics.accountBalances(view.active, view.config.accounts, today) }
    Column(Modifier.padding(top = 8.dp)) {
        for (a in view.config.accounts.sortedWith(compareBy({ it.archived }, { it.sort }))) {
            io.github.sirallap.fulla.ui.components.ItemCard(dimmed = a.archived,
                onClick = if (canEdit) ({ editing = bundleItem(view, Structure.ACCOUNT, a.id) }) else null) {
                io.github.sirallap.fulla.ui.components.Spread(startShare = 0.5f, start = {
                    Column {
                        Text(a.name, style = FullaType.body, color = if (a.archived) c.inkMuted else c.ink)
                        val notes = listOfNotNull(stringResource(R.string.archived).takeIf { a.archived },
                            stringResource(R.string.round_up_title).takeIf { a.roundUp != null })
                        if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = FullaType.secondary, color = c.inkMuted)
                    }
                }, end = { AmountText(view.formats.money(balances[a.id] ?: 0L), color = if (a.archived) c.inkMuted else c.ink) })
            }
        }
    }
    if (canEdit) io.github.sirallap.fulla.ui.components.ScreenPrimaryAction(stringResource(R.string.add_account)) {
        editing = buildJsonObject {
            put("id", UUID.randomUUID().toString()); put("name", ""); put("type", "checking")
            put("opening_balance_minor", 0); put("sort", view.config.accounts.size); put("archived", false)
        }
    }
    editing?.let { item ->
        // A new account has no `opening_balance_date` key at all (built just
        // above); an existing one always does, since every account upsert on
        // the wire writes both balance fields (Wire.kt). That is what tells
        // the dialog whether it is creating or editing.
        val isNew = "opening_balance_date" !in item
        val itemId = (item["id"] as? JsonPrimitive)?.content
        StructureDialog(item, onDismiss = { editing = null }, iconPicker = false, formats = view.formats, isNewAccount = isNew,
            todayMinor = itemId?.takeIf { !isNew }?.let { balances[it] ?: 0L },
            otherAccounts = io.github.sirallap.fulla.core.roundup.RoundUps.destinations(view.config.accounts, itemId, view.config.accounts.firstOrNull { it.id == itemId }?.roundUp?.toAccountId),
            roundUpBackfill = itemId?.takeIf { view.config.account(it)?.roundUp != null }?.let { id ->
                val today = LocalDate.now()
                val from = RecurringPlanner.currentPeriodStart(view.config, view.active, today)
                val rows = io.github.sirallap.fulla.core.roundup.RoundUps.backfill(view.rows.map { it.transaction }, id, from, today, view.config)
                RoundUpBackfill(rows.size, rows.sumOf { it.amountMinor }) { done ->
                    scope.launch { done(ledger.applyRoundUpBackfill(view.id, id, from, today)) }
                }
            }) { updated ->
            change { api -> ledger.upsert(view.id, Structure.ACCOUNT, updated, api) }
        }
    }
}

internal fun bundleItem(view: HouseholdView, kind: Structure, id: String): JsonObject? =
    Wire.list(view.state.bundle[kind.bundleKey]).firstOrNull { (it["id"] as? JsonPrimitive)?.content == id }

/**
 * An account's opening balance from what somebody typed: blank is 0, a minus
 * sign is a debt (a credit card, an overdraft), and text that is not an amount
 * is null, which both writers of an opening balance ([StructureDialog]'s
 * balance field and the guide's opening-balances step) say out loud instead of
 * keeping the old balance as if it had been typed. Either decimal key is read
 * as the decimal, whatever language the app is in.
 */
internal fun parseOpeningBalance(text: String, formats: io.github.sirallap.fulla.ui.Formats): Long? =
    io.github.sirallap.fulla.core.guide.OpeningBalances.parse(text, formats.currency, formats.decimalStyle)

/** The purchases of this period that a round-up already set up has not rounded yet, and the button that rounds them. */
internal class RoundUpBackfill(val count: Int, val totalMinor: Long, val write: ((Int) -> Unit) -> Unit)

/** Name, archive, (for categories) icon, and (for accounts) an opening balance. Nothing is ever deleted: archived things keep their history. */
@Composable
private fun StructureDialog(
    item: JsonObject,
    onDismiss: () -> Unit,
    iconPicker: Boolean,
    formats: io.github.sirallap.fulla.ui.Formats? = null,
    isNewAccount: Boolean = false,
    /** The accounts a round-up can go to (an account being edited). */
    otherAccounts: List<io.github.sirallap.fulla.core.model.Account> = emptyList(),
    /** What a round-up already saved here could still write for this period's purchases (count and amount to move), and how to write it. */
    roundUpBackfill: RoundUpBackfill? = null,
    /** Deleting, for what can be deleted: it closes this dialog and the caller asks what the deletion needs. [deleteBlockedText] says why it cannot be done now. */
    onDeleteClick: (() -> Unit)? = null,
    deleteBlockedText: String? = null,
    /** An account's balance today, said under its opening balance (the account board). */
    todayMinor: Long? = null,
    /** What else belongs in this dialog, above the archive switch: a category's subcategories. */
    extra: (@Composable () -> Unit)? = null,
    onSave: (JsonObject) -> Unit,
) {
    var name by remember { mutableStateOf((item["name"] as? JsonPrimitive)?.content ?: "") }
    var archived by remember { mutableStateOf((item["archived"] as? JsonPrimitive)?.content == "true") }
    var icon by remember { mutableStateOf((item["icon"] as? JsonPrimitive)?.content ?: "label") }
    val openingBalanceMinor = (item["opening_balance_minor"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
    // The field holds the amount and the switch under it says it is owed: a phone's decimal keypad has no minus key.
    var debt by remember { mutableStateOf(openingBalanceMinor < 0) }
    var balanceText by remember { mutableStateOf(formats?.plain(kotlin.math.abs(openingBalanceMinor)) ?: "") }
    // The stored date, if this account already has one. An account being
    // edited that has never had one (existing account, null date) defaults to
    // today only for display; it is not sent unless the person actually
    // touches the balance or the date (see `balanceTouched` below), so
    // editing just the name never turns a legacy "count everything" account
    // into one dated today.
    val storedDate = (item["opening_balance_date"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content?.let(LocalDate::parse)
    var balanceDate by remember { mutableStateOf(storedDate ?: LocalDate.now()) }
    var pickingBalanceDate by remember { mutableStateOf(false) }
    // Whether this edit should touch balance/date at all: always for a new
    // account, and for an existing one only once the person actually changes
    // the amount or picks a date -- otherwise the payload omits both keys,
    // and fulla.save_account (and LocalHousehold.upsert) leave the stored
    // values exactly as they were, the same "absent keeps its value" rule as
    // opening_balance_date already had.
    var balanceTouched by remember { mutableStateOf(isNewAccount) }
    // Round-up saving, as a bank offers it: per account, never cash, and nothing to round in a currency with no cents.
    val showRoundUp = formats != null && (item["type"] as? JsonPrimitive)?.content != "cash" && formats.currency.minorUnits > 0
    var roundTo by remember { mutableStateOf((item["round_up_to"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content) }
    var roundMultiplier by remember { mutableStateOf((item["round_up_multiplier"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content?.toIntOrNull() ?: 2) }
    var roundTouched by remember { mutableStateOf(false) }
    // What is in the field, as an amount; null when it is not one, which is said under the field and stops Save.
    val balanceValue = formats?.let { io.github.sirallap.fulla.core.guide.OpeningBalances.parse(balanceText, it.currency, it.decimalStyle, debt) }
    val balanceInvalid = formats != null && balanceValue == null
    // A form as a screen of its own (docs/ux.md, piece 2): the icons of a category are many, and Save stays where it is.
    io.github.sirallap.fulla.ui.components.FormScreen(
        title = stringResource(R.string.edit),
        onClose = onDismiss,
        primaryLabel = stringResource(R.string.save),
        primaryEnabled = name.isNotBlank() && !balanceInvalid,
        onPrimary = {
            onSave(JsonObject(item + mapOf("name" to JsonPrimitive(name.trim()), "archived" to JsonPrimitive(archived)) +
                (if (iconPicker) mapOf("icon" to JsonPrimitive(icon)) else emptyMap()) +
                (if (showRoundUp && roundTouched) mapOf(
                    "round_up_to" to (roundTo?.let { JsonPrimitive(it) } ?: JsonNull),
                    "round_up_multiplier" to (if (roundTo != null) JsonPrimitive(roundMultiplier) else JsonNull),
                ) else emptyMap()) +
                (if (balanceValue != null && balanceTouched) mapOf(
                    "opening_balance_minor" to JsonPrimitive(balanceValue),
                    "opening_balance_date" to JsonPrimitive(balanceDate.toString()),
                ) else emptyMap())))
            onDismiss()
        },
        neutral = if (onDeleteClick != null && deleteBlockedText == null) ({
            io.github.sirallap.fulla.ui.components.DeleteButton(onClick = { onDeleteClick(); onDismiss() })
        }) else null,
    ) {
                OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name)) }, singleLine = true)
                if (formats != null) {
                    // The amount and its date one above the other: side by side, a large font squeezed the field.
                    OutlinedTextField(balanceText, { balanceText = it; balanceTouched = true }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.opening_balance)) }, isError = balanceInvalid,
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    io.github.sirallap.fulla.ui.components.DetailChip(formats.day(balanceDate), { pickingBalanceDate = true },
                        icon = Icons.Outlined.Event)
                    if (balanceInvalid) Text(stringResource(R.string.opening_balance_invalid),
                        style = FullaType.secondary, color = FullaTheme.colors.danger)
                    Text(stringResource(R.string.opening_balance_help) + (todayMinor?.let { " " + stringResource(R.string.account_today, formats.money(it)) } ?: ""),
                        style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
                    SwitchRow(stringResource(R.string.opening_balance_debt), stringResource(R.string.opening_balance_debt_help), debt) {
                        debt = it; balanceTouched = true
                    }
                }
                if (showRoundUp && formats != null) {
                    if (otherAccounts.isEmpty()) {
                        Text(stringResource(R.string.round_up_needs_account), style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
                    } else {
                        SwitchRow(stringResource(R.string.round_up_title), stringResource(R.string.round_up_help), roundTo != null) { on ->
                            roundTo = if (on) (otherAccounts.firstOrNull { it.type == io.github.sirallap.fulla.core.model.AccountType.SAVINGS } ?: otherAccounts.first()).id else null
                            roundTouched = true
                        }
                        if (roundTo != null) io.github.sirallap.fulla.ui.components.Revealed {
                            Text(stringResource(R.string.round_up_multiplier), style = FullaType.label, color = FullaTheme.colors.inkMuted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (m in io.github.sirallap.fulla.core.roundup.RoundUps.MULTIPLIERS) {
                                    Chip("×$m", m == roundMultiplier, { roundMultiplier = m; roundTouched = true })
                                }
                            }
                            Text(stringResource(R.string.round_up_to), style = FullaType.label, color = FullaTheme.colors.inkMuted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (a in otherAccounts) Chip(a.name, a.id == roundTo, { roundTo = a.id; roundTouched = true })
                            }
                            // The bank's own example, with this multiplier: a coffee of 1,50.
                            val unit = (0 until formats.currency.minorUnits).fold(1L) { acc, _ -> acc * 10 }
                            val coffee = unit * 3 / 2
                            Text(stringResource(R.string.round_up_example, formats.money(coffee), formats.money(unit * 2),
                                formats.money(io.github.sirallap.fulla.core.roundup.RoundUps.savingMinor(coffee, formats.currency, roundMultiplier)), roundMultiplier),
                                style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
                            // Only for what is saved: the button writes what the account already says.
                            if (roundUpBackfill != null && !roundTouched) {
                                var done by remember { mutableStateOf<Int?>(null) }
                                if (done != null) {
                                    Text(stringResource(R.string.round_up_backfill_done, done!!), style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
                                } else if (roundUpBackfill.count == 0) {
                                    Text(stringResource(R.string.round_up_backfill_none), style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
                                } else {
                                    Text(stringResource(R.string.round_up_backfill_info, roundUpBackfill.count, formats.money(roundUpBackfill.totalMinor)),
                                        style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
                                    TextButton(onClick = { roundUpBackfill.write { done = it } }) { Text(stringResource(R.string.round_up_backfill)) }
                                }
                            }
                        }
                    }
                }
                if (iconPicker) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((key, vector) in CategoryIcons.all) {
                            val selected = key == icon
                            Box(Modifier.size(40.dp).clip(CircleShape)
                                .background(if (selected) FullaTheme.colors.highlight else FullaTheme.colors.paper)
                                .then(Modifier.padding(8.dp))) {
                                androidx.compose.material3.IconButton(onClick = { icon = key }, modifier = Modifier.size(24.dp)) {
                                    androidx.compose.material3.Icon(vector, key, tint = if (selected) FullaTheme.colors.onHighlight else FullaTheme.colors.ink)
                                }
                            }
                        }
                    }
                }
                extra?.invoke()
                SwitchRow(stringResource(R.string.archive), stringResource(R.string.archive_help), archived) { archived = it }
                if (deleteBlockedText != null) {
                    Text(deleteBlockedText, style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
                }
    }
    if (pickingBalanceDate) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = balanceDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        androidx.compose.material3.DatePickerDialog(onDismissRequest = { pickingBalanceDate = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    balanceDate = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    balanceTouched = true
                }
                pickingBalanceDate = false
            }) { Text(stringResource(R.string.done)) }
        }) { androidx.compose.material3.DatePicker(state) }
    }
}

// ── budgets ──────────────────────────────────────────────────────────────────

@Composable
fun BudgetsSettings(view: HouseholdView, canEdit: Boolean, change: Change) {
    val ledger = LocalContainer.current.ledger
    val f = view.formats
    val c = FullaTheme.colors
    val current = remember(view.config.household) { f.currentPeriod() }
    // null: the default for every month; otherwise one particular month.
    var month by remember { mutableStateOf<java.time.YearMonth?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    val defaults = view.config.budgets.filter { it.period == null }.associateBy { it.categoryId }
    val overrides = month?.let { m -> view.config.budgets.filter { it.period == m.toString() }.associateBy { it.categoryId } }.orEmpty()
    var picking by remember { mutableStateOf(false) }
    val period = month ?: current
    val spent = remember(view, period) {
        view.analytics.budgetSpend(view.active, period, view.config.trips).associate { it.categoryId to it.amountMinor }
    }
    val tops = view.config.categories.filter { !it.archived && it.appliesTo != AppliesTo.INCOME && it.parentId == null }
    // A month of its own takes its own amount, else the one for every month.
    fun budgetOf(id: String) = if (month == null) defaults[id] else overrides[id] ?: defaults[id]
    val budgeted = tops.filter { (budgetOf(it.id)?.amountMinor ?: 0L) > 0L }
    if (canEdit) io.github.sirallap.fulla.ui.components.ScreenPrimaryAction(stringResource(R.string.budget_other_category)) { picking = true }
    // As in the mockup: the month's total in a dark card, then one card per budgeted category.
    Column {
        val total = budgeted.sumOf { budgetOf(it.id)!!.amountMinor }
        val used = budgeted.sumOf { spent[it.id] ?: 0L }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(c.ink).padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            io.github.sirallap.fulla.ui.components.Spread(startShare = 0.5f, start = {
                Column {
                    Text(f.period(period), style = FullaType.secondary, color = c.paper.copy(alpha = 0.75f))
                    Text(stringResource(R.string.budget_of, f.money(used), f.money(total)), style = FullaType.title, color = c.paper)
                }
            }, end = {
                Text(if (month == null) stringResource(R.string.budget_every_month_same) else stringResource(R.string.budget_only_month, f.period(period)),
                    style = FullaType.secondary, color = c.paper.copy(alpha = 0.75f))
            })
        }
        if (budgeted.isEmpty()) {
            Text(stringResource(R.string.budget_none_yet), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(20.dp))
        }
        for (cat in budgeted) {
            val b = budgetOf(cat.id)!!
            val out = spent[cat.id] ?: 0L
            val inherited = month != null && overrides[cat.id] == null
            io.github.sirallap.fulla.ui.components.ItemCard {
                io.github.sirallap.fulla.ui.components.Spread(startShare = 0.5f, start = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(CategoryIcons.of(cat.icon), null, tint = c.category(cat.colorIndex))
                        Text(cat.name, style = FullaType.body, color = c.ink)
                    }
                }, end = { AmountBox(f.money(b.amountMinor), if (canEdit) ({ editing = cat.id }) else null) })
                io.github.sirallap.fulla.ui.components.ProgressLine(out.toFloat() / b.amountMinor, c.category(cat.colorIndex), over = out > b.amountMinor)
                Text(
                    if (out <= b.amountMinor) stringResource(R.string.budget_spent_left, f.money(out), f.money(b.amountMinor - out))
                    else stringResource(R.string.budget_spent_over, f.money(out), f.money(out - b.amountMinor)),
                    style = FullaType.label, color = if (out > b.amountMinor) c.danger else c.inkMuted,
                )
                if (inherited) Text(stringResource(R.string.from_default), style = FullaType.label, color = c.inkMuted)
            }
        }
        if (canEdit) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (month == null) {
                io.github.sirallap.fulla.ui.components.SecondaryButton(stringResource(R.string.budget_this_month_only), { month = current })
            } else {
                Text(stringResource(R.string.month_budget_help), style = FullaType.secondary, color = c.inkMuted)
                io.github.sirallap.fulla.ui.components.SecondaryButton(stringResource(R.string.copy_budgets_from, f.period(period.minusMonths(1))), {
                    val from = period.minusMonths(1).toString()
                    change { api ->
                        val next = if (api == null) LocalHousehold.copyBudgets(view.state.bundle, from, period.toString())
                        else api.budgetCopy(view.id, from, period.toString())
                        ledger.storeConfig(view.id, next)
                    }
                })
                io.github.sirallap.fulla.ui.components.SecondaryButton(stringResource(R.string.budget_back_every_month), { month = null })
            }
        }
    }
    if (picking) {
        io.github.sirallap.fulla.ui.components.PickSheet(stringResource(R.string.budget_other_category), onDismiss = { picking = false }) {
            for (cat in tops - budgeted.toSet()) {
                io.github.sirallap.fulla.ui.components.PickRow(cat.name, false, { picking = false; editing = cat.id },
                    start = { io.github.sirallap.fulla.ui.entry.CategoryBadge(cat) })
            }
        }
    }
    editing?.let { catId ->
        val own = if (month == null) defaults[catId] else overrides[catId]
        EditDialog(view.categoryName(catId) ?: "", own?.let { f.plain(it.amountMinor) } ?: "",
            stringResource(if (month == null) R.string.monthly_budget else R.string.budget_for_month),
            onDismiss = { editing = null }, number = true,
            enabledFor = { (MoneyParser.parseTyped(it, f.currency, f.decimalStyle) ?: -1) >= 0 }) { text ->
            val minor = MoneyParser.parseTyped(text, f.currency, f.decimalStyle) ?: return@EditDialog
            val item = buildJsonObject {
                put("id", own?.id ?: UUID.randomUUID().toString()); put("category_id", catId)
                put("period", month?.toString()); put("amount_minor", minor)
            }
            change { api -> ledger.upsert(view.id, Structure.BUDGET, item, api) }
        }
    }
}

/** An amount that looks like the field it opens (the budgets' cards, as in the mockup). */
@Composable
private fun AmountBox(text: String, onClick: (() -> Unit)?) {
    val c = FullaTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    Box(
        Modifier.clip(shape).background(c.paper).border(1.dp, c.line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterEnd,
    ) { AmountText(text) }
}

// ── import ───────────────────────────────────────────────────────────────────

@Composable
fun ImportSettings(view: HouseholdView, change: Change) {
    ImportScreen(view, change)
}

// ── appearance ───────────────────────────────────────────────────────────────

@Composable
fun AppearanceSettings() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    // The first frame, before the stored settings arrive, draws the defaults rather than nothing.
    val s = settings ?: io.github.sirallap.fulla.data.prefs.Settings()
    Column {
        Section(stringResource(R.string.theme))
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((mode, label) in listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark)) {
                Chip(stringResource(label), s.theme == mode, { scope.launch { container.settings.setTheme(mode) } })
            }
        }
        val activity = LocalContext.current as? androidx.fragment.app.FragmentActivity
        var languagePickerOpen by remember { mutableStateOf(false) }
        Section(stringResource(R.string.language))
        ListRow(stringResource(R.string.app_language),
            context = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { it.titlecase() },
            detail = stringResource(R.string.app_language_text), onClick = { languagePickerOpen = true })
        if (languagePickerOpen && activity != null) {
            val current = io.github.sirallap.fulla.core.text.AppLanguage.preselectFor(java.util.Locale.getDefault().toLanguageTag())
            androidx.compose.ui.window.Dialog(onDismissRequest = { languagePickerOpen = false }) {
                Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(FullaTheme.colors.paper).padding(20.dp)) {
                    io.github.sirallap.fulla.ui.language.LanguageChoices(current, onChoose = { chosen ->
                        languagePickerOpen = false
                        io.github.sirallap.fulla.ui.LanguageApplier.set(activity, chosen)
                        scope.launch {
                            container.settings.setLanguageChosen(true)
                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) activity.recreate()
                        }
                    })
                }
            }
        }
        Section(stringResource(R.string.accent))
        Text(stringResource(R.string.accent_text), style = FullaType.secondary, color = FullaTheme.colors.inkMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (a in io.github.sirallap.fulla.core.design.Accent.entries) {
                val name = stringResource(accentName(a))
                Box(Modifier.size(52.dp).clip(CircleShape).clickable(onClickLabel = name) { scope.launch { container.settings.setAccent(a) } }
                    .semantics { contentDescription = name; selected = s.accent == a }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(a.fill)), contentAlignment = Alignment.Center) {
                        if (s.accent == a) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Check, null,
                            tint = androidx.compose.ui.graphics.Color(a.onFill), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Section(stringResource(R.string.money_colours))
        Text(stringResource(R.string.money_colours_text), style = FullaType.secondary, color = FullaTheme.colors.inkMuted, modifier = Modifier.padding(20.dp))
        for (p in MoneyPalette.entries) {
            val colors = p.of(FullaTheme.colors.isDark)
            ListRow(
                title = stringResource(paletteName(p)),
                start = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(colors.inSurface)))
                        Box(Modifier.size(20.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(colors.outSurface)))
                    }
                },
                end = { if (s.palette == p) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Check, null, tint = FullaTheme.colors.accent) },
                onClick = { scope.launch { container.settings.setPalette(p) } },
            )
        }
        Section(stringResource(R.string.security))
        SwitchRow(stringResource(R.string.lock), stringResource(R.string.lock_text), s.lock) { on -> scope.launch { container.settings.setLock(on) } }
        Section(stringResource(R.string.widget_section))
        Text(stringResource(R.string.widget_change_help), style = FullaType.secondary, color = FullaTheme.colors.inkMuted,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
        SwitchRow(stringResource(R.string.widget_hide_amounts), stringResource(R.string.widget_hide_amounts_text), s.widgetHideAmounts) { on ->
            scope.launch { container.settings.setWidgetHideAmounts(on) }
        }
    }
}

private fun accentName(a: io.github.sirallap.fulla.core.design.Accent): Int = when (a) {
    io.github.sirallap.fulla.core.design.Accent.GOLD -> R.string.accent_gold
    io.github.sirallap.fulla.core.design.Accent.LAVENDER -> R.string.accent_lavender
    io.github.sirallap.fulla.core.design.Accent.SEA -> R.string.accent_sea
    io.github.sirallap.fulla.core.design.Accent.SAGE -> R.string.accent_sage
    io.github.sirallap.fulla.core.design.Accent.ROSE -> R.string.accent_rose
    io.github.sirallap.fulla.core.design.Accent.EMBER -> R.string.accent_ember
    io.github.sirallap.fulla.core.design.Accent.PLATINUM -> R.string.accent_platinum
}

private fun paletteName(p: MoneyPalette): Int = when (p.name) {
    "INK" -> R.string.palette_ink
    "AMBER" -> R.string.palette_amber
    "SEA" -> R.string.palette_sea
    "FOREST" -> R.string.palette_forest
    else -> R.string.palette_classic
}

// ── about ────────────────────────────────────────────────────────────────────

@Composable
fun AboutSettings(onUpdate: () -> Unit = {}) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var notices by remember { mutableStateOf<String?>(null) }
    // The notes of every version are in the app (docs/releases, bundled): this one's, and the earlier ones in a drop-down.
    val versions = remember { ReleaseHistory.versions(context.assets.list("releases").orEmpty().toList()) }
    var reading by remember { mutableStateOf<String?>(null) }
    var showEarlier by remember { mutableStateOf(false) }
    Column {
        ListRow(stringResource(R.string.app_name), context = stringResource(R.string.version, BuildConfig.VERSION_NAME))
        if (BuildConfig.VERSION_NAME in versions) {
            ListRow(stringResource(R.string.whats_new), context = stringResource(R.string.whats_new_text), onClick = { reading = BuildConfig.VERSION_NAME })
        }
        val earlier = versions.filter { it != BuildConfig.VERSION_NAME }
        if (earlier.isNotEmpty()) {
            ListRow(stringResource(R.string.earlier_versions, earlier.size), context = stringResource(R.string.earlier_versions_text),
                end = { androidx.compose.material3.Icon(if (showEarlier) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = FullaTheme.colors.inkMuted) },
                onClick = { showEarlier = !showEarlier })
            if (showEarlier) for (v in earlier) ListRow(stringResource(R.string.version, v), onClick = { reading = v })
        }
        SwitchRow(stringResource(R.string.update_check), stringResource(R.string.update_check_text),
            checked = settings?.checkForUpdates ?: true,
            onChange = { on -> scope.launch { container.settings.setCheckForUpdates(on) } })
        CheckUpdatesRow(onUpdate)
        ListRow(stringResource(R.string.licence), context = stringResource(R.string.licence_text))
        ListRow(stringResource(R.string.privacy), context = stringResource(R.string.privacy_text))
        ListRow(stringResource(R.string.third_party_licences), context = stringResource(R.string.third_party_licences_text), onClick = {
            notices = context.assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name ->
                "── $name ──\n" + context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }
        })
    }
    reading?.let { version ->
        val notes = remember(version) { runCatching { context.assets.open("releases/$version.md").bufferedReader().use { it.readText() } }.getOrDefault("") }
        FullaDialog(
            onDismissRequest = { reading = null },
            title = { Text(stringResource(R.string.version, version)) },
            text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { ReleaseNotesText(notes) } },
            confirmButton = { TextButton(onClick = { reading = null }) { Text(stringResource(R.string.done)) } },
        )
    }
    notices?.let { text ->
        FullaDialog(
            onDismissRequest = { notices = null },
            title = { Text(stringResource(R.string.third_party_licences)) },
            text = {
                Text(text, style = FullaType.secondary, modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { notices = null }) { Text(stringResource(R.string.done)) } },
        )
    }
}

/**
 * Deleting a category. Nothing is physically deleted, so it is archived; what
 * makes it vanish from the list is that nothing refers to it any more. So the
 * entries that use it are moved first, to a category the person picks (the one
 * above it, for a subcategory, is already picked). What cannot be moved here
 * (a budget, a fixed cost, a field) keeps it in the list, archived, and the
 * dialog says so before it is confirmed.
 */
@Composable
private fun DeleteCategoryDialog(
    view: HouseholdView,
    category: io.github.sirallap.fulla.core.model.Category,
    onDismiss: () -> Unit,
    onConfirm: (moveTo: String?) -> Unit,
) {
    val use = remember(category.id) { io.github.sirallap.fulla.core.categories.CategoryUse.of(view.config, view.active, category.id) }
    val targets = remember(category.id) { io.github.sirallap.fulla.core.categories.CategoryUse.targetsFor(view.config, view.active, category.id) }
    val others = use.budgets + use.recurring + use.fields
    var target by remember { mutableStateOf(if (use.rows > 0) targets.firstOrNull { it.id == category.parentId }?.id else null) }
    val c = FullaTheme.colors
    FullaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (others > 0) R.string.archive_item_title else R.string.delete_item_title, category.name)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (use.rows == 0 && others == 0) Text(stringResource(R.string.delete_category_unused), style = FullaType.secondary)
                if (use.rows > 0) {
                    Text(stringResource(R.string.delete_category_move, use.rows), style = FullaType.secondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in targets) Chip(t.name, t.id == target, { target = t.id })
                    }
                    if (targets.isEmpty()) Text(stringResource(R.string.delete_category_no_target), style = FullaType.secondary, color = c.warning)
                }
                if (others > 0) Text(stringResource(R.string.delete_category_kept), style = FullaType.secondary, color = c.inkMuted)
            }
        },
        confirmButton = {
            TextButton(enabled = use.rows == 0 || target != null, onClick = { onConfirm(target); onDismiss() },
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = if (others > 0 && use.rows == 0) c.accent else c.danger)) {
                Text(stringResource(when {
                    use.rows > 0 -> R.string.delete_and_move
                    others > 0 -> R.string.archive
                    else -> R.string.delete
                }))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
