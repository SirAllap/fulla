// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.core.keypad.Keypad
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.rules.PeriodAnchors
import io.github.sirallap.fulla.core.schema.CustomField
import io.github.sirallap.fulla.core.schema.FieldType
import io.github.sirallap.fulla.core.schema.SchemaEngine
import io.github.sirallap.fulla.core.trips.Trips
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.model.TransactionValidator
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.time.LocalDate
import org.w3c.dom.HTMLElement

/**
 * Writing something down, as the Android app does it: the kind, the amount as
 * large as the screen allows, the categories and the details line just above
 * the keypad, where the thumb already is. The keypad is core's own.
 */
object AddScreen {
    private var kind = TransactionKind.EXPENSE
    private var keypad: Keypad? = null
    private var categoryId: String? = null
    private var accountId: String? = null
    private var toAccountId: String? = null
    private var paidBy: String? = null
    private var splitWith: Set<String>? = null
    private var date: LocalDate? = null
    private var note = ""
    private var showAll = false
    private var problem: String? = null
    private var tripId: String? = null
    private var tripTouched = false
    private var tripChipTouched = false
    private var extras: Map<String, Any?> = emptyMap()
    private var fixed = false
    private var startsMonthChoice: Boolean? = null
    private var loadedFor: String? = null
    private var householdFor: String? = null

    private val kinds = listOf(
        TransactionKind.EXPENSE to "kind_expense", TransactionKind.INCOME to "kind_income",
        TransactionKind.REFUND to "kind_refund", TransactionKind.TRANSFER to "kind_transfer",
    )

    private fun reset(format: Format) {
        keypad = Keypad(format.currency); categoryId = null; note = ""; date = null; problem = null; showAll = false
        tripId = null; tripTouched = false; tripChipTouched = false; extras = emptyMap(); fixed = false; startsMonthChoice = null
    }

    /** An edit from History starts from the row it changes. */
    private fun loadEditing(view: HouseholdView, format: Format) {
        val id = App.editing
        if (id == loadedFor && householdFor == view.id) return
        loadedFor = id
        householdFor = view.id
        val row = id?.let { wanted -> view.rows.firstOrNull { it.id == wanted }?.transaction }
        if (row == null) { App.editing = null; reset(format); paidBy = null; splitWith = null; return }
        kind = row.kind
        keypad = Keypad(format.currency).withAmount(row.amountMinor)
        categoryId = row.categoryId
        accountId = row.accountId
        toAccountId = row.toAccountId
        paidBy = row.paidByMemberId
        splitWith = row.split?.memberIds
        date = row.date
        note = row.note
        problem = null
        tripId = row.tripId; tripTouched = true; tripChipTouched = false
        extras = row.extras; fixed = row.recurrence == Recurrence.FIXED; startsMonthChoice = null
    }

    fun build(view: HouseholdView, format: Format): HTMLElement {
        if (keypad == null || householdFor != view.id && App.editing == null) { reset(format); householdFor = view.id; paidBy = null; splitWith = null }
        loadEditing(view, format)
        val config = view.config
        val editingRow = App.editing?.let { id -> view.rows.firstOrNull { it.id == id }?.transaction }
        val accounts = config.accounts.filter { !it.archived }
        if (accountId == null || accounts.none { it.id == accountId }) accountId = accounts.firstOrNull()?.id
        val members = config.activeMembers
        if (!tripTouched && editingRow == null && kind.isTripKind) tripId = Trips.defaultFor(config.trips, date ?: LocalDate.now(), config.meMemberId)?.id
        if (paidBy == null || members.none { it.id == paidBy }) paidBy = config.meMemberId ?: members.firstOrNull()?.id

        return el("main", "screen add") {
            tabHeader(if (editingRow != null) t("edit_transaction") else t("tab_add"),
                *(if (editingRow != null) arrayOf(
                    iconButton("close", t("cancel")) { App.editing = null; loadedFor = null; reset(format); App.go(Tab.HISTORY) },
                    if (editingRow.status == Status.DELETED) iconButton("restore_from_trash", t("restore")) {
                        val id = editingRow.id; App.editing = null; loadedFor = null; reset(format)
                        App.launch { Ledger.undelete(id); App.go(Tab.HISTORY) }
                    } else iconButton("delete_outline", t("delete")) {
                        val id = editingRow.id; App.editing = null; loadedFor = null; reset(format)
                        App.launch { Ledger.delete(id); App.toast(t("entry_deleted"), t("undo")) { App.launch { Ledger.undelete(id) } }; App.go(Tab.HISTORY) }
                    },
                ) else emptyArray()))
            chipRow { for ((k, label) in kinds) chip(t(label), k == kind) { kind = k; categoryId = null; problem = null; App.render() } }
            if (kind.isTripKind) tripToggle(view)
            div("body") {
                lateinit var sum: HTMLElement
                lateinit var amount: HTMLElement
                div("entry-sum t-secondary muted") { sum = this }
                div(if (kind == TransactionKind.INCOME) "entry-amount in" else "entry-amount") { amount = this; attr("aria-live", "polite") }
                div("pick") {
                    if (kind.isCategorised) {
                        categoryTiles(view, format)
                        subcategories(view)
                        categoryFields(view)
                    }
                    problem?.let { child("p", "problem") { attr("role", "alert"); text(it) } }
                    detailsLine(view, format, accounts.size)
                    startsMonth(view, editingRow, format)
                }
                keypadView(format, sum, amount) { save(view, format, editingRow) }
                paint(view, format, sum, amount)
            }
        }
    }

