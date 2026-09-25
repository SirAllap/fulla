// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.local

import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.core.defaults.Defaults
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * A household that lives on this phone only (local mode).
 *
 * Its structure is kept as a config bundle of exactly the shape the server
 * sends, so every screen reads one format, and moving to a shared household
 * is one call that uploads this bundle as it is, ids and all.
 */
object LocalHousehold {

    fun create(
        name: String,
        currency: String,
        locale: String,
        displayName: String,
        initials: String,
        colorIndex: Int,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): JsonObject {
        val language = Defaults.languageOf(locale)
        val householdId = newId()
        val memberId = newId()
        return buildJsonObject {
            put("config_version", 1)
            put("household", buildJsonObject {
                put("id", householdId)
                put("name", name.trim())
                put("currency", currency.uppercase())
                put("locale", locale)
                put("period_start_day", 1)
                put("income_shift_day", JsonNull)
                put("week_start", 1)
                put("member_limit", 20)
                put("money_mode", JsonNull)
            })
            put("me_member_id", memberId)
            put("members", buildJsonArray {
                add(buildJsonObject {
                    put("id", memberId)
                    put("display_name", displayName.trim())
                    put("initials", initials.trim())
                    put("color_index", colorIndex)
                    put("role", "owner")
                    put("status", "active")
                    put("has_account", false)
                })
            })
            put("accounts", buildJsonArray {
                Defaults.accounts.forEachIndexed { i, a ->
                    add(buildJsonObject {
                        put("id", newId()); put("name", a.name(language)); put("type", a.type.key)
                        put("opening_balance_minor", 0); put("sort", i); put("archived", false)
                    })
                }
            })
            put("categories", buildJsonArray {
                (Defaults.categories + Defaults.uncategorized).forEachIndexed { i, c ->
                    add(buildJsonObject {
                        put("id", newId()); put("name", c.name(language)); put("applies_to", c.appliesTo.key)
                        put("parent_id", JsonNull); put("icon", c.icon); put("color_index", c.colorIndex)
                        put("sort", i); put("archived", false)
                    })
                }
            })
            for (key in listOf("custom_fields", "budgets", "recurring_rules", "categorization_rules", "import_profiles")) {
                put(key, JsonArray(emptyList()))
            }
        }
    }

    /**
     * Saves one item into the bundle, replacing the one with the same id (or
     * the same category and period, for budgets), and bumps the version.
     * Nothing is ever removed: archiving is a field of the item.
     */
    fun upsert(bundle: JsonObject, kind: Structure, item: JsonObject): JsonObject {
        val list = (bundle[kind.bundleKey] as? JsonArray).orEmpty()
        fun sameAs(o: JsonObject): Boolean = if (kind == Structure.BUDGET) {
            o.text("category_id") == item.text("category_id") && o.text("period") == item.text("period")
        } else {
            o.text("id") == item.text("id")
        }
        val at = list.indexOfFirst { (it as? JsonObject)?.let(::sameAs) == true }
        val merged = if (at >= 0) {
            val old = list[at] as JsonObject
            JsonObject(old + item + ("id" to (old["id"] ?: item["id"] ?: JsonNull)))
        } else item
        val next = if (at >= 0) list.toMutableList().also { it[at] = merged } else list + merged
        return bump(JsonObject(bundle + (kind.bundleKey to JsonArray(next))))
    }

    /** Household settings: the same fields fulla_household_update accepts. */
    fun updateHousehold(bundle: JsonObject, patch: JsonObject): JsonObject {
        val allowed = setOf("name", "locale", "period_start_day", "income_shift_day", "week_start", "currency", "member_limit", "money_mode")
        val h = bundle["household"] as JsonObject
        return bump(JsonObject(bundle + ("household" to JsonObject(h + patch.filterKeys { it in allowed }))))
    }

    fun upsertMember(bundle: JsonObject, member: JsonObject): JsonObject {
        val list = (bundle["members"] as? JsonArray).orEmpty()
        val id = member.text("id")
        val at = list.indexOfFirst { (it as? JsonObject)?.text("id") == id }
        val next = if (at >= 0) list.toMutableList().also { it[at] = JsonObject((list[at] as JsonObject) + member) }
        else list + JsonObject(mapOf("role" to JsonPrimitive("member"), "status" to JsonPrimitive("active"),
            "has_account" to JsonPrimitive(false)) + member)
        return bump(JsonObject(bundle + ("members" to JsonArray(next))))
    }

    /**
     * Copies one month's budgets into another, the way fulla_budget_copy
     * does: each category's budget for [from] (or its default) becomes an
     * override for [to], and an override [to] already has is left alone.
     */
    fun copyBudgets(bundle: JsonObject, from: String, to: String, newId: () -> String = { UUID.randomUUID().toString() }): JsonObject {
        val budgets = (bundle["budgets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun of(period: String?) = budgets.filter { it.text("period") == period }.associateBy { it.text("category_id") }
        val explicit = of(from)
        val defaults = of(null)
        val existing = of(to)
        val added = ((explicit.keys + defaults.keys) - existing.keys).mapNotNull { category ->
            val source = explicit[category] ?: defaults[category] ?: return@mapNotNull null
            buildJsonObject {
                put("id", newId()); put("category_id", category); put("period", to); put("amount_minor", source["amount_minor"] ?: JsonPrimitive(0))
            }
        }
        if (added.isEmpty()) return bundle
        return bump(JsonObject(bundle + ("budgets" to JsonArray(budgets + added))))
    }

    fun version(bundle: JsonObject): Int = (bundle["config_version"] as? JsonPrimitive)?.intOrNull ?: 0

    private fun bump(bundle: JsonObject): JsonObject = JsonObject(bundle + ("config_version" to JsonPrimitive(version(bundle) + 1)))

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
