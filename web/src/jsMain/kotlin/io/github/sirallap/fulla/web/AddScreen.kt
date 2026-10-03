// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.model.TransactionValidator
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.time.LocalDate
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Writing something down: the amount first, then what it was, then save.
 * Everything else has a sensible default, so the common case is three taps.
 */
object AddScreen {
    private var kind = TransactionKind.EXPENSE
    private var amountText = ""
    private var categoryId: String? = null
    private var accountId: String? = null
    private var date: LocalDate? = null
    private var note = ""
    private var anotherDay = false
    private var problem: String? = null
    private var loadedFor: String? = null

    private val kinds = listOf(TransactionKind.EXPENSE to "kind_expense", TransactionKind.INCOME to "kind_income", TransactionKind.REFUND to "kind_refund")

    private fun reset() {
        amountText = ""; categoryId = null; note = ""; date = null; anotherDay = false; problem = null
    }

    /** An edit from History starts from the row it changes. */
    private fun loadEditing(view: HouseholdView) {
        val id = App.editing
        if (id == loadedFor) return
        loadedFor = id
        val row = id?.let { wanted -> view.rows.firstOrNull { it.id == wanted }?.transaction }
        if (row == null) { App.editing = null; return }
        kind = row.kind
        amountText = io.github.sirallap.fulla.core.money.Currency.of(view.config.household.currency)?.let { c ->
            val digits = row.amountMinor.toString().padStart(c.minorUnits + 1, '0')
            if (c.minorUnits == 0) digits else digits.dropLast(c.minorUnits) + "." + digits.takeLast(c.minorUnits)
        } ?: row.amountMinor.toString()
        categoryId = row.categoryId
        accountId = row.accountId
        date = row.date
        note = row.note
        anotherDay = row.date != LocalDate.now() && row.date != LocalDate.now().minusDays(1)
        problem = null
    }

    fun build(view: HouseholdView, format: Format): HTMLElement {
        loadEditing(view)
        val config = view.config
        val editingRow = App.editing?.let { id -> view.rows.firstOrNull { it.id == id }?.transaction }
        val today = LocalDate.now()
        val chosenDate = date ?: today
        val accounts = config.accounts.filter { !it.archived }
        if (accountId == null || accounts.none { it.id == accountId }) accountId = accounts.firstOrNull()?.id

        return el("main", "screen add") {
            div("header") {
                child("h1", "title") { text(if (editingRow != null) t("edit_transaction") else t("tab_add")) }
                if (editingRow != null) button(t("cancel"), "btn quiet") {
                    App.editing = null; loadedFor = null; reset(); App.go(Tab.HISTORY)
                }
            }
            InstallHint.card()?.let { appendChild(it) }
            div("chips") {
                attr("role", "tablist")
                for ((k, label) in kinds) button(t(label), if (k == kind) "chip selected" else "chip") {
                    kind = k; categoryId = null; App.render()
                }
            }
            div("amount") {
                span("currency") { text(format.currency.code) }
                val input = input("text", amountText, "amount-input") {
                    attr("inputmode", "decimal")
                    attr("placeholder", "0")
                    attr("autocomplete", "off")
                    attr("aria-label", t("amount"))
                }
                input.on("input") { amountText = input.value; problem = null }
            }
            categoryTiles(view)
            if (accounts.size > 1) div("block") {
                label(t("account"))
                div("chips") {
                    for (a in accounts) button(a.name, if (a.id == accountId) "chip selected" else "chip") { accountId = a.id; App.render() }
                }
            }
            div("block") {
                label(t("date"))
                div("chips") {
                    button(t("today"), if (date == null && !anotherDay) "chip selected" else "chip") { date = null; anotherDay = false; App.render() }
                    button(t("yesterday"), if (date == today.minusDays(1)) "chip selected" else "chip") { date = today.minusDays(1); anotherDay = false; App.render() }
                    button(t("other_day"), if (anotherDay) "chip selected" else "chip") { anotherDay = true; if (date == null) date = today; App.render() }
                }
                if (anotherDay) {
                    val dateInput = input("date", chosenDate.toString(), "input") { attr("max", today.plusDays(366).toString()) }
                    dateInput.on("change") { runCatching { LocalDate.parse(dateInput.value) }.getOrNull()?.let { date = it } }
                }
            }
            div("block") {
                label(t("note"))
                val noteInput = input("text", note, "input") { attr("placeholder", t("note_hint")); attr("autocomplete", "off") }
                noteInput.on("input") { note = noteInput.value }
            }
            div("savebar") {
                problem?.let { child("p", "problem") { attr("role", "alert"); text(it) } }
                button(t("save"), "btn primary wide") { save(view, format, editingRow) }
            }
        }
    }

    private fun HTMLElement.categoryTiles(view: HouseholdView) {
        val config = view.config
        val all = config.categories.filter { !it.archived && it.appliesTo.allows(kind) }.sortedBy { it.sort }
        val top = all.filter { it.parentId == null }
        div("block") {
            label(t("category"))
            div("tiles") {
                for (c in top) tile(c)
            }
            val children = all.filter { it.parentId != null && (it.parentId == categoryId || it.id == categoryId || it.parentId == all.firstOrNull { p -> p.id == categoryId }?.parentId) }
            if (children.isNotEmpty()) div("chips sub") {
                for (c in children) button(c.name, if (c.id == categoryId) "chip selected" else "chip") { categoryId = c.id; App.render() }
            }
        }
    }

    private fun HTMLElement.tile(c: Category) {
        val selected = c.id == categoryId || (categoryId != null && c.id == (categoryOf(categoryId)?.parentId))
        child("button", if (selected) "tile selected" else "tile") {
            attr("type", "button")
            attr("aria-pressed", selected.toString())
            style.setProperty("--tile", "var(--cat-${c.colorIndex % 12})")
            span("tile-icon") { text(Icons.of(c.icon)) }
            span("tile-name") { text(c.name) }
            click { categoryId = c.id; App.render() }
        }
    }

    private fun categoryOf(id: String?): Category? = id?.let { wanted -> Ledger.view?.config?.categories?.firstOrNull { it.id == wanted } }

    private fun save(view: HouseholdView, format: Format, editingRow: Transaction?) {
        val config = view.config
        val minor = MoneyParser.parseTyped(amountText, format.currency, format.decimalStyle)
        if (minor == null || minor <= 0) { problem = t("amount"); App.render(); return }
        if (kind.isCategorised && categoryId == null) { problem = t("category"); App.render(); return }
        val everyone = config.activeMembers.map { it.id }
        val splits = kind == TransactionKind.EXPENSE || kind == TransactionKind.REFUND
        val base = editingRow ?: Transaction(id = randomUuid(), kind = kind, date = date ?: LocalDate.now(), amountMinor = 0, createdAt = "", clientUpdatedAt = "")
        val built = base.copy(
            kind = kind,
            date = date ?: LocalDate.now(),
            amountMinor = minor,
            categoryId = if (kind.isCategorised) categoryId else null,
            accountId = accountId,
            paidByMemberId = base.paidByMemberId ?: config.meMemberId,
            split = if (splits && everyone.size >= 2) (base.split ?: Split.Equal(everyone)) else null,
            note = note.trim(),
        )
        val problems = TransactionValidator.problems(built, config, isNew = editingRow == null)
        if (problems.isNotEmpty()) { problem = t("something_failed"); App.render(); return }
        App.launch {
            Ledger.save(built)
            val wasEditing = editingRow != null
            App.editing = null; loadedFor = null
            reset()
            App.toast(t("web_saved"))
            if (wasEditing) App.go(Tab.HISTORY) else App.render()
        }
    }
}
