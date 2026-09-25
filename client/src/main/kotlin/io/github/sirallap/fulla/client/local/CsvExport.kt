// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.local

import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.money.Currency
import java.math.BigDecimal

/**
 * Every transaction as a CSV file anyone can open: the way out of Fulla, so
 * nobody's history is ever held hostage by it.
 *
 * Amounts are plain decimals with a dot and no grouping, whatever the phone's
 * language, because a file is for other programs; names are written out next
 * to ids so a person can read it and a program can rejoin it. Custom fields
 * are one JSON column. RFC 4180 quoting; UTF-8 with a byte-order mark so
 * spreadsheet programs open accents correctly.
 */
object CsvExport {

    val HEADER = listOf(
        "date", "kind", "amount", "currency", "category", "account", "to_account", "paid_by", "to_member",
        "split", "recurrence", "note", "tags", "status", "custom_fields", "id", "created_at", "updated_at",
    )

    fun write(config: Config, rows: List<Transaction>, includeDeleted: Boolean = false): String {
        val currency = Currency.of(config.household.currency) ?: Currency(config.household.currency, 2)
        fun amount(minor: Long) = BigDecimal.valueOf(minor, currency.minorUnits).toPlainString()
        fun member(id: String?) = config.member(id)?.displayName ?: ""
        fun split(s: Split?): String = when (s) {
            null -> ""
            is Split.Equal -> "equal: " + s.members.joinToString(", ") { member(it) }
            is Split.Shares -> "shares: " + s.shares.entries.joinToString(", ") { "${member(it.key)} ${it.value}" }
            is Split.Exact -> "exact: " + s.amounts.entries.joinToString(", ") { "${member(it.key)} ${amount(it.value)}" }
        }
        val out = StringBuilder("\uFEFF")
        out.append(HEADER.joinToString(",")).append("\r\n")
        for (t in rows.filter { includeDeleted || it.isActive }.sortedWith(compareBy({ it.date }, { it.createdAt }, { it.id }))) {
            val cells = listOf(
                t.date.toString(), t.kind.key, amount(t.amountMinor), currency.code,
                config.category(t.categoryId)?.name ?: "", config.account(t.accountId)?.name ?: "",
                config.account(t.toAccountId)?.name ?: "", member(t.paidByMemberId), member(t.toMemberId),
                split(t.split), t.recurrence.key, t.note, t.tags.joinToString(", "), t.status.key,
                if (t.extras.isEmpty()) "" else kotlinx.serialization.json.JsonObject(t.extras.mapValues { Wire.element(it.value) }).toString(),
                t.id, t.createdAt, t.clientUpdatedAt,
            )
            out.append(cells.joinToString(",", transform = ::quote)).append("\r\n")
        }
        return out.toString()
    }

    fun quote(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
}
