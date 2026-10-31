// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.goals.Goal
import io.github.sirallap.fulla.core.goals.GoalEntry
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalWireTest {
    private val savings = "00000000-0000-4000-8000-000000000501"
    private val id = "00000000-0000-4000-8000-000000000502"

    private fun household() = LocalHousehold.upsert(LocalHousehold.create("Demo household", "EUR", "en-GB", "Alice", "A", 0), Structure.ACCOUNT,
        buildJsonObject { put("id", savings); put("name", "Savings"); put("type", "savings"); put("sort", 8); put("archived", false) })

    @Test
    fun `a goal saved on a phone-only household is read back, and a bundle without goals has none`() {
        assertTrue(Wire.config(household()).goals.isEmpty())
        val goal = Goal(id, "Summer", 160_000, savings, LocalDate.of(2030, 6, 30))
        val saved = LocalHousehold.upsert(household(), Structure.GOAL, Wire.goal(goal))
        assertEquals(listOf(goal), Wire.config(saved).goals)
    }

    @Test
    fun `a goal without a date writes an explicit null, and is read back without one`() {
        val goal = Goal(id, "Rainy day", 300_000, savings)
        val json = Wire.goal(goal)
        assertEquals(JsonNull, json["target_date"])
        assertEquals(goal, Wire.goal(json))
    }

    @Test
    fun `the whole config written and read again keeps its goals`() {
        val goal = Goal(id, "Summer", 160_000, savings, LocalDate.of(2030, 6, 30), archived = true)
        val config = Wire.config(LocalHousehold.upsert(household(), Structure.GOAL, Wire.goal(goal)))
        assertEquals(config.goals, Wire.config(Wire.bundle(config)).goals)
    }

    @Test
    fun `a goal with a jar of its own keeps its entries, taken-back ones included, through a phone-only household`() {
        val goal = Goal(id, "New car", 900_000, accountId = null, contributions = listOf(
            GoalEntry("00000000-0000-4000-8000-000000000601", LocalDate.of(2030, 1, 5), 20_000, memberId = "m1"),
            GoalEntry("00000000-0000-4000-8000-000000000602", LocalDate.of(2030, 1, 20), 15_000, note = "Bonus", deleted = true),
            GoalEntry("00000000-0000-4000-8000-000000000603", LocalDate.of(2030, 2, 2), -5_000),
        ))
        assertEquals(JsonNull, Wire.goal(goal)["account_id"])
        val saved = LocalHousehold.upsert(household(), Structure.GOAL, Wire.goal(goal))
        val back = Wire.config(saved).goals.single()
        assertEquals(goal, back)
        assertEquals(15_000, back.jarMinor)
    }
}