    private fun paint(view: HouseholdView, format: Format, sum: HTMLElement, amount: HTMLElement) {
        val pad = keypad!!
        sum.textContent = if (pad.isAdding) pad.addends.joinToString(" + ") { format.plain(it) } + " +" else ""
        sum.style.display = if (pad.isAdding) "block" else "none"
        amount.textContent = format.money(pad.totalMinor)
    }

    private fun HTMLElement.categoryTiles(view: HouseholdView, format: Format) {
        val usable = view.config.categories.filter { !it.archived && it.appliesTo.allows(kind) && it.parentId == null }
        // The categories used most in the last three months come first, counting their subcategories.
        val since = LocalDate.now().minusMonths(3)
        val use = view.active.filter { it.date >= since && it.kind == kind }
            .groupingBy { view.config.category(it.categoryId)?.parentId ?: it.categoryId }.eachCount()
        val ordered = usable.sortedWith(compareByDescending<Category> { use[it.id] ?: 0 }.thenBy { it.sort })
        val selectedTop = view.config.category(categoryId)?.let { it.parentId ?: it.id }
        val limit = 8
        val shown = if (showAll || ordered.size <= limit) ordered else ordered.take(limit - 1).let { top ->
            if (selectedTop != null && top.none { it.id == selectedTop }) top.dropLast(1) + ordered.first { it.id == selectedTop } else top
        }
        div("tiles") {
            for (c in shown) tile(c.name, c.icon, "var(--cat-${c.colorIndex.mod(12)})", c.id == selectedTop) { categoryId = c.id; problem = null; App.render() }
            if (ordered.size > limit) tile(if (showAll) t("fewer") else t("more"), "", "var(--ink-muted)", false, uiIcon = "expand_more") { showAll = !showAll; App.render() }
        }
    }

    /** The picked category's subcategories, with the category itself first ("General"). Nothing when it has none. */
    private fun HTMLElement.subcategories(view: HouseholdView) {
        val picked = view.config.category(categoryId) ?: return
        val topId = picked.parentId ?: picked.id
        val subs = view.config.categories.filter { it.parentId == topId && !it.archived && it.appliesTo.allows(kind) }.sortedBy { it.sort }
        if (subs.isEmpty()) return
        chipRow(wrap = true) {
            chip(t("subcategory_general"), categoryId == topId) { categoryId = topId; App.render() }
            for (s in subs) chip(s.name, categoryId == s.id) { categoryId = s.id; App.render() }
        }
    }

