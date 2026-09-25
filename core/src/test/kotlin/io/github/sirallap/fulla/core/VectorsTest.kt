// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.balance.Balances
import io.github.sirallap.fulla.core.defaults.Defaults
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.money.Currencies
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.split.Allocator
import io.github.sirallap.fulla.core.text.normalizeName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rules that exist in the database too, run against the same vectors the
 * database suite runs (supabase/tests/run.js). If one side changes, the other
 * side's tests go red until it follows.
 */
class VectorsTest {

    private fun vectors(name: String) = Fixtures.json("testdata/vectors/$name").jsonObject["vectors"]!!.jsonArray

    private fun split(e: JsonElement?): Split? {
        if (e == null || e is JsonNull) return null
        val o = e.jsonObject
        return when (o["mode"]!!.jsonPrimitive.content) {
            "equal" -> Split.Equal(o["members"]!!.jsonArray.map { it.jsonPrimitive.content })
            "shares" -> Split.Shares(o["shares"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int })
            else -> Split.Exact(o["amounts"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long })
        }
    }

    @Test
    fun `period rule agrees with period json`() {
        for (v in vectors("period.json")) {
            val i = v.jsonObject["input"]!!.jsonObject
            val rule = PeriodRule(i["period_start_day"]!!.jsonPrimitive.int, i["income_shift_day"]?.jsonPrimitive?.intOrNull)
            val got = rule.label(
                LocalDate.parse(i["date"]!!.jsonPrimitive.content),
                TransactionKind.of(i["kind"]!!.jsonPrimitive.content)!!,
                Recurrence.of(i["recurrence"]!!.jsonPrimitive.content),
            )
            assertEquals(v.jsonObject["expected"]!!.jsonPrimitive.content, got, v.jsonObject["why"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `allocator agrees with allocate json`() {
        for (v in vectors("allocate.json")) {
            val i = v.jsonObject["input"]!!.jsonObject
            val amount = i["amount_minor"]!!.jsonPrimitive.long
            val got = Allocator.allocate(amount, split(i["split"])!!)
            val expected = v.jsonObject["expected"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long }
            assertEquals(expected, got, v.jsonObject["why"]!!.jsonPrimitive.content)
            assertEquals(amount, got.values.sum())
        }
    }

    @Test
    fun `balances agree with balance json`() {
        for (v in vectors("balance.json")) {
            val i = v.jsonObject["input"]!!.jsonObject
            val members = i["members"]!!.jsonArray.map { it.jsonPrimitive.content }
            val txs = i["transactions"]!!.jsonArray.map { e ->
                val t = e.jsonObject
                Transaction(
                    id = Fixtures.newId(), kind = TransactionKind.of(t["kind"]!!.jsonPrimitive.content)!!,
                    date = LocalDate.of(2030, 1, 15), amountMinor = t["amount_minor"]!!.jsonPrimitive.long,
                    paidByMemberId = (t["paid_by"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                    toMemberId = (t["to_member"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                    split = split(t["split"]), createdAt = "2030-01-15T00:00:00.000Z", clientUpdatedAt = "2030-01-15T00:00:00.000Z",
                )
            }
            val got = Balances.of(txs, members).associateBy { it.memberId }
            for ((id, want) in v.jsonObject["expected"]!!.jsonObject) {
                val w = want.jsonObject
                val b = got.getValue(id)
                assertEquals(
                    listOf(w["paid"]!!.jsonPrimitive.long, w["share"]!!.jsonPrimitive.long, w["balance"]!!.jsonPrimitive.long),
                    listOf(b.paidMinor, b.shareMinor, b.balanceMinor),
                    v.jsonObject["why"]!!.jsonPrimitive.content,
                )
            }
            assertEquals(0L, got.values.sumOf { it.balanceMinor })
        }
    }

    @Test
    fun `name normalisation agrees with normalize_name json`() {
        for (v in vectors("normalize_name.json")) {
            val input = v.jsonObject["input"]!!.jsonPrimitive.content
            assertEquals(v.jsonObject["expected"]!!.jsonPrimitive.content, input.normalizeName(), input)
        }
    }

    @Test
    fun `currency table is exactly currencies json`() {
        val file = Fixtures.json("testdata/defaults/currencies.json").jsonObject["minor_units"]!!.jsonObject
            .mapValues { it.value.jsonPrimitive.int }
        assertEquals(file, Currencies.MINOR_UNITS)
    }

    @Test
    fun `defaults are exactly categories json`() {
        val file = Fixtures.json("testdata/defaults/categories.json").jsonObject
        fun names(o: JsonObject) = o["name"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val categories = file["categories"]!!.jsonArray.map { it.jsonObject }
        assertEquals(categories.map { it["key"]!!.jsonPrimitive.content }, Defaults.categories.map { it.key })
        categories.zip(Defaults.categories).forEach { (f, d) ->
            assertEquals(names(f), d.names)
            assertEquals(f["icon"]!!.jsonPrimitive.content, d.icon)
            assertEquals(f["color_index"]!!.jsonPrimitive.int, d.colorIndex)
            assertEquals(f["applies_to"]!!.jsonPrimitive.content, d.appliesTo.key)
        }
        val accounts = file["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(accounts.map { names(it) }, Defaults.accounts.map { it.names })
        assertEquals(accounts.map { it["type"]!!.jsonPrimitive.content }, Defaults.accounts.map { it.type.key })
        assertEquals(names(file["uncategorized"]!!.jsonObject), Defaults.uncategorized.names)
    }
}
