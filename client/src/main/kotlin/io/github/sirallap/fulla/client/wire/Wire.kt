// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.wire

import io.github.sirallap.fulla.core.importers.CategorizationRule
import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.core.model.AppliesTo
import io.github.sirallap.fulla.core.model.Budget
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Household
import io.github.sirallap.fulla.core.model.MoneyMode
import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.MemberStatus
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Role
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import io.github.sirallap.fulla.core.schema.CustomField
import io.github.sirallap.fulla.core.schema.FieldType
import io.github.sirallap.fulla.core.sync.Conflict
import io.github.sirallap.fulla.core.sync.FieldChange
import io.github.sirallap.fulla.core.sync.Mutation
import io.github.sirallap.fulla.core.sync.PushResult
import io.github.sirallap.fulla.core.trips.Trip
import io.github.sirallap.fulla.core.trips.TripKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * The wire format, both ways. It is the database's JSON exactly
 * (docs/api.md), so the same functions read a pull, write a push, and store
 * the config bundle on the phone.
 */
object Wire {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.arr(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** A JSON value as the plain Kotlin value custom fields hold. */
    fun plain(e: JsonElement?): Any? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            else -> e.content.toBigDecimalOrNull() ?: e.content
        }
        is JsonArray -> e.map { plain(it) }
        is JsonObject -> e.mapValues { plain(it.value) }
    }

    fun element(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { element(it) })
        is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to element(it.value) })
        else -> JsonPrimitive(v.toString())
    }

    // ── splits ───────────────────────────────────────────────────────────────

    fun split(e: JsonElement?): Split? {
        val o = e as? JsonObject ?: return null
        return when (o.str("mode")) {
            "equal" -> Split.Equal(o.arr("members").map { it.jsonPrimitive.content })
            "shares" -> Split.Shares(o.obj("shares")?.mapValues { it.value.jsonPrimitive.intOrNull ?: 1 } ?: emptyMap())
            "exact" -> Split.Exact(o.obj("amounts")?.mapValues { it.value.jsonPrimitive.longOrNull ?: 0 } ?: emptyMap())
            else -> null
        }
    }

    fun split(s: Split?): JsonElement = when (s) {
        null -> JsonNull
        is Split.Equal -> buildJsonObject { put("mode", "equal"); put("members", JsonArray(s.members.map(::JsonPrimitive))) }
        is Split.Shares -> buildJsonObject { put("mode", "shares"); put("shares", JsonObject(s.shares.mapValues { JsonPrimitive(it.value) })) }
        is Split.Exact -> buildJsonObject { put("mode", "exact"); put("amounts", JsonObject(s.amounts.mapValues { JsonPrimitive(it.value) })) }
    }

    // ── transactions ─────────────────────────────────────────────────────────

    fun transaction(o: JsonObject): Transaction = Transaction(
        id = o.str("id")!!,
        kind = TransactionKind.of(o.str("kind") ?: "expense") ?: TransactionKind.EXPENSE,
        date = LocalDate.parse(o.str("date")),
        amountMinor = o.long("amount_minor") ?: 0,
        categoryId = o.str("category_id"),
        accountId = o.str("account_id"),
        toAccountId = o.str("to_account_id"),
        paidByMemberId = o.str("paid_by_member_id"),
        toMemberId = o.str("to_member_id"),
        split = split(o["split"]),
        recurrence = Recurrence.of(o.str("recurrence")),
        note = o.str("note") ?: "",
        tags = o.arr("tags").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        extras = (o.obj("extras") ?: JsonObject(emptyMap())).mapValues { plain(it.value) },
        status = Status.of(o.str("status")),
        recurringRuleId = o.str("recurring_rule_id"),
        occurrenceDate = o.str("occurrence_date")?.let(LocalDate::parse),
        importFingerprint = o.str("import_fingerprint"),
        originalAmountMinor = o.long("original_amount_minor"),
        originalCurrency = o.str("original_currency"),
        tripId = o.str("trip_id"),
        // False only when the object never carried the key at all: a row an
        // old app version stored before it knew about trip_id, decoded back
        // from what this phone's own database holds. Wire then writes the
        // row's edits without the key, and fulla_sync_push keeps whatever
        // trip another phone set instead of reading this as "no trip".
        tripKnown = o.containsKey("trip_id"),
        createdAt = o.str("created_at") ?: o.str("client_updated_at") ?: "",
        clientUpdatedAt = o.str("client_updated_at") ?: "",
        serverSeq = o.long("server_seq") ?: 0,
        createdByMemberId = o.str("created_by_member_id"),
    )

    fun transaction(t: Transaction): JsonObject = buildJsonObject {
        put("id", t.id)
        put("kind", t.kind.key)
        put("date", t.date.toString())
        put("amount_minor", t.amountMinor)
        put("category_id", t.categoryId)
        put("account_id", t.accountId)
        put("to_account_id", t.toAccountId)
        put("paid_by_member_id", t.paidByMemberId)
        put("to_member_id", t.toMemberId)
        put("split", split(t.split))
        put("recurrence", t.recurrence.key)
        put("note", t.note)
        put("tags", JsonArray(t.tags.map(::JsonPrimitive)))
        put("extras", JsonObject(t.extras.mapValues { element(it.value) }))
        put("status", t.status.key)
        put("recurring_rule_id", t.recurringRuleId)
        put("occurrence_date", t.occurrenceDate?.toString())
        put("import_fingerprint", t.importFingerprint)
        t.originalAmountMinor?.let { put("original_amount_minor", it) }
        put("original_currency", t.originalCurrency)
        // Omitted entirely for a row that never had the key (an old app
        // version's own row, never edited on a phone that knows trips): an
        // absent key keeps whatever trip is stored server-side, exactly the
        // "absent keeps its value" rule extras already follows.
        if (t.tripKnown || t.tripId != null) put("trip_id", t.tripId)
        put("created_at", t.createdAt)
        put("client_updated_at", t.clientUpdatedAt)
    }

    // ── config ───────────────────────────────────────────────────────────────

    fun config(o: JsonObject): Config {
        val h = o.obj("household")!!
        return Config(
            version = o.int("config_version") ?: 1,
            household = Household(
                id = h.str("id")!!, name = h.str("name") ?: "", currency = h.str("currency") ?: "EUR",
                locale = h.str("locale") ?: "en", periodStartDay = h.int("period_start_day") ?: 1,
                incomeShiftDay = h.int("income_shift_day"), weekStart = h.int("week_start") ?: 1,
                memberLimit = h.int("member_limit") ?: 20, moneyMode = MoneyMode.of(h.str("money_mode")),
            ),
            meMemberId = o.str("me_member_id"),
            members = o.arr("members").map { it.jsonObject }.map {
                Member(it.str("id")!!, it.str("display_name") ?: "", it.str("initials") ?: "?", it.int("color_index") ?: 0,
                    Role.of(it.str("role")), MemberStatus.of(it.str("status")), it.bool("has_account") ?: false)
            },
            accounts = o.arr("accounts").map { it.jsonObject }.map {
                Account(it.str("id")!!, it.str("name") ?: "", AccountType.of(it.str("type")), it.long("opening_balance_minor") ?: 0,
                    // Absent (an older bundle, before this field existed) and an
                    // explicit null both read as "no date": a legacy account,
                    // every movement counts, same as before this field existed.
                    it.str("opening_balance_date")?.let(LocalDate::parse),
                    it.int("sort") ?: 0, it.bool("archived") ?: false)
            },
            categories = o.arr("categories").map { it.jsonObject }.map {
                Category(it.str("id")!!, it.str("name") ?: "", AppliesTo.of(it.str("applies_to")), it.str("parent_id"),
                    it.str("icon") ?: "label", it.int("color_index") ?: 0, it.int("sort") ?: 0, it.bool("archived") ?: false)
            },
            fields = o.arr("custom_fields").map { it.jsonObject }.mapNotNull { f ->
                val type = FieldType.of(f.str("type") ?: "") ?: return@mapNotNull null
                CustomField(
                    id = f.str("id")!!, key = f.str("key")!!,
                    labels = f.obj("labels")?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                    type = type,
                    appliesTo = f.arr("applies_to").mapNotNull { TransactionKind.of(it.jsonPrimitive.content) }.toSet(),
                    required = f.bool("required") ?: false,
                    options = f.arr("options").map { it.jsonPrimitive.content },
                    defaultValue = f.str("default_value"),
                    showInList = f.bool("show_in_list") ?: false,
                    sort = f.int("sort") ?: 0,
                    archived = f.bool("archived") ?: false,
                )
            },
            budgets = o.arr("budgets").map { it.jsonObject }.map {
                Budget(it.str("id")!!, it.str("category_id")!!, it.str("period"), it.long("amount_minor") ?: 0)
            },
            recurringRules = o.arr("recurring_rules").map { it.jsonObject }.mapNotNull { recurring(it) },
            trips = o.arr("trips").map { it.jsonObject }.map(::trip),
        )
    }

    fun trip(o: JsonObject): Trip = Trip(
        id = o.str("id")!!, name = o.str("name") ?: "",
        startDate = LocalDate.parse(o.str("start_date")), endDate = LocalDate.parse(o.str("end_date")),
        budgetMinor = o.long("budget_minor"), inCategoryBudgets = o.bool("in_category_budgets") ?: false,
        archived = o.bool("archived") ?: false,
        // Absent entirely (a bundle from before this field existed, or a
        // decode with ignoreUnknownKeys tolerance in reverse) reads as no
        // known members, same as a legacy row: Trips.defaultFor already
        // treats that as "never auto-select", never as "everyone".
        memberIds = o.arr("member_ids").mapNotNull { it.jsonPrimitive.contentOrNull },
        // An unrecognised or absent kind (a bundle from before this field
        // existed, or one written by a newer app) falls back to OTHER/HOLIDAY
        // through TripKind.of, never a crash.
        kind = TripKind.of(o.str("trip_kind")),
    )

    fun trip(t: Trip): JsonObject = buildJsonObject {
        put("id", t.id); put("name", t.name)
        put("start_date", t.startDate.toString()); put("end_date", t.endDate.toString())
        put("budget_minor", t.budgetMinor)
        put("in_category_budgets", t.inCategoryBudgets); put("archived", t.archived)
        put("member_ids", JsonArray(t.memberIds.map { JsonPrimitive(it) }))
        put("trip_kind", t.kind.wire)
    }

    fun recurring(o: JsonObject): RecurringRule? {
        val s = o.obj("schedule") ?: return null
        val freq = Frequency.of(s.str("freq") ?: "") ?: return null
        val schedule = runCatching {
            Schedule(freq, s.int("interval") ?: 1, s.arr("by_weekday").mapNotNull { it.jsonPrimitive.intOrNull },
                s.int("by_month_day"), s.int("by_month"))
        }.getOrNull() ?: return null
        val template = o.obj("template") ?: return null
        val start = o.str("start_date") ?: return null
        val t = transaction(JsonObject(template + mapOf(
            "id" to JsonPrimitive(o.str("id")), "date" to JsonPrimitive(start),
            "status" to JsonPrimitive("active"), "client_updated_at" to JsonPrimitive(""),
        )))
        return RecurringRule(o.str("id")!!, o.str("name") ?: "", t, schedule, LocalDate.parse(start),
            o.str("end_date")?.let(LocalDate::parse), o.bool("auto_create") ?: false, o.bool("active") ?: true)
    }

    fun rules(o: JsonObject): List<CategorizationRule> = o.arr("categorization_rules").map { it.jsonObject }.map { r ->
        val a = r.obj("action") ?: JsonObject(emptyMap())
        CategorizationRule(
            id = r.str("id")!!, pattern = r.str("pattern") ?: "", categoryId = a.str("category_id"),
            kind = a.str("kind")?.let { TransactionKind.of(it) }, toAccountId = a.str("to_account_id"),
            tags = a.arr("tags").map { it.jsonPrimitive.content }, sort = r.int("sort") ?: 0, active = r.bool("active") ?: true,
        )
    }

    /** The inverse of [config]: a bundle in the server's shape, for households built on the phone (the demo). */
    fun bundle(c: Config): JsonObject = buildJsonObject {
        put("config_version", c.version)
        put("household", buildJsonObject {
            put("id", c.household.id); put("name", c.household.name); put("currency", c.household.currency)
            put("locale", c.household.locale); put("period_start_day", c.household.periodStartDay)
            put("income_shift_day", c.household.incomeShiftDay); put("week_start", c.household.weekStart)
            put("member_limit", c.household.memberLimit); put("money_mode", c.household.moneyMode?.key)
        })
        put("me_member_id", c.meMemberId)
        put("members", JsonArray(c.members.map { m ->
            buildJsonObject {
                put("id", m.id); put("display_name", m.displayName); put("initials", m.initials)
                put("color_index", m.colorIndex); put("role", m.role.key); put("status", m.status.key)
                put("has_account", m.hasAccount)
            }
        }))
        put("accounts", JsonArray(c.accounts.map(::account)))
        put("categories", JsonArray(c.categories.map(::category)))
        put("custom_fields", JsonArray(c.fields.map { f ->
            buildJsonObject {
                put("id", f.id); put("key", f.key); put("labels", JsonObject(f.labels.mapValues { JsonPrimitive(it.value) }))
                put("type", f.type.key); put("applies_to", JsonArray(f.appliesTo.map { JsonPrimitive(it.key) }))
                put("required", f.required); put("options", JsonArray(f.options.map(::JsonPrimitive)))
                put("default_value", f.defaultValue); put("show_in_list", f.showInList); put("sort", f.sort)
                put("archived", f.archived)
            }
        }))
        put("budgets", JsonArray(c.budgets.map { b ->
            buildJsonObject { put("id", b.id); put("category_id", b.categoryId); put("period", b.period); put("amount_minor", b.amountMinor) }
        }))
        put("recurring_rules", JsonArray(c.recurringRules.map(::recurring)))
        put("categorization_rules", JsonArray(emptyList()))
        put("import_profiles", JsonArray(emptyList()))
        put("trips", JsonArray(c.trips.map(::trip)))
    }

    fun account(a: Account): JsonObject = buildJsonObject {
        put("id", a.id); put("name", a.name); put("type", a.type.key)
        put("opening_balance_minor", a.openingBalanceMinor)
        put("opening_balance_date", a.openingBalanceDate?.toString())
        put("sort", a.sort); put("archived", a.archived)
    }

    fun category(k: Category): JsonObject = buildJsonObject {
        put("id", k.id); put("name", k.name); put("applies_to", k.appliesTo.key); put("parent_id", k.parentId)
        put("icon", k.icon); put("color_index", k.colorIndex); put("sort", k.sort); put("archived", k.archived)
    }

    fun recurring(r: RecurringRule): JsonObject = buildJsonObject {
        put("id", r.id); put("name", r.name)
        put("schedule", buildJsonObject {
            put("freq", r.schedule.frequency.key); put("interval", r.schedule.interval)
            put("by_weekday", JsonArray(r.schedule.byWeekday.map(::JsonPrimitive)))
            put("by_month_day", r.schedule.byMonthDay); put("by_month", r.schedule.byMonth)
        })
        put("template", JsonObject(transaction(r.template) - setOf("id", "date", "status", "client_updated_at", "created_at",
            "recurring_rule_id", "occurrence_date", "import_fingerprint")))
        put("start_date", r.startDate.toString()); put("end_date", r.endDate?.toString())
        put("auto_create", r.autoCreate); put("active", r.active)
    }

    fun list(e: JsonElement?): List<JsonObject> = (e as? JsonArray)?.map { it.jsonObject } ?: emptyList()

    fun strings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    // ── sync ─────────────────────────────────────────────────────────────────

    fun mutation(m: Mutation): JsonObject = buildJsonObject {
        put("mutation_id", m.mutationId)
        put("type", m.type.key)
        put("client_id", m.clientId)
        put("client_updated_at", m.clientUpdatedAt)
        m.baseClientUpdatedAt?.let { put("base_client_updated_at", it) }
        put("transaction", transaction(m.transaction))
    }

    fun pushResult(o: JsonObject): PushResult {
        val error = o.obj("error")
        return PushResult(
            mutationId = o.str("mutation_id") ?: "",
            transactionId = o.str("transaction_id") ?: "",
            ok = o.bool("ok") ?: false,
            applied = o.bool("applied") ?: false,
            conflict = o.obj("conflict")?.let(::conflict),
            serverTransaction = o.obj("server_transaction")?.let(::transaction),
            warnings = o.arr("warnings").map { w -> (w as? JsonPrimitive)?.contentOrNull ?: w.toString() },
            errorCode = error?.str("code"),
            errorMessage = error?.str("message"),
        )
    }

    fun conflict(o: JsonObject): Conflict = Conflict(
        winner = if (o.str("winner") == "server") Conflict.Winner.SERVER else Conflict.Winner.CLIENT,
        overwritten = o.arr("overwritten").mapNotNull { it as? JsonObject }.map {
            FieldChange(it.str("field") ?: "", text(it["before"]), text(it["after"]))
        },
    )

    /** A JSON value as a person would read it in a conflict note. */
    fun text(e: JsonElement?): String = when (e) {
        null, JsonNull -> ""
        is JsonPrimitive -> e.content
        else -> e.toString()
    }

    data class PullPage(
        val transactions: List<Transaction>,
        val cursor: Long,
        val hasMore: Boolean,
        val configVersion: Int,
        /** The whole config bundle, when the phone's version was not the current one. */
        val config: JsonObject?,
        val serverTime: String?,
    )

    fun pullPage(o: JsonObject, since: Long): PullPage = PullPage(
        transactions = o.arr("transactions").map { transaction(it.jsonObject) },
        cursor = o.long("cursor") ?: since,
        hasMore = o.bool("has_more") ?: false,
        configVersion = o.int("config_version") ?: 0,
        config = o.obj("config"),
        serverTime = o.str("server_time"),
    )
}