    /** Date · account · who paid · split, in one tappable line. In one shared pot nobody is asked who paid. */
    private fun HTMLElement.detailsLine(view: HouseholdView, format: Format, accountCount: Int) {
        val config = view.config
        val today = LocalDate.now()
        val parts = buildList {
            add(if ((date ?: today) == today) t("today") else format.day(date!!))
            config.account(accountId)?.name?.takeIf { accountCount > 0 }?.let(::add)
            if (kind == TransactionKind.TRANSFER) config.account(toAccountId)?.name?.let { add("→ $it") }
            if (config.activeMembers.size > 1 && kind != TransactionKind.TRANSFER && !SharedPot.isShared(config.household)) {
                add(t("paid_by", view.memberName(paidBy)))
                if (kind == TransactionKind.EXPENSE || kind == TransactionKind.REFUND) {
                    val n = (splitWith ?: config.activeMembers.map { it.id }.toSet()).size
                    add(if (n == config.activeMembers.size) t("split_everyone") else t("split_between", n))
                }
            }
            if (note.isNotBlank()) add("“$note”")
        }
        child("button", "details-line") {
            attr("type", "button")
            attr("aria-label", t("details"))
            span("") { text(parts.joinToString(" · ")) }
            ui("expand_more")
            click { detailsSheet(view, format) }
        }
    }

    private val TransactionKind.isTripKind: Boolean get() = this == TransactionKind.EXPENSE || this == TransactionKind.REFUND

    /** "Everyday / ✈ trip", above the amount: assigning a trip is an explicit, reversible step before saving. */
    private fun HTMLElement.tripToggle(view: HouseholdView) {
        val date = date ?: LocalDate.now()
        val onDate = view.config.trips.filter { !it.archived && it.startDate <= date && date <= it.endDate }
        val current = tripId?.let { id -> view.config.trips.firstOrNull { it.id == id } }
        val trips = if (current != null && onDate.none { it.id == current.id }) onDate + current else onDate
        if (trips.isEmpty()) return
        val selected = trips.firstOrNull { it.id == tripId }
        val shown = selected ?: trips.first()
        div("toggle") {
            child("button", if (selected == null) "toggle-option selected" else "toggle-option") {
                attr("type", "button"); text(t("trip_everyday")); click { tripId = null; tripTouched = true; tripChipTouched = true; App.render() }
            }
            child("button", if (selected != null) "toggle-option selected" else "toggle-option") {
                attr("type", "button")
                icon(MaterialPaths.ui.getValue(tripKindIcon(shown.kind)), 18)
                span("") { text(shown.name) }
                click {
                    if (trips.size > 1) sheet(t("trip_pick_title")) { close ->
                        div("rows") { for (tr in trips) listRow(tr.name, start = leadIcon(tripKindIcon(tr.kind))) { close(); tripId = tr.id; tripTouched = true; tripChipTouched = true; App.render() } }
                    } else { tripId = trips.first().id; tripTouched = true; tripChipTouched = true; App.render() }
                }
            }
        }
    }

    /** The fields limited to the picked category, asked right under it as one more level: Pets › Vet › who it was for. */
    private fun HTMLElement.categoryFields(view: HouseholdView) {
        val picked = view.config.category(categoryId) ?: return
        for (field in SchemaEngine.fieldsForCategory(view.config.fields, kind, picked)) fieldControl(view, field)
    }

    /** One control per type: a type is not a labelled text box. Values are in their wire form; null clears the value. */
    private fun HTMLElement.fieldControl(view: HouseholdView, field: CustomField) {
        val label = field.label(I18n.language)
        val value = extras[field.key]
        val format = Format(view.config)
        fun set(v: Any?) { extras = extras + (field.key to v) }
        when (field.type) {
            FieldType.BOOLEAN -> div("rows") { switchRow(label, null, value == true) { set(it); App.render() } }
            FieldType.SELECT, FieldType.MEMBER -> {
                section(label)
                val options = if (field.type == FieldType.MEMBER) view.config.activeMembers.map { it.id to it.displayName } else field.options.map { it to it }
                chipRow(wrap = true) { for ((id, name) in options) chip(name, value == id) { set(if (value == id) null else id); App.render() } }
            }
            FieldType.MULTISELECT -> {
                section(label)
                val chosen = (value as? List<*>)?.filterIsInstance<String>().orEmpty()
                chipRow(wrap = true) {
                    for (o in field.options) chip(o, o in chosen) { set((if (o in chosen) chosen - o else chosen + o).ifEmpty { null }); App.render() }
                }
            }
            FieldType.DATE -> {
                section(label)
                val d = field(label, (value as? String) ?: "", "date")
                d.on("change") { set(d.value.ifEmpty { null }) }
            }
            FieldType.MONEY -> {
                val m = field(label, (value as? Number)?.toLong()?.let { format.plain(it) } ?: "", help = format.currency.code) { attr("inputmode", "decimal") }
                m.on("input") { set(if (m.value.isBlank()) null else MoneyParser.parseTyped(m.value, format.currency, format.decimalStyle) ?: value) }
            }
            FieldType.NUMBER -> {
                val n = field(label, value?.toString() ?: "") { attr("inputmode", "decimal") }
                n.on("input") { set(n.value.replace(',', '.').trim().ifBlank { null }) }
            }
            FieldType.TEXT -> {
                val x = field(label, (value as? String) ?: "") { attr("maxlength", "500") }
                x.on("input") { set(x.value.ifEmpty { null }) }
            }
        }
    }

