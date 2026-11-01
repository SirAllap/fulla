// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.entry

import androidx.compose.ui.zIndex
import io.github.sirallap.fulla.core.rules.PeriodAnchors
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
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.sirallap.fulla.ui.components.DetailChip
import io.github.sirallap.fulla.ui.components.PickRow
import io.github.sirallap.fulla.ui.components.PickSheet
import io.github.sirallap.fulla.ui.components.PrimaryButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import io.github.sirallap.fulla.core.guide.TourStop
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.trips.Trips
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.guide.guideTarget
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.AddDock
import io.github.sirallap.fulla.ui.components.AddPhase
import io.github.sirallap.fulla.ui.components.BackHeader
import io.github.sirallap.fulla.ui.components.LiquidSurface
import io.github.sirallap.fulla.ui.components.jelly
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
        private set
    /**
     * The trip active on [date] when this is a new expense or refund, but
     * only when [Config.meMemberId] is actually on it ([Trips.defaultFor]):
     * an edit keeps the row's own trip, even if none, and a trip nobody
     * assigned to this person never becomes the default just because its
     * dates cover today.
     */
    var tripId by mutableStateOf(Trips.initialTripId(isNew = existing == null, existingTripId = existing?.tripId, trips = config.trips, date = date, me = config.meMemberId))
    /** Once the person has touched the trip chip or toggle themselves, a date change never re-runs the pick. */
    var tripTouched by mutableStateOf(existing != null)
    /** Whether the person picked a trip chip in this edit, as opposed to [tripTouched]'s default-true for edits. */
    var tripChipTouched by mutableStateOf(false)
    fun pickDate(next: LocalDate) {
        date = next
        if (!tripTouched) tripId = Trips.defaultFor(config.trips, next, config.meMemberId)?.id
    }
    /**
     * Every trip active on [date] for anyone, not just this person: the
     * Add-screen toggle offers all of them. The row's own trip stays in the
     * list even once it no longer matches (the date moved outside it, or it
     * got archived), the same way the details sheet's chips do just below —
     * otherwise the toggle would fall back to showing "Everyday" while the
     * row is still actually saved onto that trip (review: MEDIUM).
     */
    fun tripsOnDate(): List<io.github.sirallap.fulla.core.trips.Trip> {
        val onDate = config.trips.filter { !it.archived && it.startDate <= date && date <= it.endDate }
        val current = tripId?.let { id -> config.trips.firstOrNull { it.id == id } }
        return if (current != null && current.id !in onDate.map { it.id }) onDate + current else onDate
    }
    fun pickTrip(id: String?) {
        tripId = id; tripTouched = true; tripChipTouched = true
    }
    var accountId by mutableStateOf(existing?.accountId ?: firstAccount)
    var toAccountId by mutableStateOf(existing?.toAccountId)
    var paidBy by mutableStateOf(existing?.paidByMemberId ?: config.meMemberId ?: everyone.firstOrNull())
    var toMember by mutableStateOf(existing?.toMemberId)
    var splitWith by mutableStateOf(existing?.split?.memberIds ?: everyone.toSet())
    var note by mutableStateOf(existing?.note ?: "")
    var fixed by mutableStateOf(existing?.recurrence == Recurrence.FIXED)
    var extras by mutableStateOf<Map<String, Any?>>(existing?.extras ?: emptyMap())
    val template: Transaction? = existing
    private val active = view.active
    /** The salary that started the last period: an income like it is proposed as the next one. */
    private val latestSalary = PeriodAnchors.latest(active)
    /** This row as the rules see it, for the switch's default and its notes. */
    private val probe: Transaction get() = Transaction(id = id, kind = kind, date = date, amountMinor = 0, categoryId = categoryId,
        accountId = accountId, tags = template?.tags.orEmpty(), createdAt = "", clientUpdatedAt = "")
    /** Shown in plain sight, or kept in the details (PeriodAnchors.worthOffering). */
    val offersStartsMonth: Boolean get() = kind == TransactionKind.INCOME && (startsMonthChoice != null || PeriodAnchors.worthOffering(probe, active))
    /** Another income already starting this month, near this one's date. */
    val otherSalary: Transaction? get() = if (kind == TransactionKind.INCOME) PeriodAnchors.otherSalaryNear(probe, active) else null
    /** Set once the person flips "starts the month"; until then it follows the row, or the proposal. */
    var startsMonthChoice by mutableStateOf<Boolean?>(null)
    val startsMonth: Boolean get() = startsMonthChoice ?: template?.let(PeriodAnchors::marked)
        ?: (PeriodAnchors.looksLikeSalary(probe, latestSalary) && PeriodAnchors.otherSalaryNear(probe, active) == null)

    fun build(): Transaction {
        val splits = kind == TransactionKind.EXPENSE || kind == TransactionKind.REFUND
        val members = everyone.filter { it in splitWith }
        val nextTripId = if (splits) tripId else null
        val nextTripKnown = Trips.tripKnownForEdit(template?.tripKnown, tripChipTouched, nextTripId)
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
            tripId = nextTripId,
            tripKnown = nextTripKnown,
            recurrence = if (fixed) Recurrence.FIXED else Recurrence.VARIABLE,
            note = note.trim(),
            extras = extras,
        ).let { PeriodAnchors.mark(it, kind == TransactionKind.INCOME && startsMonth) }
    }
}

