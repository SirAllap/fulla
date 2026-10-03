// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.local.CsvExport
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.schema.SchemaEngine
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.core.text.normalizeName
import io.github.sirallap.fulla.core.time.LocalDate
import org.w3c.dom.HTMLElement

/** Every row, newest first, grouped by day. Tap one to change, restore or delete it. */
object HistoryScreen {
    private enum class Filter(val label: String) {
        ALL("filter_all"), SPENDING("filter_spending"), INCOME("filter_income"), MOVES("filter_moves"), UNSENT("filter_unsent"), DELETED("filter_deleted");

        fun keeps(row: LocalTransaction): Boolean {
            val t = row.transaction
            if (this == DELETED) return t.status == Status.DELETED
            if (t.status == Status.DELETED) return false
            return when (this) {
                ALL -> true
                SPENDING -> t.kind == TransactionKind.EXPENSE || t.kind == TransactionKind.REFUND
                INCOME -> t.kind == TransactionKind.INCOME
                MOVES -> t.kind == TransactionKind.TRANSFER || t.kind == TransactionKind.SETTLEMENT
                UNSENT -> row.state == SyncState.PENDING || row.state == SyncState.REJECTED
                DELETED -> true
            }
        }
    }

    private var filter = Filter.ALL
    private var query = ""
    private var limit = 100

    fun build(view: HouseholdView, format: Format): HTMLElement {
        val menu = iconButton("more_vert", t("more")) {
            sheet(null) { close ->
                div("rows") {
                    listRow(t("import_statement"), start = leadIcon("file_upload")) { close(); App.openSettings(SettingsPage.IMPORT) }
                    listRow(t("export_csv"), start = leadIcon("file_download")) {
                        close()
                        App.launch { shareOrDownload("fulla-${LocalDate.now()}.csv", "text/csv", CsvExport.write(view.stored, view.rows.map { it.transaction })) }
                    }
                }
            }
        }
        return el("main", "screen history") {
            tabHeader(t("tab_history"), menu)
            lateinit var list: HTMLElement
            div("field") {
                div("searchbox") {
                    ui("search")
                    val search = input("search", query, "") { attr("placeholder", t("search")); attr("aria-label", t("search")); attr("autocomplete", "off") }
                    // The list changes as the person types; the field stays where it is.
                    search.on("input") { query = search.value; limit = 100; list.clear(); list.fill(view, format) }
                }
            }
            chipRow {
                for (f in Filter.entries) {
                    if (f == Filter.UNSENT && !view.connected) continue
                    chip(t(f.label), f == filter) { filter = f; limit = 100; App.render() }
                }
            }
            list = div("")
            list.fill(view, format)
        }
    }

    private fun HTMLElement.fill(view: HouseholdView, format: Format) {
        val q = query.trim().normalizeName()
        val shown = view.rows.filter { filter.keeps(it) }.filter { row ->
            if (q.isEmpty()) return@filter true
            val tx = row.transaction
            listOfNotNull(tx.note, view.config.category(tx.categoryId)?.name, view.config.account(tx.accountId)?.name, view.memberName(tx.paidByMemberId))
                .plus(tx.tags).any { it.normalizeName().contains(q) } || format.plain(tx.amountMinor).contains(query.trim())
        }.map { it.transaction.date to it }.sortedWith(compareByDescending<Pair<LocalDate, LocalTransaction>> { it.first }.thenByDescending { it.second.transaction.createdAt })
        if (shown.isEmpty()) { emptyState("receipt_long", t("empty_history_title"), t("empty_history_text")); return }
        var last: LocalDate? = null
        for ((day, row) in shown.take(limit)) {
            if (day != last) { last = day; section(dayTitle(day, format)) }
            entry(row, view, format)
        }
        if (shown.size > limit) div("actions") { secondaryButton(t("more")) { limit += 100; App.render() } }
    }

    private fun dayTitle(day: LocalDate, format: Format): String = format.day(day)

    private fun HTMLElement.entry(row: LocalTransaction, view: HouseholdView, format: Format) {
        val tx = row.transaction
        val config = view.config
        val category = config.category(tx.categoryId)
        val title = when (tx.kind) {
            TransactionKind.TRANSFER -> t("transfer_between", config.account(tx.accountId)?.name ?: "?", config.account(tx.toAccountId)?.name ?: "?")
            TransactionKind.SETTLEMENT -> t("settlement_between", view.memberName(tx.paidByMemberId), view.memberName(tx.toMemberId))
            else -> tx.note.ifBlank { category?.name ?: t("uncategorized") }
        }
        val context = (listOfNotNull(
            category?.name?.takeIf { tx.note.isNotBlank() },
            config.account(tx.accountId)?.name?.takeIf { tx.kind != TransactionKind.TRANSFER },
            t("kind_refund").takeIf { tx.kind == TransactionKind.REFUND },
        ) + SchemaEngine.fieldsForList(config.fields).mapNotNull { f -> fieldText(view, f, tx.extras[f.key], format) }).joinToString(" · ")
        val detail = when (row.state) {
            SyncState.REJECTED -> t("rejected_reason", row.rejectMessage ?: row.rejectCode ?: "")
            SyncState.PENDING -> t("not_sent_yet")
            else -> null
        }
        val payer = config.member(tx.paidByMemberId)
        val deleted = tx.status == Status.DELETED
        val several = config.activeMembers.size >= 2
        div("rows") {
            listRow(title, context = context.ifBlank { null }, detail = detail, dim = deleted,
                start = {
                    if (payer != null && several) {
                        // In one shared pot who paid is only who added it.
                        val label = t(if (SharedPot.isShared(config.household)) "added_by" else "paid_by", payer.displayName)
                        badge(payer.initials, payer.colorIndex).attr("aria-label", label)
                    } else span("glyph") {
                        style.setProperty("--tile", "var(--cat-${(category?.colorIndex ?: 0).mod(12)})")
                        categoryIcon(category?.icon ?: "label", 22)
                    }
                },
                end = {
                    val amount = when (tx.kind) { TransactionKind.INCOME, TransactionKind.REFUND -> format.money(tx.amountMinor, signed = true); else -> format.money(tx.amountMinor) }
                    amountText(amount, when {
                        deleted || tx.kind == TransactionKind.TRANSFER || tx.kind == TransactionKind.SETTLEMENT -> "muted"
                        tx.kind == TransactionKind.INCOME || tx.kind == TransactionKind.REFUND -> "in"
                        else -> ""
                    })
                },
                onClick = if (tx.kind.isCategorised || tx.kind == TransactionKind.TRANSFER) ({ App.editing = tx.id; App.go(Tab.ADD) }) else null)
        }
    }
}