    // ── "starts the month": a salary marks where the period begins ───────────

    private fun probe(view: HouseholdView, existing: Transaction?): Transaction =
        Transaction(id = existing?.id ?: "probe", kind = kind, date = date ?: LocalDate.now(), amountMinor = 0, categoryId = categoryId,
            accountId = accountId, tags = existing?.tags.orEmpty(), createdAt = "", clientUpdatedAt = "")

    private fun offersStartsMonth(view: HouseholdView, existing: Transaction?): Boolean =
        kind == TransactionKind.INCOME && (startsMonthChoice != null || PeriodAnchors.worthOffering(probe(view, existing), view.active))

    private fun startsMonthOn(view: HouseholdView, existing: Transaction?): Boolean {
        val p = probe(view, existing)
        return startsMonthChoice ?: existing?.let(PeriodAnchors::marked)
            ?: (PeriodAnchors.looksLikeSalary(p, PeriodAnchors.latest(view.active)) && PeriodAnchors.otherSalaryNear(p, view.active) == null)
    }

    private fun HTMLElement.startsMonth(view: HouseholdView, existing: Transaction?, format: Format) {
        if (kind != TransactionKind.INCOME) return
        if (offersStartsMonth(view, existing)) startsMonthSwitch(view, existing, format)
        // A salary a recurring item wrote on its own starts no period until somebody saves it.
        if (existing != null && PeriodAnchors.marked(existing) && !PeriodAnchors.confirmed(existing)) note(t("salary_generated_note"))
    }

    private fun HTMLElement.startsMonthSwitch(view: HouseholdView, existing: Transaction?, format: Format) {
        val other = PeriodAnchors.otherSalaryNear(probe(view, existing), view.active)
        val on = startsMonthOn(view, existing)
        val line = other?.let {
            val name = it.note.ifBlank { view.config.category(it.categoryId)?.name ?: "" }
            t(if (on) "starts_month_replaces" else "starts_month_taken", name, format.day(it.date))
        }
        div("rows") { switchRow(t("starts_month"), line ?: t("starts_month_help"), on) { startsMonthChoice = it; App.render() } }
    }