/**
 * Writing something down: the amount first, then what it was, then save.
 * Everything else has a sensible default and sits one tap away on the
 * details line, so the common case is three taps.
 */
@Composable
fun EntryScreen(view: HouseholdView, editingId: String?, headerActions: (@Composable () -> Unit)?, onDone: () -> Unit, onDeleted: (String) -> Unit = {},
                /** An income asked for from outside (a launcher shortcut): a new entry turns into one, then [onIncomeShown]. */
                incomeAsked: Boolean = false, onIncomeShown: () -> Unit = {},
                /** A category asked for from outside (the widget's shortcuts): a new expense starts in it, then [onCategoryShown]. */
                categoryAsked: String? = null, onCategoryShown: () -> Unit = {},
                /** The round button of the tab bar, when this is the Add tab: it becomes Save (see [AddDock]). */
                dock: AddDock? = null) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val existing = remember(editingId) { editingId?.let { id -> view.rows.firstOrNull { it.id == id }?.transaction } }
    val draft = remember(editingId, view.id) { Draft(view, existing) }
    LaunchedEffect(incomeAsked) {
        if (incomeAsked && editingId == null) { draft.kind = TransactionKind.INCOME; draft.categoryId = null; onIncomeShown() }
    }
    LaunchedEffect(categoryAsked) {
        val asked = categoryAsked ?: return@LaunchedEffect
        if (editingId == null && view.config.category(asked)?.archived == false) { draft.kind = TransactionKind.EXPENSE; draft.categoryId = asked }
        onCategoryShown()
    }
    // The detail being changed, in its own sheet: one at a time, opened from its chip.
    var detail by remember { mutableStateOf<Detail?>(null) }
    var allCategories by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val c = FullaTheme.colors
    val fieldProblem = stringResource(R.string.field_value_problem)

    var saved by remember { mutableStateOf(false) }
    val reducedMotion = io.github.sirallap.fulla.ui.theme.FullaMotion.reduced()

    fun save() {
        if (saved) return
        // A field limited to categories the row is no longer in is cleared, not kept from before.
        val asked = SchemaEngine.fieldsForCategory(view.config.fields, draft.kind, view.config.category(draft.categoryId)).map { it.key }.toSet()
        val stale = view.config.fields.filter { it.categoryIds != null && it.key !in asked }.map { it.key }
            .filter { it in draft.extras || existing?.extras?.containsKey(it) == true }
        if (stale.isNotEmpty()) draft.extras = draft.extras + stale.associateWith { null }
        // A new row in one shared pot is the payer's alone; an edit keeps the split it has.
        val built = draft.build().let { if (existing == null) SharedPot.forNew(it, view.config.household) else it }
        val problems = TransactionValidator.problems(built, view.config, isNew = existing == null)
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
        // One salary per month: marking this one takes the mark off the one it replaces.
        val replaced = if (PeriodAnchors.marked(t)) draft.otherSalary?.let { PeriodAnchors.mark(it, false) } else null
        scope.launch {
            if (replaced == null) container.ledger.save(view.id, t) else container.ledger.saveAll(view.id, listOf(t, replaced))
            // The save key gathers into a ✓ before the screen moves on.
            saved = true
            if (!reducedMotion) kotlinx.coroutines.delay(460)
            onDone()
            saved = false
        }
    }

    // The tab bar's round button is this screen's Save: tell it where the entry stands.
    if (dock != null) {
        val pickCategory = stringResource(R.string.add_pick_category)
        val needAmount = stringResource(R.string.add_need_amount)
        val saveText = stringResource(R.string.save)
        val amountText = view.formats.money(draft.keypad.totalMinor)
        val hasAmount = draft.keypad.canSave
        val needsCategory = draft.kind.isCategorised && draft.categoryId == null
        val phase = when {
            saved -> AddPhase.SAVED
            hasAmount && !needsCategory -> AddPhase.READY
            hasAmount -> AddPhase.NEEDS_CATEGORY
            draft.categoryId != null -> AddPhase.NEEDS_AMOUNT
            else -> AddPhase.IDLE
        }
        val words = when (phase) {
            AddPhase.READY, AddPhase.SAVED -> "$saveText · $amountText"
            AddPhase.NEEDS_CATEGORY -> pickCategory
            AddPhase.NEEDS_AMOUNT -> needAmount
            AddPhase.IDLE -> ""
        }
        SideEffect {
            dock.publish(phase, words, onSave = ::save,
                onDiscard = { draft.keypad = Keypad(view.formats.currency); draft.categoryId = null; problem = null },
                onMissing = { if (phase == AddPhase.NEEDS_CATEGORY) problem = pickCategory })
        }
        DisposableEffect(dock) { onDispose { dock.clear() } }
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
                    scope.launch { container.ledger.delete(view.id, editingId); onDeleted(editingId); onDone() }
                },
            ))
        }

        KindRow(draft.kind) { draft.kind = it; draft.categoryId = null; problem = null }

        if (draft.kind == TransactionKind.EXPENSE || draft.kind == TransactionKind.REFUND) {
            val onDate = draft.tripsOnDate()
            if (onDate.isNotEmpty()) {
                io.github.sirallap.fulla.ui.components.TripToggle(
                    everydayLabel = stringResource(R.string.trip_everyday),
                    trips = onDate,
                    tripPickerTitle = stringResource(R.string.trip_pick_title),
                    selectedTripId = draft.tripId,
                    onSelect = { draft.pickTrip(it) },
                )
            }
        }

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
        // Right under the amount, as in the mockup: no empty gap above them; what runs under the keypad fades and scrolls.
        val detailsScroll = rememberScrollState()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        if (detailsScroll.canScrollForward) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp).zIndex(1f)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.paper.copy(alpha = 0f), c.paper))))
        }
        Column(Modifier.verticalScroll(detailsScroll).padding(bottom = 8.dp)) {
            if (draft.kind.isCategorised) {
                val top = view.config.category(draft.categoryId)?.let { it.parentId ?: it.id }
                CategoryTiles(view, draft.kind, top, showAll = allCategories,
                    onPick = { draft.categoryId = it; problem = null }, onMore = { allCategories = !allCategories })
                SubcategoryChips(view, draft)
                CategoryFieldChips(view, draft)
            }
            DetailChips(view, draft) { detail = it }
            if (draft.offersStartsMonth) StartsMonthSwitch(view, draft)
            if (draft.kind == TransactionKind.INCOME) {
                // A salary a recurring item wrote on its own starts no period until somebody saves it.
                if (existing != null && PeriodAnchors.marked(existing) && !PeriodAnchors.confirmed(existing)) {
                    Text(stringResource(R.string.salary_generated_note), style = FullaType.secondary, color = c.inkMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
            problem?.let { Text(it, style = FullaType.secondary, color = c.danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        }
        }

        KeypadPad(
            aboveTabBar = headerActions != null,
            showSave = dock == null,
            keypad = draft.keypad,
            decimal = view.formats.decimalStyle.decimal,
            amount = view.formats.money(draft.keypad.totalMinor),
            onChange = { draft.keypad = it; problem = null },
            onSave = ::save,
            saved = saved,
            saveLabel = stringResource(if (editingId == null) R.string.save else R.string.save_changes),
        )
    }

    detail?.let { DetailSheet(view, draft, it, onDismiss = { detail = null }) }
}

/** "Starts the month", with what it does to the income already starting this month, if any. */
@Composable
private fun StartsMonthSwitch(view: HouseholdView, draft: Draft) {
    val other = draft.otherSalary
    val note = other?.let {
        val name = it.note.ifBlank { view.categoryName(it.categoryId) ?: "" }
        stringResource(if (draft.startsMonth) R.string.starts_month_replaces else R.string.starts_month_taken, name, view.formats.day(it.date))
    }
    SwitchRow(stringResource(R.string.starts_month), note ?: stringResource(R.string.starts_month_help), draft.startsMonth) {
        draft.startsMonthChoice = it
    }
}

@Composable
private fun KindRow(kind: TransactionKind, onPick: (TransactionKind) -> Unit) {
    val kinds = listOf(
        TransactionKind.EXPENSE to R.string.kind_expense,
        TransactionKind.INCOME to R.string.kind_income,
        TransactionKind.REFUND to R.string.kind_refund,
        TransactionKind.TRANSFER to R.string.kind_transfer,
    )
    // One segmented choice, as in the mockup: two by two, or one under the other, when big text doesn't fit four across.
    io.github.sirallap.fulla.ui.components.SegmentedChoice(kinds.map { (k, label) -> k to stringResource(label) }, kind, onPick,
        Modifier.padding(horizontal = 20.dp))
}

@Composable
internal fun CategoryTiles(
    view: HouseholdView, kind: TransactionKind, selected: String?, showAll: Boolean, onPick: (String) -> Unit, onMore: () -> Unit,
    columns: Int = 4, sidePadding: androidx.compose.ui.unit.Dp = 16.dp,
) {
    val c = FullaTheme.colors
    // Only top-level categories: a subcategory is picked on the line below, once its category is.
    val usable = view.config.categories.filter { !it.archived && it.appliesTo.allows(kind) && it.parentId == null }
    // The categories used most in the last three months come first, counting their subcategories.
    val since = LocalDate.now().minusMonths(3)
    val use = view.active.filter { it.date >= since && it.kind == kind }
        .groupingBy { view.config.category(it.categoryId)?.parentId ?: it.categoryId }.eachCount()
    val ordered = usable.sortedWith(compareByDescending<Category> { use[it.id] ?: 0 }.thenBy { it.sort })
    val limit = columns * 2
    val shown = if (showAll || ordered.size <= limit) ordered else ordered.take(limit - 1).let { top ->
        if (selected != null && top.none { it.id == selected }) top.dropLast(1) + ordered.first { it.id == selected } else top
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = sidePadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = columns,
    ) {
        for (cat in shown) {
            Tile(cat.name, CategoryIcons.of(cat.icon), selected = cat.id == selected, tint = c.category(cat.colorIndex)) { onPick(cat.id) }
        }
        if (ordered.size > limit) {
            Tile(stringResource(if (showAll) R.string.fewer else R.string.more), Icons.Outlined.ExpandMore, selected = false, tint = c.inkMuted, onClick = onMore)
        }
    }
}

/** The picked category's subcategories, with the category itself first ("General"). Nothing when it has none. */
@Composable
private fun SubcategoryChips(view: HouseholdView, draft: Draft) {
    val picked = view.config.category(draft.categoryId) ?: return
    val topId = picked.parentId ?: picked.id
    val subs = view.config.categories.filter { it.parentId == topId && !it.archived && it.appliesTo.allows(draft.kind) }.sortedBy { it.sort }
    if (subs.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.subcategory_general), draft.categoryId == topId, { draft.categoryId = topId })
        for (s in subs) Chip(s.name, draft.categoryId == s.id, { draft.categoryId = s.id })
    }
}

