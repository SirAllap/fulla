// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

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
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.remote.FullaApi
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.InviteLink
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.balance.Balances
import io.github.sirallap.fulla.core.balance.MemberBalance
import io.github.sirallap.fulla.core.balance.SettlementPlanner
import io.github.sirallap.fulla.core.design.MoneyPalette
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
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text))
                extra?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()); onDismiss() }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) } },
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
        ListRow(stringResource(R.string.household_name), context = h.name, onClick = if (canEdit) ({ editing = "name" }) else null)
        ListRow(stringResource(R.string.currency), context = h.currency,
            detail = stringResource(R.string.currency_change_note),
            onClick = if (me != null && Permissions.canChangeCurrencyOrLimit(me)) ({ editing = "currency" }) else null)
        ListRow(stringResource(R.string.period_start_day), context = stringResource(R.string.period_start_day_value, h.periodStartDay),
            detail = stringResource(R.string.period_start_day_help), onClick = if (canEdit) ({ editing = "period_start_day" }) else null)
        ListRow(stringResource(R.string.income_shift_day), context = h.incomeShiftDay?.let { stringResource(R.string.income_shift_day_value, it) }
            ?: stringResource(R.string.off), detail = stringResource(R.string.income_shift_day_help),
            onClick = if (canEdit) ({ editing = "income_shift_day" }) else null)
        ListRow(stringResource(R.string.money_between_members),
            context = stringResource(if (shared) R.string.shared_pot_card_title else R.string.money_mode_split_value),
            onClick = if (canEdit && !switching) ({ choosingMode = true }) else null)
        modeError?.let { Text(it, style = FullaType.secondary, color = FullaTheme.colors.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
    }
    if (choosingMode) {
        MoneyModeSheet(h.moneyMode, dismissLabel = stringResource(R.string.cancel), onDismiss = { choosingMode = false }, onChoose = { mode ->
            choosingMode = false
            choose(mode)
        })
    }
    owedFromBefore?.let { owed ->
        AlertDialog(
            onDismissRequest = { owedFromBefore = null },
            title = { Text(stringResource(R.string.shared_pot_existing_title)) },
            text = { Text(stringResource(R.string.shared_pot_existing_text, view.memberName(owed.memberId), view.formats.money(owed.balanceMinor))) },
            confirmButton = { TextButton(onClick = { owedFromBefore = null; switchToShared(settle = true) }) { Text(stringResource(R.string.settle_now)) } },
            dismissButton = { TextButton(onClick = { owedFromBefore = null; switchToShared(settle = false) }) { Text(stringResource(R.string.keep_it)) } },
        )
    }
    if (splittingAgain) {
        AlertDialog(
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
            val patch = when (field) {
                "name" -> buildJsonObject { put("name", value) }
                "currency" -> buildJsonObject { put("currency", value.uppercase()) }
                "period_start_day" -> buildJsonObject { put("period_start_day", value.toIntOrNull()?.coerceIn(1, 28) ?: 1) }
                else -> buildJsonObject { put("income_shift_day", value.toIntOrNull()?.takeIf { it in 2..28 }) }
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
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var creating by remember { mutableStateOf<AppliesTo?>(null) }
    Column {
        for ((title, applies) in listOf(R.string.spending to AppliesTo.EXPENSE, R.string.income to AppliesTo.INCOME)) {
            Section(stringResource(title))
            val list = view.config.categories.filter { it.appliesTo == applies || (applies == AppliesTo.EXPENSE && it.appliesTo == AppliesTo.BOTH) }
            for (cat in list.sortedWith(compareBy({ it.archived }, { it.sort }))) {
                ListRow(cat.name, icon = CategoryIcons.of(cat.icon), iconTint = c.category(cat.colorIndex),
                    indent = if (cat.parentId != null) 24.dp else 0.dp,
                    titleColor = if (cat.archived) c.inkMuted else c.ink,
                    context = if (cat.archived) stringResource(R.string.archived) else null,
                    onClick = if (canEdit) ({ editing = bundleItem(view, Structure.CATEGORY, cat.id) }) else null)
            }
            if (canEdit) ListRow(stringResource(R.string.add_category), icon = Icons.Outlined.Add, onClick = { creating = applies })
        }
    }
    editing?.let { item ->
        StructureDialog(item, onDismiss = { editing = null }, iconPicker = true) { updated ->
            change { api -> ledger.upsert(view.id, Structure.CATEGORY, updated, api) }
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
    val c = FullaTheme.colors
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    Column {
        for (a in view.config.accounts.sortedWith(compareBy({ it.archived }, { it.sort }))) {
            ListRow(a.name, titleColor = if (a.archived) c.inkMuted else c.ink,
                context = if (a.archived) stringResource(R.string.archived) else null,
                onClick = if (canEdit) ({ editing = bundleItem(view, Structure.ACCOUNT, a.id) }) else null)
        }
        if (canEdit) ListRow(stringResource(R.string.add_account), icon = Icons.Outlined.Add, onClick = {
            editing = buildJsonObject {
                put("id", UUID.randomUUID().toString()); put("name", ""); put("type", "checking")
                put("opening_balance_minor", 0); put("sort", view.config.accounts.size); put("archived", false)
            }
        })
    }
    editing?.let { item ->
        StructureDialog(item, onDismiss = { editing = null }, iconPicker = false) { updated ->
            change { api -> ledger.upsert(view.id, Structure.ACCOUNT, updated, api) }
        }
    }
}

private fun bundleItem(view: HouseholdView, kind: Structure, id: String): JsonObject? =
    Wire.list(view.state.bundle[kind.bundleKey]).firstOrNull { (it["id"] as? JsonPrimitive)?.content == id }

/** Name, archive and (for categories) icon. Nothing is ever deleted: archived things keep their history. */
@Composable
private fun StructureDialog(item: JsonObject, onDismiss: () -> Unit, iconPicker: Boolean, onSave: (JsonObject) -> Unit) {
    var name by remember { mutableStateOf((item["name"] as? JsonPrimitive)?.content ?: "") }
    var archived by remember { mutableStateOf((item["archived"] as? JsonPrimitive)?.content == "true") }
    var icon by remember { mutableStateOf((item["icon"] as? JsonPrimitive)?.content ?: "label") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
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
                SwitchRow(stringResource(R.string.archive), stringResource(R.string.archive_help), archived) { archived = it }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(JsonObject(item + mapOf("name" to JsonPrimitive(name.trim()), "archived" to JsonPrimitive(archived)) +
                    (if (iconPicker) mapOf("icon" to JsonPrimitive(icon)) else emptyMap())))
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
    Column {
        Text(stringResource(R.string.budgets_text), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(20.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.every_month), month == null, { month = null })
            Chip(f.period(current), month == current, { month = current })
            Chip(f.period(current.plusMonths(1)), month == current.plusMonths(1), { month = current.plusMonths(1) })
        }
        month?.let { m ->
            Text(stringResource(R.string.month_budget_help), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(20.dp))
            if (canEdit) ListRow(stringResource(R.string.copy_budgets_from, f.period(m.minusMonths(1))), icon = Icons.Outlined.ContentCopy, onClick = {
                val from = m.minusMonths(1).toString()
                change { api ->
                    val next = if (api == null) LocalHousehold.copyBudgets(view.state.bundle, from, m.toString())
                    else api.budgetCopy(view.id, from, m.toString())
                    ledger.storeConfig(view.id, next)
                }
            })
        }
        for (cat in view.config.categories.filter { !it.archived && it.appliesTo != AppliesTo.INCOME && it.parentId == null }) {
            val own = if (month == null) defaults[cat.id] else overrides[cat.id]
            val inherited = if (month != null && own == null) defaults[cat.id] else null
            ListRow(cat.name, icon = CategoryIcons.of(cat.icon), iconTint = c.category(cat.colorIndex),
                context = inherited?.let { stringResource(R.string.from_default) },
                onClick = if (canEdit) ({ editing = cat.id }) else null,
                end = { AmountText((own ?: inherited)?.let { f.money(it.amountMinor) } ?: "—", color = if (own != null) c.ink else c.inkMuted) })
        }
    }
    editing?.let { catId ->
        val own = if (month == null) defaults[catId] else overrides[catId]
        EditDialog(view.categoryName(catId) ?: "", own?.let { f.plain(it.amountMinor) } ?: "",
            stringResource(if (month == null) R.string.monthly_budget else R.string.budget_for_month),
            onDismiss = { editing = null }, number = true) { text ->
            val minor = MoneyParser.parse(text, f.currency, f.decimalStyle) ?: return@EditDialog
            val item = buildJsonObject {
                put("id", own?.id ?: UUID.randomUUID().toString()); put("category_id", catId)
                put("period", month?.toString()); put("amount_minor", minor)
            }
            change { api -> ledger.upsert(view.id, Structure.BUDGET, item, api) }
        }
    }
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
fun AboutSettings() {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var notices by remember { mutableStateOf<String?>(null) }
    Column {
        ListRow(stringResource(R.string.app_name), context = stringResource(R.string.version, BuildConfig.VERSION_NAME))
        SwitchRow(stringResource(R.string.update_check), stringResource(R.string.update_check_text),
            checked = settings?.checkForUpdates ?: true,
            onChange = { on -> scope.launch { container.settings.setCheckForUpdates(on) } })
        var checking by remember { mutableStateOf(false) }
        var checked by remember { mutableStateOf<io.github.sirallap.fulla.AppContainer.UpdateCheckResult?>(null) }
        val result = when (checked) {
            io.github.sirallap.fulla.AppContainer.UpdateCheckResult.FOUND -> stringResource(R.string.update_found_text)
            io.github.sirallap.fulla.AppContainer.UpdateCheckResult.UP_TO_DATE -> stringResource(R.string.update_up_to_date)
            io.github.sirallap.fulla.AppContainer.UpdateCheckResult.FAILED -> stringResource(R.string.update_check_failed)
            io.github.sirallap.fulla.AppContainer.UpdateCheckResult.SKIPPED -> stringResource(R.string.update_test_build)
            null -> null
        }
        ListRow(stringResource(if (checking) R.string.update_checking else R.string.update_check_now), context = result,
            onClick = if (checking) null else ({
                checking = true
                scope.launch { checked = container.checkForUpdates(now = true); checking = false }
            }))
        ListRow(stringResource(R.string.licence), context = stringResource(R.string.licence_text))
        ListRow(stringResource(R.string.privacy), context = stringResource(R.string.privacy_text))
        ListRow(stringResource(R.string.third_party_licences), context = stringResource(R.string.third_party_licences_text), onClick = {
            notices = context.assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name ->
                "── $name ──\n" + context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }
        })
    }
    notices?.let { text ->
        AlertDialog(
            onDismissRequest = { notices = null },
            title = { Text(stringResource(R.string.third_party_licences)) },
            text = {
                Text(text, style = FullaType.secondary, modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { notices = null }) { Text(stringResource(R.string.done)) } },
        )
    }
}
