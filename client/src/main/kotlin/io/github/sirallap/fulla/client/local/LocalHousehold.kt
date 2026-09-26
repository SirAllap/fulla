// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.local

import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.defaults.Defaults
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.MoneyMode
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

    /**
     * Household settings: the same fields fulla_household_update accepts.
     *
     * Mirrors that function's own check: a period that starts mid-month
     * (`period_start_day` > 1) and shifted income (`income_shift_day` set)
     * exclude each other. `MonthStart.toPatch` always sends both keys, one
     * explicitly null, so a caller that goes through it can never trip this;
     * a hand-built patch that would still leave both set is refused before
     * it reaches a household a `PeriodRule` would refuse to build.
     */
    fun updateHousehold(bundle: JsonObject, patch: JsonObject): JsonObject {
        val allowed = setOf("name", "locale", "period_start_day", "income_shift_day", "week_start", "currency", "member_limit", "money_mode")
        val h = bundle["household"] as JsonObject
        val merged = JsonObject(h + patch.filterKeys { it in allowed })
        val periodStartDay = (merged["period_start_day"] as? JsonPrimitive)?.intOrNull ?: 1
        val incomeShiftDay = (merged["income_shift_day"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.intOrNull
        require(periodStartDay <= 1 || incomeShiftDay == null) {
            "A period that starts mid-month and shifted income cannot both be on."
        }
        return bump(JsonObject(bundle + ("household" to merged)))
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

    // ── the pot chosen before sharing ────────────────────────────────────────

    /**
     * Kept in the stored bundle on this phone only, never sent: how money
     * works, chosen while the household lived here alone. The upload leaves
     * money_mode out, because its history arrives on the server as new rows
     * and a shared pot would rewrite them and refuse its settlements; the
     * phone sets it on the server once every one of those rows is in.
     */
    const val DEFERRED_MONEY_MODE = "deferred_money_mode"

    /** What fulla_household_create_from_local receives: the bundle without money_mode. */
    fun forUpload(bundle: JsonObject): JsonObject {
        val h = bundle["household"] as JsonObject
        return JsonObject(bundle - DEFERRED_MONEY_MODE + ("household" to JsonObject(h - "money_mode")))
    }

    /** The server's answer to the upload, remembering the pot [local] chose until its history is in. */
    fun afterUpload(local: JsonObject, server: JsonObject): JsonObject {
        val mode = Wire.config(local).household.moneyMode ?: return server
        return JsonObject(server + (DEFERRED_MONEY_MODE to JsonPrimitive(mode.key)))
    }

    fun deferredMoneyMode(bundle: JsonObject): MoneyMode? = MoneyMode.of(bundle.text(DEFERRED_MONEY_MODE))

    /** A config from the server taking the place of [stored]: a pot still waiting to be set survives it. */
    fun keepDeferred(stored: JsonObject?, incoming: JsonObject): JsonObject {
        val waiting = stored?.get(DEFERRED_MONEY_MODE) ?: return incoming
        return JsonObject(incoming + (DEFERRED_MONEY_MODE to waiting))
    }

    /** The household as the phone works with it: a pot waiting to be set already applies here. */
    fun config(bundle: JsonObject): Config {
        val c = Wire.config(bundle)
        val mode = deferredMoneyMode(bundle) ?: return c
        return c.copy(household = c.household.copy(moneyMode = mode))
    }

    /**
     * Sets a waiting pot on the server once nothing is left to send
     * ([unsent] is 0), through [update] (fulla_household_update). Returns the
     * bundle to store, without the waiting pot, or null if there is nothing
     * to do yet.
     */
    suspend fun applyDeferred(bundle: JsonObject, unsent: Int, update: suspend (JsonObject) -> JsonObject): JsonObject? {
        val mode = deferredMoneyMode(bundle) ?: return null
        if (unsent > 0) return null
        return JsonObject(update(buildJsonObject { put("money_mode", mode.key) }) - DEFERRED_MONEY_MODE)
    }

    fun version(bundle: JsonObject): Int = (bundle["config_version"] as? JsonPrimitive)?.intOrNull ?: 0

    private fun bump(bundle: JsonObject): JsonObject = JsonObject(bundle + ("config_version" to JsonPrimitive(version(bundle) + 1)))

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