/**
 * The fields limited to the picked category, asked right under it as one
 * more level: Pets › Vet › who it was for. A list is chips; tapping the
 * chosen one again clears it.
 */
@Composable
private fun CategoryFieldChips(view: HouseholdView, draft: Draft) {
    val language = java.util.Locale.getDefault().language
    for (field in SchemaEngine.fieldsForCategory(view.config.fields, draft.kind, view.config.category(draft.categoryId))) {
        if (field.type == io.github.sirallap.fulla.core.schema.FieldType.SELECT) {
            Text(field.label(language), style = FullaType.label, color = FullaTheme.colors.inkMuted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
            val current = draft.extras[field.key]?.toString()
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (o in field.options) Chip(o, current == o, { draft.extras = draft.extras + (field.key to if (current == o) null else o) })
            }
        } else {
            FieldControl(view, field, draft.extras[field.key]) { v -> draft.extras = draft.extras + (field.key to v) }
        }
    }
}

@Composable
internal fun androidx.compose.foundation.layout.FlowRowScope.Tile(label: String, icon: ImageVector, selected: Boolean, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val c = FullaTheme.colors
    val ink by androidx.compose.animation.animateColorAsState(if (selected) c.onHighlight else c.ink,
        androidx.compose.animation.core.tween(220), label = "tileInk")
    Box(
        Modifier.weight(1f).heightIn(min = 72.dp).jelly(selected).clip(RoundedCornerShape(16.dp))
            .background(c.paperHigh)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // What is chosen fills with a liquid that rises, not a flat colour.
        LiquidSurface(if (selected) 1.05f else 0f, c.highlight, Modifier.matchParentSize(), maxAmplitude = 4.dp)
        Column(
            Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(if (selected) Icons.Outlined.Check else icon, null, tint = if (selected) ink else tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = FullaType.label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** Which detail of the row a sheet changes. */
private sealed interface Detail {
    data object Date : Detail
    data object Account : Detail
    data object ToAccount : Detail
    data object Payer : Detail
    data object Split : Detail
    data object Note : Detail
    data class Field(val key: String) : Detail
}

/**
 * Every detail of the row in plain sight (docs/ux.md, piece 6): what it is
 * now, on a chip that opens what changes it; what can be added, dashed with
 * a +. Nothing waits behind a summary line. In one shared pot nobody is
 * asked who paid.
 */
@Composable
private fun DetailChips(view: HouseholdView, draft: Draft, onOpen: (Detail) -> Unit) {
    val today = LocalDate.now()
    val language = java.util.Locale.getDefault().language
    val people = view.config.activeMembers.size > 1 && draft.kind != TransactionKind.TRANSFER && !SharedPot.isShared(view.config.household)
    val splits = draft.kind == TransactionKind.EXPENSE || draft.kind == TransactionKind.REFUND
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailChip(
            when (draft.date) {
                today -> stringResource(R.string.today)
                today.minusDays(1) -> stringResource(R.string.yesterday)
                else -> view.formats.day(draft.date)
            },
            { onOpen(Detail.Date) }, icon = Icons.Outlined.Event,
        )
        DetailChip(view.accountName(draft.accountId) ?: stringResource(R.string.account), { onOpen(Detail.Account) },
            icon = Icons.Outlined.AccountBalanceWallet)
        if (draft.kind == TransactionKind.TRANSFER) {
            val to = view.accountName(draft.toAccountId)
            DetailChip(to?.let { "→ $it" } ?: stringResource(R.string.to_account), { onOpen(Detail.ToAccount) }, empty = to == null)
        }
        if (people) {
            DetailChip(stringResource(R.string.paid_by, view.memberName(draft.paidBy)), { onOpen(Detail.Payer) }, icon = Icons.Outlined.Person)
            if (splits) DetailChip(
                if (draft.splitWith.size == view.config.activeMembers.size) stringResource(R.string.split_everyone)
                else stringResource(R.string.split_between, draft.splitWith.size),
                { onOpen(Detail.Split) }, icon = Icons.Outlined.Group,
            )
        }
        for (field in SchemaEngine.fieldsForForm(view.config.fields, draft.kind)) {
            val label = field.label(language)
            val shown = fieldValueText(view, draft.extras[field.key])
            if (shown == null) DetailChip(label, { onOpen(Detail.Field(field.key)) }, empty = true)
            else DetailChip("$label: $shown", { onOpen(Detail.Field(field.key)) })
        }
        if (draft.kind == TransactionKind.INCOME && !draft.offersStartsMonth) {
            Chip(stringResource(R.string.starts_month), draft.startsMonth, { draft.startsMonthChoice = !draft.startsMonth })
        }
        if (draft.kind == TransactionKind.INCOME || draft.kind == TransactionKind.EXPENSE) {
            // Off, it is a detail with no value yet: "+ Fixed" (ux.md, piece 6).
            if (draft.fixed) Chip(stringResource(R.string.fixed), true, { draft.fixed = false })
            else DetailChip(stringResource(R.string.fixed), { draft.fixed = true }, empty = true)
        }
        if (draft.note.isBlank()) DetailChip(stringResource(R.string.note), { onOpen(Detail.Note) }, empty = true)
        else DetailChip("“${draft.note}”", { onOpen(Detail.Note) })
    }
}

/** A custom field's value as a chip says it; null when it has none. */
@Composable
private fun fieldValueText(view: HouseholdView, value: Any?): String? = when (value) {
    null -> null
    is Boolean -> if (value) "✓" else null
    is List<*> -> value.filterIsInstance<String>().takeIf { it.isNotEmpty() }?.joinToString(", ")
    is String -> value.takeIf { it.isNotBlank() }?.let { v -> view.config.activeMembers.firstOrNull { it.id == v }?.displayName ?: v }
    else -> value.toString()
}

/** The sheet that changes one [detail]: a tap picks and closes it; the people a cost is split between, and the note, end with Done. */
@Composable
private fun DetailSheet(view: HouseholdView, draft: Draft, detail: Detail, onDismiss: () -> Unit) {
    val accounts = view.config.accounts.filter { !it.archived }
    val members = view.config.activeMembers
    val done = stringResource(R.string.done)
    when (detail) {
        Detail.Date -> DateSheet(view, draft, onDismiss)
        Detail.Account -> PickSheet(stringResource(if (draft.kind == TransactionKind.TRANSFER) R.string.from_account else R.string.account), onDismiss) {
            for (a in accounts) PickRow(a.name, a.id == draft.accountId, { draft.accountId = a.id; onDismiss() })
        }
        Detail.ToAccount -> PickSheet(stringResource(R.string.to_account), onDismiss) {
            for (a in accounts.filter { it.id != draft.accountId }) PickRow(a.name, a.id == draft.toAccountId, { draft.toAccountId = a.id; onDismiss() })
        }
        Detail.Payer -> PickSheet(stringResource(if (draft.kind == TransactionKind.INCOME) R.string.received_by else R.string.who_paid), onDismiss) {
            for (m in members) PickRow(m.displayName, m.id == draft.paidBy, { draft.paidBy = m.id; onDismiss() },
                start = { MemberBadge(m.initials, m.colorIndex) })
        }
        Detail.Split -> PickSheet(stringResource(R.string.split_between_title), onDismiss) {
            for (m in members) {
                val on = m.id in draft.splitWith
                PickRow(m.displayName, on, {
                    val next = if (on) draft.splitWith - m.id else draft.splitWith + m.id
                    if (next.isNotEmpty()) draft.splitWith = next
                }, start = { MemberBadge(m.initials, m.colorIndex) })
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) { PrimaryButton(done, onDismiss) }
        }
        Detail.Note -> PickSheet(stringResource(R.string.note), onDismiss) {
            // Typed in a short sheet that rides on the keyboard: the amount and the category stay in sight above it.
            val focus = remember { FocusRequester() }
            OutlinedTextField(draft.note, { if (it.length <= 500) draft.note = it },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.note_hint)) }, singleLine = false, maxLines = 3)
            LaunchedEffect(Unit) { focus.requestFocus() }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)) { PrimaryButton(done, onDismiss) }
        }
        is Detail.Field -> {
            val field = view.config.fields.firstOrNull { it.key == detail.key } ?: return
            PickSheet(field.label(java.util.Locale.getDefault().language), onDismiss) {
                FieldControl(view, field, draft.extras[field.key]) { v -> draft.extras = draft.extras + (field.key to v) }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) { PrimaryButton(done, onDismiss) }
            }
        }
    }
}

