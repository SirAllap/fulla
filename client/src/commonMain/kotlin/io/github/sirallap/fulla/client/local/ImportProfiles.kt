// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.local

import io.github.sirallap.fulla.core.importers.AmountColumns
import io.github.sirallap.fulla.core.importers.ImportProfile
import io.github.sirallap.fulla.core.money.DecimalStyle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A saved CSV column mapping, so a bank's file is mapped once, not every month. */
data class SavedProfile(val id: String, val name: String, val accountId: String?, val profile: ImportProfile)

/** Saved profiles to and from the shape fulla.save_import_profile stores. */
object ImportProfiles {

    fun toJson(p: SavedProfile): JsonObject = buildJsonObject {
        put("id", p.id)
        put("name", p.name)
        put("format", "csv")
        put("account_id", p.accountId)
        put("mapping", buildJsonObject {
            p.profile.encoding?.let { put("encoding", it) }
            put("delimiter", p.profile.delimiter.toString())
            put("skip_rows", p.profile.skipRows)
            put("has_header", p.profile.hasHeader)
            put("date_column", p.profile.dateColumn)
            put("date_format", p.profile.dateFormat)
            put("decimal", p.profile.decimal.decimal.toString())
            put("amount", when (val a = p.profile.amount) {
                is AmountColumns.Single -> buildJsonObject {
                    put("mode", "single"); put("column", a.column); put("negative_is_expense", a.negativeIsExpense)
                }
                is AmountColumns.Split -> buildJsonObject {
                    put("mode", "split"); put("debit_column", a.debitColumn); put("credit_column", a.creditColumn)
                }
            })
            put("description_columns", JsonArray(p.profile.descriptionColumns.map(::JsonPrimitive)))
            put("category_column", p.profile.categoryColumn)
            put("kind_column", p.profile.kindColumn)
            put("income_values", JsonArray(p.profile.incomeValues.map(::JsonPrimitive)))
            put("payer_column", p.profile.payerColumn)
            put("account_column", p.profile.accountColumn)
            put("fixed_column", p.profile.fixedColumn)
            put("fixed_values", JsonArray(p.profile.fixedValues.map(::JsonPrimitive)))
        })
    }

    fun fromJson(o: JsonObject): SavedProfile? = runCatching {
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
        val m = o["mapping"] as JsonObject
        val a = m["amount"] as JsonObject
        val amount = if (a.s("mode") == "split") AmountColumns.Split(a.i("debit_column")!!, a.i("credit_column")!!)
        else AmountColumns.Single(a.i("column")!!, (a["negative_is_expense"] as? JsonPrimitive)?.booleanOrNull ?: true)
        SavedProfile(
            id = o.s("id")!!, name = o.s("name")!!, accountId = o.s("account_id"),
            profile = ImportProfile(
                encoding = m.s("encoding"),
                delimiter = m.s("delimiter")?.firstOrNull() ?: ',',
                skipRows = m.i("skip_rows") ?: 0,
                hasHeader = (m["has_header"] as? JsonPrimitive)?.booleanOrNull ?: true,
                dateColumn = m.i("date_column")!!,
                dateFormat = m.s("date_format")!!,
                amount = amount,
                decimal = if (m.s("decimal") == ",") DecimalStyle.COMMA else DecimalStyle.DOT,
                descriptionColumns = (m["description_columns"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty(),
                categoryColumn = m.i("category_column"),
                kindColumn = m.i("kind_column"),
                incomeValues = (m["income_values"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                payerColumn = m.i("payer_column"),
                accountColumn = m.i("account_column"),
                fixedColumn = m.i("fixed_column"),
                fixedValues = (m["fixed_values"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            ),
        )
    }.getOrNull()
}
