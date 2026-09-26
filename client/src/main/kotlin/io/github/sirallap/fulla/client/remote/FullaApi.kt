// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

import io.github.sirallap.fulla.client.sync.SyncBackend
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.sync.Mutation
import io.github.sirallap.fulla.core.sync.PushResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A household the signed-in person belongs to. */
data class Membership(val householdId: String, val name: String, val currency: String, val memberId: String, val role: String)

/** What creating or joining a household answers with. */
data class Joined(val householdId: String, val memberId: String, val config: JsonObject)

/** A new invite. The code is shown once, as text and as a QR code. */
data class Invite(val code: String, val expiresAt: String, val role: String, val claimMemberId: String?)

/** Why an invite code did not work. */
enum class InviteProblem { INVALID, EXPIRED, USED, ALREADY_MEMBER, HOUSEHOLD_FULL }

class InviteRefused(val problem: InviteProblem) : Exception(problem.name)

/**
 * One method per database function (docs/api.md). Arguments are named as the
 * function's parameters, results are the database's JSON.
 */
class FullaApi(private val supabase: Supabase) : SyncBackend {

    private suspend fun call(function: String, vararg args: Pair<String, Any?>): JsonElement =
        supabase.rpc(function, JsonObject(args.associate { it.first to Wire.element(it.second) }))

    private suspend fun obj(function: String, vararg args: Pair<String, Any?>): JsonObject =
        call(function, *args) as? JsonObject ?: throw FullaError(FullaError.BAD_RESPONSE, "Unexpected answer from $function.")

    // ── system ───────────────────────────────────────────────────────────────

