// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.core.keypad.Keypad
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Split
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
    private var loadedFor: String? = null
    private var householdFor: String? = null

    private val kinds = listOf(
        TransactionKind.EXPENSE to "kind_expense", TransactionKind.INCOME to "kind_income",
        TransactionKind.REFUND to "kind_refund", TransactionKind.TRANSFER to "kind_transfer",
    )

    private fun reset(format: Format) {
        keypad = Keypad(format.currency); categoryId = null; note = ""; date = null; problem = null; showAll = false
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
    }

    fun build(view: HouseholdView, format: Format): HTMLElement {
        if (keypad == null || householdFor != view.id && App.editing == null) { reset(format); householdFor = view.id; paidBy = null; splitWith = null }
        loadEditing(view, format)
        val config = view.config
        val editingRow = App.editing?.let { id -> view.rows.firstOrNull { it.id == id }?.transaction }
        val accounts = config.accounts.filter { !it.archived }
        if (accountId == null || accounts.none { it.id == accountId }) accountId = accounts.firstOrNull()?.id
        val members = config.activeMembers
        if (paidBy == null || members.none { it.id == paidBy }) paidBy = config.meMemberId ?: members.firstOrNull()?.id

        return el("main", "screen add") {
            tabHeader(if (editingRow != null) t("edit_transaction") else t("tab_add"),
                *(if (editingRow != null) arrayOf(iconButton("close", t("cancel")) {
                    App.editing = null; loadedFor = null; reset(format); App.go(Tab.HISTORY)
                }) else emptyArray()))
            chipRow { for ((k, label) in kinds) chip(t(label), k == kind) { kind = k; categoryId = null; problem = null; App.render() } }
            div("body") {
                lateinit var sum: HTMLElement
                lateinit var amount: HTMLElement
                div("entry-sum t-secondary muted") { sum = this }
                div(if (kind == TransactionKind.INCOME) "entry-amount in" else "entry-amount") { amount = this; attr("aria-live", "polite") }
                div("pick") {
                    if (kind.isCategorised) {
                        categoryTiles(view, format)
                        subcategories(view)
                    }
                    problem?.let { child("p", "problem") { attr("role", "alert"); text(it) } }
                    detailsLine(view, format, accounts.size)
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

    private fun detailsSheet(view: HouseholdView, format: Format) {
        val config = view.config
        val today = LocalDate.now()
        val accounts = config.accounts.filter { !it.archived }
        val members = config.activeMembers
        sheet(t("details")) { close ->
            section(t("date"), first = true)
            chipRow {
                chip(t("today"), (date ?: today) == today) { date = null; close(); App.render() }
                chip(t("yesterday"), date == today.minusDays(1)) { date = today.minusDays(1); close(); App.render() }
            }
            div("field") {
                val dateInput = input("date", (date ?: today).toString(), "input") { attr("max", today.plusDays(366).toString()) }
                dateInput.on("change") { runCatching { LocalDate.parse(dateInput.value) }.getOrNull()?.let { date = it; App.render() } }
            }
            if (accounts.size > 1) {
                section(if (kind == TransactionKind.TRANSFER) t("from_account") else t("account"))
                chipRow { for (a in accounts) chip(a.name, a.id == accountId) { accountId = a.id; close(); App.render() } }
                if (kind == TransactionKind.TRANSFER) {
                    section(t("to_account"))
                    chipRow { for (a in accounts.filter { it.id != accountId }) chip(a.name, a.id == toAccountId) { toAccountId = a.id; close(); App.render() } }
                }
            }
            if (members.size > 1 && kind != TransactionKind.TRANSFER && !SharedPot.isShared(config.household)) {
                section(t("who_paid"))
                chipRow { for (m in members) chip(m.displayName, m.id == paidBy) { paidBy = m.id; close(); App.render() } }
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
            section(t("note"))
            div("field") {
                val noteInput = input("text", note, "input") { attr("placeholder", t("note_hint")); attr("autocomplete", "off") }
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
        val everyone = config.activeMembers.map { it.id }
        val splits = kind == TransactionKind.EXPENSE || kind == TransactionKind.REFUND
        val members = everyone.filter { it in (splitWith ?: everyone.toSet()) }
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
            note = note.trim(),
        )
        // A new row in one shared pot is the payer's alone; an edit keeps the split it has.
        if (editingRow == null) built = SharedPot.forNew(built, config.household)
        val problems = TransactionValidator.problems(built, config, isNew = editingRow == null)
        if (problems.isNotEmpty()) { problem = t("something_failed"); App.render(); return }
        App.launch {
            val wasEditing = editingRow != null
            App.editing = null; loadedFor = null
            reset(format)
            Ledger.save(built)
            App.toast(t("web_saved"))
            if (wasEditing) App.go(Tab.HISTORY) else App.render()
        }
    }
}
