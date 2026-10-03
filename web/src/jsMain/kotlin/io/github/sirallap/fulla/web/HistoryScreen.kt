// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import org.w3c.dom.HTMLElement

/** Every entry, newest first. Tap one to change it; delete is undoable. */
object HistoryScreen {
    private enum class Filter(val label: String) { ALL("filter_all"), SPENDING("filter_spending"), INCOME("filter_income"), DELETED("filter_deleted") }

    private var filter = Filter.ALL
    private var query = ""
    private var limit = 100

    fun build(view: HouseholdView, format: Format): HTMLElement {
        val config = view.config
        val normalized = query.trim().lowercase()
        val matching = view.rows.map { it.transaction }.filter { t ->
            when (filter) {
                Filter.ALL -> t.isActive
                Filter.SPENDING -> t.isActive && (t.kind == TransactionKind.EXPENSE || t.kind == TransactionKind.REFUND)
                Filter.INCOME -> t.isActive && t.kind == TransactionKind.INCOME
                Filter.DELETED -> !t.isActive
            } && (normalized.isEmpty() || t.note.lowercase().contains(normalized) || (config.category(t.categoryId)?.name ?: "").lowercase().contains(normalized))
        }.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.createdAt })

        return el("main", "screen history") {
            div("header") { child("h1", "title") { text(t("tab_history")) } }
            val search = input("search", query, "input search") {
                attr("placeholder", t("search"))
                attr("aria-label", t("search"))
                attr("autocomplete", "off")
            }
            search.on("change") { query = search.value; limit = 100; App.render() }
            div("chips") {
                for (f in Filter.entries) button(t(f.label), if (f == filter) "chip selected" else "chip") { filter = f; limit = 100; App.render() }
            }
            if (matching.isEmpty()) {
                div("empty") {
                    child("h2") { text(t("empty_history_title")) }
                    child("p", "muted") { text(t("empty_history_text")) }
                }
            } else {
                var lastDay: io.github.sirallap.fulla.core.time.LocalDate? = null
                for (row in matching.take(limit)) {
                    if (row.date != lastDay) {
                        lastDay = row.date
                        child("h2", "day") { text(dayTitle(row.date, format)) }
                    }
                    appendChild(entry(row, view, format))
                }
                if (matching.size > limit) button(t("more"), "btn secondary wide") { limit += 100; App.render() }
            }
        }
    }

    private fun dayTitle(day: io.github.sirallap.fulla.core.time.LocalDate, format: Format): String {
        val today = io.github.sirallap.fulla.core.time.LocalDate.now()
        return when (day) {
            today -> t("today")
            today.minusDays(1) -> t("yesterday")
            else -> format.day(day)
        }
    }

    private fun entry(row: Transaction, view: HouseholdView, format: Format): HTMLElement {
        val category = view.config.category(row.categoryId)
        val deleted = !row.isActive
        return el("div", if (deleted) "entry deleted" else "entry") {
            style.setProperty("--tile", "var(--cat-${(category?.colorIndex ?: 0) % 12})")
            child("button", "entry-main") {
                attr("type", "button")
                span("tile-icon") { text(Icons.of(category?.icon ?: "label")) }
                div("entry-text") {
                    span("name") { text(row.note.ifBlank { category?.name ?: kindName(row.kind) }) }
                    span("muted small") { text(listOfNotNull(category?.name?.takeIf { row.note.isNotBlank() }, view.config.account(row.accountId)?.name?.takeIf { view.config.accounts.size > 1 }).joinToString(" · ")) }
                }
                span(if (row.kind == TransactionKind.INCOME) "amount in" else "amount") {
                    text(when (row.kind) {
                        TransactionKind.INCOME, TransactionKind.REFUND -> format.money(row.amountMinor, signed = true)
                        TransactionKind.EXPENSE -> format.money(-row.amountMinor)
                        else -> format.money(row.amountMinor)
                    })
                }
                if (!deleted && row.kind.isCategorised) click { App.editing = row.id; App.go(Tab.ADD) }
            }
            if (deleted) button(t("restore"), "btn small secondary") { App.launch { Ledger.undelete(row.id) } }
            else child("button", "btn icon ghost") {
                attr("type", "button")
                attr("aria-label", t("delete"))
                icon("M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14M10 11v6M14 11v6", 20)
                click {
                    App.launch {
                        Ledger.delete(row.id)
                        App.toast(t("entry_deleted"), t("undo")) { App.launch { Ledger.undelete(row.id) } }
                    }
                }
            }
        }
    }

    private fun kindName(kind: TransactionKind): String = when (kind) {
        TransactionKind.EXPENSE -> t("kind_expense")
        TransactionKind.INCOME -> t("kind_income")
        TransactionKind.REFUND -> t("kind_refund")
        TransactionKind.TRANSFER -> t("kind_transfer")
        else -> t("settled")
    }
}
