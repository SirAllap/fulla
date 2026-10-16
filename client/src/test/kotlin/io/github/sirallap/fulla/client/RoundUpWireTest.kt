// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.core.model.RoundUp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoundUpWireTest {
    private val card = "00000000-0000-4000-8000-000000000401"
    private val orange = "00000000-0000-4000-8000-000000000402"

    private fun start() = LocalHousehold.upsert(
        LocalHousehold.upsert(LocalHousehold.create("Demo household", "EUR", "en-GB", "Alice", "A", 0), Structure.ACCOUNT,
            buildJsonObject { put("id", orange); put("name", "Orange"); put("type", "savings"); put("sort", 8); put("archived", false) }),
        Structure.ACCOUNT, buildJsonObject {
            put("id", card); put("name", "Card"); put("type", "checking"); put("sort", 9); put("archived", false)
            put("round_up_to", orange); put("round_up_multiplier", 5)
        })

    @Test
    fun `an account's round-up is read from the household's config`() {
        val account = Wire.config(start()).account(card)!!
        assertEquals(RoundUp(orange, 5), account.roundUp)
        assertNull(Wire.config(start()).account(orange)!!.roundUp)
    }

    @Test
    fun `renaming an account keeps its round-up, and an explicit null turns it off`() {
        val renamed = LocalHousehold.upsert(start(), Structure.ACCOUNT, buildJsonObject { put("id", card); put("name", "Card 2"); put("type", "checking") })
        assertEquals(RoundUp(orange, 5), Wire.config(renamed).account(card)!!.roundUp)
        val off = LocalHousehold.upsert(renamed, Structure.ACCOUNT, buildJsonObject {
            put("id", card); put("name", "Card 2"); put("type", "checking"); put("round_up_to", JsonNull); put("round_up_multiplier", JsonNull)
        })
        assertNull(Wire.config(off).account(card)!!.roundUp)
    }

    @Test
    fun `a multiplier no bank offers reads as off`() {
        val odd = LocalHousehold.upsert(start(), Structure.ACCOUNT, buildJsonObject { put("id", card); put("round_up_to", orange); put("round_up_multiplier", 3) })
        assertNull(Wire.config(odd).account(card)!!.roundUp)
    }

    @Test
    fun `an account is written with its round-up, or with explicit nulls when it has none`() {
        val on = Wire.account(Account(card, "Card", AccountType.CHECKING, roundUp = RoundUp(orange, 2)))
        assertEquals(JsonPrimitive(orange), on["round_up_to"])
        assertEquals(JsonPrimitive(2), on["round_up_multiplier"])
        val off = Wire.account(Account(card, "Card", AccountType.CHECKING))
        assertEquals(JsonNull, off["round_up_to"])
        assertEquals(JsonNull, off["round_up_multiplier"])
    }
}
