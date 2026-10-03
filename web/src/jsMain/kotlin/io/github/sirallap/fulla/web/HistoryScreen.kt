// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.core.time.LocalDate
import org.w3c.dom.HTMLElement

/** Every entry, newest first, by day. Tap one to change it; delete is undoable. */
object HistoryScreen {
    private enum class Filter(val label: String) {
        ALL("filter_all"), SPENDING("filter_spending"), INCOME("filter_income"), MOVES("filter_moves"), DELETED("filter_deleted"), UNSENT("filter_unsent")
    }

    private var filter = Filter.ALL
    private var query = ""
    private var limit = 100

    fun build(view: HouseholdView, format: Format): HTMLElement {
        val config = view.config
        val normalized = query.trim().lowercase()
        val matching = view.rows.filter { r ->
            val t = r.transaction
            when (filter) {
                Filter.ALL -> t.isActive
                Filter.SPENDING -> t.isActive && (t.kind == TransactionKind.EXPENSE || t.kind == TransactionKind.REFUND)
                Filter.INCOME -> t.isActive && t.kind == TransactionKind.INCOME
                Filter.MOVES -> t.isActive && (t.kind == TransactionKind.TRANSFER || t.kind == TransactionKind.SETTLEMENT)
                Filter.DELETED -> !t.isActive
                Filter.UNSENT -> r.state == SyncState.PENDING
            } && (normalized.isEmpty() || t.note.lowercase().contains(normalized) || (config.category(t.categoryId)?.name ?: "").lowercase().contains(normalized))
        }.map { it.transaction }.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.createdAt })

        return el("main", "screen history") {
            tabHeader(t("tab_history"))
            div("field") {
                lateinit var box: HTMLElement
                div("searchbox") {
                    box = this
                    ui("search")
                    val search = input("search", query, "") { attr("placeholder", t("search")); attr("aria-label", t("search")); attr("autocomplete", "off") }
                    search.on("change") { query = search.value; limit = 100; App.render() }
                }
            }
            chipRow {
                for (f in Filter.entries) {
                    if (f == Filter.UNSENT && !view.connected) continue
                    chip(t(f.label), f == filter) { filter = f; limit = 100; App.render() }
                }
            }
            if (matching.isEmpty()) {
                div("empty") {
                    child("h2", "t-title") { text(t("empty_history_title")) }
                    child("p", "muted") { text(t("empty_history_text")) }
                }
            } else {
                var lastDay: LocalDate? = null
                for (row in matching.take(limit)) {
                    if (row.date != lastDay) {
                        lastDay = row.date
                        div("day") { span("t-section") { text(dayTitle(row.date, format)) } }
                    }
                    appendChild(entry(row, view, format))
                }
                if (matching.size > limit) div("actions") { secondaryButton(t("more")) { limit += 100; App.render() } }
            }
        }
    }

    private fun dayTitle(day: LocalDate, format: Format): String {
        val today = LocalDate.now()
        return when (day) {
            today -> t("today")
            today.minusDays(1) -> t("yesterday")
            else -> format.day(day)
        }
    }

    private fun entry(row: Transaction, view: HouseholdView, format: Format): HTMLElement {
        val category = view.config.category(row.categoryId)
        val payer = view.config.member(row.paidByMemberId)
        val deleted = !row.isActive
        val context = listOfNotNull(
            category?.name?.takeIf { row.note.isNotBlank() } ?: category?.name?.takeIf { false },
            view.config.account(row.accountId)?.name,
        ).joinToString(" · ")
        return el("div", "wrap-row") {
            listRow(
                title = row.note.ifBlank { category?.name ?: kindName(row.kind) },
                context = if (row.note.isBlank()) view.config.account(row.accountId)?.name else context,
                dim = deleted,
                start = { if (payer != null) badge(payer.initials, payer.colorIndex) else span("glyph") { style.setProperty("--tile", "var(--cat-${(category?.colorIndex ?: 0).mod(12)})"); categoryIcon(category?.icon ?: "label", 20) } },
                end = {
                    amountText(when (row.kind) {
                        TransactionKind.INCOME, TransactionKind.REFUND -> format.money(row.amountMinor, signed = true)
                        else -> format.money(row.amountMinor)
                    }, if (row.kind == TransactionKind.INCOME) "in" else "")
                    if (deleted) button(t("restore"), "chip") { App.launch { Ledger.undelete(row.id) } }
                    else child("button", "icon-btn") {
                        attr("type", "button"); attr("aria-label", t("delete")); ui("delete_outline", 22)
                        click {
                            App.launch {
                                Ledger.delete(row.id)
                                App.toast(t("entry_deleted"), t("undo")) { App.launch { Ledger.undelete(row.id) } }
                            }
                        }
                    }
                },
                onClick = if (!deleted && row.kind.isCategorised) ({ App.editing = row.id; App.go(Tab.ADD) }) else null,
            )
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