    private fun detailsSheet(view: HouseholdView, format: Format) {
        val config = view.config
        val today = LocalDate.now()
        val accounts = config.accounts.filter { !it.archived }
        val members = config.activeMembers
        val existing = App.editing?.let { id -> view.rows.firstOrNull { it.id == id }?.transaction }
        sheet(t("details")) { close ->
            section(t("date"), first = true)
            val day = date ?: today
            val other = day < today.minusDays(1) || day > today
            chipRow {
                chip(t("today"), day == today) { date = null; close(); App.render() }
                chip(t("yesterday"), day == today.minusDays(1)) { date = today.minusDays(1); close(); App.render() }
                chip(if (other) format.day(day) else t("other_day"), other) { }
            }
            div("field") {
                val dateInput = input("date", day.toString(), "input")
                dateInput.on("change") { runCatching { LocalDate.parse(dateInput.value) }.getOrNull()?.let { date = it; close(); App.render() } }
            }
            if (kind == TransactionKind.INCOME && !offersStartsMonth(view, existing)) startsMonthSwitch(view, existing, format)
            if (accounts.size > 1) {
                section(if (kind == TransactionKind.TRANSFER) t("from_account") else t("account"))
                chipRow(wrap = true) { for (a in accounts) chip(a.name, a.id == accountId) { accountId = a.id; close(); App.render() } }
                if (kind == TransactionKind.TRANSFER) {
                    section(t("to_account"))
                    chipRow(wrap = true) { for (a in accounts.filter { it.id != accountId }) chip(a.name, a.id == toAccountId) { toAccountId = a.id; close(); App.render() } }
                }
            }
            if (members.size > 1 && kind != TransactionKind.TRANSFER && !SharedPot.isShared(config.household)) {
                section(t(if (kind == TransactionKind.INCOME) "received_by" else "who_paid"))
                chipRow(wrap = true) { for (m in members) chip(m.displayName, m.id == paidBy) { paidBy = m.id; close(); App.render() } }
                if (kind == TransactionKind.EXPENSE || kind == TransactionKind.REFUND) {
                    section(t("split_between_title"))
                    val current = splitWith ?: members.map { it.id }.toSet()
                    chipRow(wrap = true) {
                        for (m in members) chip(m.displayName, m.id in current) {
                            val next = if (m.id in current) current - m.id else current + m.id
                            if (next.isNotEmpty()) splitWith = next
                            close(); detailsSheet(view, format); App.render()
                        }
                    }
                }
            }
            if (kind.isTripKind) {
                val onTrip = config.trips.filter { !it.archived && it.startDate <= day && day <= it.endDate }
                val current = tripId?.let { id -> config.trips.firstOrNull { it.id == id } }
                val chips = if (current != null && onTrip.none { it.id == current.id }) onTrip + current else onTrip
                if (chips.isNotEmpty()) {
                    section(t("settings_trips"))
                    chipRow(wrap = true) {
                        chip(t("trip_none"), tripId == null) { tripId = null; tripTouched = true; tripChipTouched = true; close(); App.render() }
                        for (tr in chips) chip(tr.name, tripId == tr.id) { tripId = tr.id; tripTouched = true; tripChipTouched = true; close(); App.render() }
                    }
                }
            }
            for (field in SchemaEngine.fieldsForForm(config.fields, kind)) fieldControl(view, field)
            if (kind == TransactionKind.INCOME || kind == TransactionKind.EXPENSE) div("rows") { switchRow(t("fixed"), t("fixed_help"), fixed) { fixed = it } }
            section(t("note"))
            div("field") {
                val noteInput = input("text", note, "input") { attr("placeholder", t("note_hint")); attr("autocomplete", "off"); attr("maxlength", "500") }
                noteInput.on("input") { note = noteInput.value }
            }
            div("actions") { primaryButton(t("done")) { close(); App.render() } }
        }
    }


    private fun HTMLElement.keypadView(format: Format, sum: HTMLElement, amount: HTMLElement, onSave: () -> Unit) {
        div("keypad") {
            attr("aria-label", t("amount"))
            lateinit var saveKey: HTMLElement
            fun press(next: Keypad) {
                keypad = next; problem = null
                amount.textContent = format.money(next.totalMinor)
                sum.textContent = if (next.isAdding) next.addends.joinToString(" + ") { format.plain(it) } + " +" else ""
                sum.style.display = if (next.isAdding) "block" else "none"
                saveKey.className = if (next.canSave) "key save ready" else "key save"
                if (next.canSave) saveKey.removeAttribute("disabled") else saveKey.setAttribute("disabled", "")
            }
            div("keys") {
                for (row in listOf("123", "456", "789")) for (d in row) key(d.toString()) { press(keypad!!.digit(d)) }
                if (keypad!!.hasDecimalKey) key(format.decimalStyle.decimal.toString()) { press(keypad!!.decimal()) } else span("")
                key("0") { press(keypad!!.digit('0')) }
                key("+", t("add_another")) { press(keypad!!.plus()) }
            }
            div("keyside") {
                key("", t("delete_digit"), "backspace") { press(keypad!!.delete()) }
                saveKey = child("button", if (keypad!!.canSave) "key save ready" else "key save") {
                    attr("type", "button")
                    attr("aria-label", t(if (App.editing == null) "save" else "save_changes"))
                    if (!keypad!!.canSave) attr("disabled", "")
                    ui("check", 32)
                    click(onSave)
                }
            }
        }
    }