    /** Checks the URL and key reach a Fulla database. Returns its API version. */
    suspend fun ping(): Int = ((call("fulla_ping") as? JsonObject)?.get("api_version") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        ?: throw FullaError(FullaError.BAD_RESPONSE, "This project does not have Fulla's database set up.")

    suspend fun myHouseholds(): List<Membership> = Wire.list(call("fulla_my_households")).map {
        Membership(it.s("household_id"), it.s("name"), it.s("currency"), it.s("member_id"), it.s("role"))
    }

    /**
     * Deletes the signed-in account: households only this person used are
     * erased, the others keep their history. The owner of a household others
     * use gets `owner_must_hand_over`.
     */
    suspend fun accountDelete() {
        call("fulla_account_delete")
    }

    // ── households, members, invites ─────────────────────────────────────────

    suspend fun householdCreate(name: String, currency: String, locale: String, displayName: String, initials: String, colorIndex: Int): Joined =
        joined(obj("fulla_household_create", "p_name" to name, "p_currency" to currency, "p_locale" to locale,
            "p_display_name" to displayName, "p_initials" to initials, "p_color_index" to colorIndex))

    /** Uploads a household that so far lived on this phone only, keeping every id. */
    suspend fun householdCreateFromLocal(bundle: JsonObject): Joined = joined(obj("fulla_household_create_from_local", "p_payload" to bundle))

    suspend fun householdUpdate(householdId: String, patch: JsonObject): JsonObject =
        obj("fulla_household_update", "p_household_id" to householdId, "p_patch" to patch)

    suspend fun inviteCreate(householdId: String, role: String, claimMemberId: String?, ttlHours: Int): Invite {
        val o = obj("fulla_invite_create", "p_household_id" to householdId, "p_role" to role,
            "p_claim_member_id" to claimMemberId, "p_ttl_hours" to ttlHours)
        return Invite(o.s("code"), o.s("expires_at"), o.s("role"), o.n("claim_member_id"))
    }

    suspend fun inviteList(householdId: String): List<Invite> =
        Wire.list(call("fulla_invite_list", "p_household_id" to householdId)).map {
            Invite(it.s("code"), it.s("expires_at"), it.s("role"), it.n("claim_member_id"))
        }

    suspend fun inviteRevoke(householdId: String, code: String) {
        call("fulla_invite_revoke", "p_household_id" to householdId, "p_code" to code)
    }

    suspend fun inviteAccept(code: String, displayName: String, initials: String, colorIndex: Int): Joined {
        val o = obj("fulla_invite_accept", "p_code" to code, "p_display_name" to displayName,
            "p_initials" to initials, "p_color_index" to colorIndex)
        if (o["ok"] == JsonPrimitive(false)) {
            throw InviteRefused(when (o.n("error")) {
                "invite_expired" -> InviteProblem.EXPIRED
                "invite_used" -> InviteProblem.USED
                "already_member" -> InviteProblem.ALREADY_MEMBER
                "household_full" -> InviteProblem.HOUSEHOLD_FULL
                else -> InviteProblem.INVALID
            })
        }
        return joined(o)
    }

    suspend fun memberCreateVirtual(householdId: String, member: JsonObject): JsonObject =
        obj("fulla_member_create_virtual", "p_household_id" to householdId, "p_member" to member)

    suspend fun memberUpdate(householdId: String, memberId: String, patch: JsonObject): JsonObject =
        obj("fulla_member_update", "p_household_id" to householdId, "p_member_id" to memberId, "p_patch" to patch)

    suspend fun memberSetRole(householdId: String, memberId: String, role: String): JsonObject =
        obj("fulla_member_set_role", "p_household_id" to householdId, "p_member_id" to memberId, "p_role" to role)

    suspend fun memberRemove(householdId: String, memberId: String): JsonObject =
        obj("fulla_member_remove", "p_household_id" to householdId, "p_member_id" to memberId)

    suspend fun memberLeave(householdId: String) {
        call("fulla_member_leave", "p_household_id" to householdId)
    }

    suspend fun ownerTransfer(householdId: String, memberId: String): JsonObject =
        obj("fulla_owner_transfer", "p_household_id" to householdId, "p_member_id" to memberId)

    // ── structure ────────────────────────────────────────────────────────────

    suspend fun configGet(householdId: String): JsonObject = obj("fulla_config_get", "p_household_id" to householdId)

    /**
     * Saves one piece of structure and returns the new config bundle.
     * [kind] is the function's middle word: account, category, field,
     * budget, recurring, rule or import_profile.
     */
    suspend fun upsert(householdId: String, kind: Structure, value: JsonObject): JsonObject =
        obj("fulla_${kind.function}_upsert", "p_household_id" to householdId, "p_${kind.argument}" to value)

    suspend fun budgetCopy(householdId: String, fromPeriod: String, toPeriod: String): JsonObject =
        obj("fulla_budget_copy", "p_household_id" to householdId, "p_from_period" to fromPeriod, "p_to_period" to toPeriod)

    // ── sync ─────────────────────────────────────────────────────────────────

    override suspend fun push(householdId: String, mutations: List<Mutation>): List<PushResult> {
        val o = obj("fulla_sync_push", "p_household_id" to householdId, "p_mutations" to JsonArray(mutations.map(Wire::mutation)))
        return Wire.list(o["results"]).map(Wire::pushResult)
    }

    override suspend fun pull(householdId: String, since: Long, configVersion: Int, limit: Int): Wire.PullPage =
        Wire.pullPage(obj("fulla_sync_pull", "p_household_id" to householdId, "p_since" to since,
            "p_config_version" to configVersion, "p_limit" to limit), since)

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun joined(o: JsonObject) = Joined(o.s("household_id"), o.s("member_id"),
        o["config"] as? JsonObject ?: throw FullaError(FullaError.BAD_RESPONSE, "The answer carried no config."))

    private fun JsonObject.n(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.s(key: String): String = n(key) ?: ""
}

/** The structure functions, by the words in their names and first argument. */
enum class Structure(val function: String, val argument: String, val bundleKey: String) {
    ACCOUNT("account", "account", "accounts"),
    CATEGORY("category", "category", "categories"),
    FIELD("field", "field", "custom_fields"),
    BUDGET("budget", "budget", "budgets"),
    RECURRING("recurring", "rule", "recurring_rules"),
    RULE("rule", "rule", "categorization_rules"),
    IMPORT_PROFILE("import_profile", "profile", "import_profiles"),
    TRIP("trip", "trip", "trips"),
}