/** Today, yesterday, or another day from the calendar. */
@Composable
private fun DateSheet(view: HouseholdView, draft: Draft, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    var pickingDate by remember { mutableStateOf(false) }
    if (!pickingDate) PickSheet(stringResource(R.string.date), onDismiss) {
        PickRow(stringResource(R.string.today), draft.date == today, { draft.pickDate(today); onDismiss() })
        PickRow(stringResource(R.string.yesterday), draft.date == today.minusDays(1), { draft.pickDate(today.minusDays(1)); onDismiss() })
        val other = draft.date < today.minusDays(1) || draft.date > today
        PickRow(if (other) view.formats.day(draft.date) else stringResource(R.string.other_day), other, { pickingDate = true })
    }
    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = draft.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { draft.pickDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    onDismiss()
                }) { Text(stringResource(R.string.done)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

/**
 * Digits, the decimal key (when the currency has decimals), delete and "+",
 * then the screen's primary button (ux.md, piece 1): the action and its
 * figure, "Save · €12.50", never a bare grey tick.
 */
@Composable
private fun KeypadPad(keypad: Keypad, decimal: Char, amount: String, onChange: (Keypad) -> Unit, onSave: () -> Unit, saveLabel: String, saved: Boolean = false,
                      aboveTabBar: Boolean = false, showSave: Boolean = true) {
    val c = FullaTheme.colors
    val gap = 6.dp
    val key = 52.dp
    Column(Modifier.fillMaxWidth().guideTarget(TourStop.ADD_AND_KEYPAD).background(c.paperHigh)
        // On the Add tab the tab bar under it already keeps clear of the navigation bar: padding twice left a gap under Save.
        .then(if (aboveTabBar) Modifier else Modifier.navigationBarsPadding()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in listOf("123", "456", "789")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (d in row) Key(d.toString(), Modifier.height(key).weight(1f)) { onChange(keypad.digit(d)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    if (keypad.hasDecimalKey) Key(decimal.toString(), Modifier.height(key).weight(1f)) { onChange(keypad.decimal()) }
                    Key("0", Modifier.height(key).weight(if (keypad.hasDecimalKey) 2f else 3f)) { onChange(keypad.digit('0')) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                Key(null, Modifier.fillMaxWidth().height(key * 2 + gap), icon = Icons.AutoMirrored.Outlined.Backspace,
                    description = stringResource(R.string.delete_digit)) { onChange(keypad.delete()) }
                Key("+", Modifier.fillMaxWidth().height(key * 2 + gap), description = stringResource(R.string.add_another)) { onChange(keypad.plus()) }
            }
        }
        if (showSave) PrimaryButton(
            if (keypad.canSave) "$saveLabel · $amount" else saveLabel,
            onClick = onSave,
            icon = if (saved) Icons.Outlined.Check else null,
            enabled = keypad.canSave && !saved,
        )
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