    private fun HTMLElement.key(caption: String, label: String? = null, iconName: String? = null, onClick: () -> Unit) {
        child("button", "key") {
            attr("type", "button")
            if (label != null) attr("aria-label", label)
            if (iconName != null) ui(iconName, 24) else text(caption)
            click(onClick)
        }
    }

    private fun save(view: HouseholdView, format: Format, editingRow: Transaction?) {
        val config = view.config
        val pad = keypad ?: return
        if (!pad.canSave) return
        if (kind.isCategorised && categoryId == null) { problem = t("category"); App.render(); return }
        if (kind == TransactionKind.TRANSFER && (toAccountId == null || toAccountId == accountId)) { problem = t("to_account"); App.render(); return }
        // A field limited to categories the row is no longer in is cleared, not kept from before.
        val asked = SchemaEngine.fieldsForCategory(config.fields, kind, config.category(categoryId)).map { it.key }.toSet()
        val stale = config.fields.filter { it.categoryIds != null && it.key !in asked }.map { it.key }
            .filter { it in extras || editingRow?.extras?.containsKey(it) == true }
        if (stale.isNotEmpty()) extras = extras + stale.associateWith { null }
        val everyone = config.activeMembers.map { it.id }
        val splits = kind == TransactionKind.EXPENSE || kind == TransactionKind.REFUND
        val members = everyone.filter { it in (splitWith ?: everyone.toSet()) }
        val nextTripId = if (splits) tripId else null
        val base = editingRow ?: Transaction(id = randomUuid(), kind = kind, date = date ?: LocalDate.now(), amountMinor = 0, createdAt = "", clientUpdatedAt = "")
        var built = base.copy(
            kind = kind,
            date = date ?: LocalDate.now(),
            amountMinor = pad.totalMinor,
            categoryId = if (kind.isCategorised) categoryId else null,
            accountId = accountId,
            toAccountId = if (kind == TransactionKind.TRANSFER) toAccountId else null,
            paidByMemberId = paidBy ?: config.meMemberId,
            split = if (splits && everyone.size >= 2) Split.Equal(members) else null,
            tripId = nextTripId,
            tripKnown = Trips.tripKnownForEdit(editingRow?.tripKnown, tripChipTouched, nextTripId),
            recurrence = if (fixed) Recurrence.FIXED else Recurrence.VARIABLE,
            note = note.trim(),
            extras = extras,
        ).let { PeriodAnchors.mark(it, kind == TransactionKind.INCOME && startsMonthOn(view, editingRow)) }
        // A new row in one shared pot is the payer's alone; an edit keeps the split it has.
        if (editingRow == null) built = SharedPot.forNew(built, config.household)
        val problems = TransactionValidator.problems(built, config, isNew = editingRow == null)
        if (problems.isNotEmpty()) { problem = t("something_failed"); App.render(); return }
        // Custom fields: checked and put in canonical form the way the server will.
        val merged = SchemaEngine.merge(config.fields, built.kind, extras, emptyMap(), config.members.map { it.id }.toSet())
        merged.problems.firstOrNull()?.let { p ->
            val name = config.fields.firstOrNull { it.key == p.key }?.label(I18n.language) ?: p.key
            problem = t("field_value_problem", name); App.render(); return
        }
        // A cleared field travels as an explicit null: an absent key would keep the stored value.
        built = built.copy(extras = merged.extras + extras.filterValues { it == null }.keys.associateWith { null })
        // One salary per month: marking this one takes the mark off the one it replaces.
        val replaced = if (PeriodAnchors.marked(built)) PeriodAnchors.otherSalaryNear(built, view.active)?.let { PeriodAnchors.mark(it, false) } else null
        val savedTrip = config.trip(built.tripId)?.name
        App.launch {
            val wasEditing = editingRow != null
            App.editing = null; loadedFor = null
            reset(format)
            if (replaced == null) Ledger.save(built) else Ledger.saveAll(listOf(built, replaced))
            App.toast(if (savedTrip != null) t("saved_to_trip", savedTrip) else t("web_saved"))
            if (wasEditing) App.go(Tab.HISTORY) else App.render()
        }
    }
}
