// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.local.Backup
import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.local.RecurringPlanner
import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.Invite
import io.github.sirallap.fulla.client.remote.Joined
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.sync.Edits
import io.github.sirallap.fulla.client.sync.GuardedWrite
import io.github.sirallap.fulla.client.sync.SyncStore
import io.github.sirallap.fulla.client.sync.Syncer
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.defaults.Defaults
import io.github.sirallap.fulla.core.demo.DemoData
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.recurring.Scheduler
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.sync.ConflictNote
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncEngine
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.core.time.Instant
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val LOCAL = "local"
const val CONNECTED = "connected"

/** What this browser knows about a household beyond its rows: where it lives, and how far the sync has got. */
data class Meta(
    val mode: String = LOCAL,
    val url: String? = null,
    val anonKey: String? = null,
    val cursor: Long = 0,
    val configVersion: Int = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
)

/** The household as the screens see it: structure, rows, and the sums over them. */
class HouseholdView(val bundle: JsonObject, val rows: List<LocalTransaction>, language: String, val meta: Meta = Meta()) {
    val stored: Config = LocalHousehold.config(bundle)
    val config: Config = Defaults.localized(stored, language)
    val id: String get() = config.household.id
    val connected: Boolean get() = meta.mode == CONNECTED
    val active: List<Transaction> = rows.map { it.transaction }.filter { it.isActive }
    val rule: PeriodRule = PeriodRule.of(config, active)
    val analytics = Analytics(config, rule)
    /** Ids of rows written off: a fixed cost skipped for a month is not waited for. */
    val deletedIds: Set<String> = rows.filter { !it.transaction.isActive }.map { it.transaction.id }.toSet()
    val pendingCount: Int = rows.count { it.state == SyncState.PENDING }

    /** The period that contains [today], for an ordinary expense. */
    fun currentPeriod(today: LocalDate = LocalDate.now()): YearMonth =
        rule.periodOf(today, TransactionKind.EXPENSE, Recurrence.VARIABLE)

    fun memberName(id: String?): String = config.member(id)?.displayName ?: "?"
}

data class HouseholdSummary(val id: String, val name: String, val mode: String)

enum class RestoreResult { RESTORED, ALREADY_HERE, NOT_A_BACKUP, NEWER, DAMAGED, CSV }

/**
 * The household this browser has open, and every way to change it. A local
 * household changes on the rows in memory by the same rules the Android app
 * uses (Edits, RecurringPlanner, LocalHousehold); a shared one sends its
 * structure through the server and its rows through the sync. Either way the
 * whole thing is written to the browser's storage after each change.
 */
object Ledger {
    private const val ACTIVE_KEY = "active"
    private const val INDEX_KEY = "index"
    private const val STATE_PREFIX = "state:"
    private const val OLD_PREFIX = "household:"

    private var bundle: JsonObject? = null
    private var rows: List<LocalTransaction> = emptyList()
    private var meta: Meta = Meta()

    /** Whenever something changes, so the page can draw it again. */
    var onChange: () -> Unit = {}

    /** When the person last saved a backup file (epoch millis), or null. */
    var lastBackupAt: Long? = null
        private set

    var syncing: Boolean = false
        private set

    /** The sign-in is gone: the person has to sign in again to sync. */
    var needsSignIn: Boolean = false
        private set

    val view: HouseholdView? get() = bundle?.let { HouseholdView(it, rows, I18n.language, meta) }

    /** Every household this browser holds, as of the last change. */
    var households: List<HouseholdSummary> = emptyList()
        private set

    private fun now(): Instant = Instant.now()
    private val connected: Boolean get() = meta.mode == CONNECTED
    private val householdId: String? get() = bundle?.let { Wire.config(it).household.id }

    // ── storage ──────────────────────────────────────────────────────────────

    /** Brings back the household the browser had open, if any. */
    suspend fun load(): Boolean {
        Idb.open()
        migrateOld()
        households = summaries()
        val id = Idb.get(ACTIVE_KEY) ?: households.firstOrNull()?.id ?: return false
        return open(id)
    }

    private suspend fun open(id: String): Boolean {
        val text = Idb.get(STATE_PREFIX + id) ?: return false
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        bundle = o["bundle"]?.jsonObject ?: return false
        meta = o["meta"]?.jsonObject?.let(::metaOf) ?: Meta()
        rows = o["rows"]?.jsonArray.orEmpty().mapNotNull { runCatching { rowOf(it.jsonObject) }.getOrNull() }
        lastBackupAt = Idb.get("last_backup:$id")?.toLongOrNull()
        needsSignIn = false
        connectRemote()
        return true
    }

    /** Households written by the first version of the web app: a backup file's text each. */
    private suspend fun migrateOld() {
        for (key in Idb.keys().filter { it.startsWith(OLD_PREFIX) }) {
            val id = key.removePrefix(OLD_PREFIX)
            if (Idb.get(STATE_PREFIX + id) == null) {
                val contents = runCatching { Backup.read(Idb.get(key).orEmpty()) }.getOrNull()
                if (contents != null) Idb.put(STATE_PREFIX + id, stateText(contents.bundle, contents.transactions.map { LocalTransaction(it, SyncState.LOCAL_ONLY, null) }, Meta()))
            }
            Idb.remove(key)
        }
    }

    suspend fun summaries(): List<HouseholdSummary> {
        val text = Idb.get(INDEX_KEY) ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(text).jsonArray.map {
                val o = it.jsonObject
                HouseholdSummary((o["id"] as JsonPrimitive).content, (o["name"] as JsonPrimitive).content, (o["mode"] as JsonPrimitive).content)
            }
        }.getOrDefault(emptyList())
    }

    private fun rowOf(o: JsonObject): LocalTransaction = LocalTransaction(
        transaction = Wire.transaction(o["t"]!!.jsonObject),
        state = runCatching { SyncState.valueOf((o["s"] as JsonPrimitive).content) }.getOrDefault(SyncState.PENDING),
        baseClientUpdatedAt = (o["b"] as? JsonPrimitive)?.contentOrNull,
        rejectCode = (o["c"] as? JsonPrimitive)?.contentOrNull,
        rejectMessage = (o["m"] as? JsonPrimitive)?.contentOrNull,
    )

    private fun rowJson(r: LocalTransaction): JsonObject = buildJsonObject {
        put("t", JsonObject(Wire.transaction(r.transaction) + ("server_seq" to JsonPrimitive(r.transaction.serverSeq))))
        put("s", r.state.name)
        put("b", r.baseClientUpdatedAt)
        put("c", r.rejectCode)
        put("m", r.rejectMessage)
    }

    private fun metaOf(o: JsonObject): Meta = Meta(
        mode = (o["mode"] as? JsonPrimitive)?.contentOrNull ?: LOCAL,
        url = (o["url"] as? JsonPrimitive)?.contentOrNull,
        anonKey = (o["key"] as? JsonPrimitive)?.contentOrNull,
        cursor = (o["cursor"] as? JsonPrimitive)?.longOrNull ?: 0,
        configVersion = (o["config_version"] as? JsonPrimitive)?.intOrNull ?: 0,
        lastSyncAt = (o["last_sync"] as? JsonPrimitive)?.longOrNull,
        lastError = (o["last_error"] as? JsonPrimitive)?.contentOrNull,
    )

    private fun stateText(b: JsonObject, r: List<LocalTransaction>, m: Meta): String = buildJsonObject {
        put("v", 1)
        put("bundle", b)
        put("meta", buildJsonObject {
            put("mode", m.mode); put("url", m.url); put("key", m.anonKey); put("cursor", m.cursor)
            put("config_version", m.configVersion); put("last_sync", m.lastSyncAt); put("last_error", m.lastError)
        })
        put("rows", JsonArray(r.map(::rowJson)))
    }.toString()

    private suspend fun persist() {
        val b = bundle ?: return
        val id = Wire.config(b).household.id
        Idb.put(STATE_PREFIX + id, stateText(b, rows, meta))
        Idb.put(ACTIVE_KEY, id)
        val others = summaries().filter { it.id != id }
        val mine = HouseholdSummary(id, Wire.config(b).household.name, meta.mode)
        households = others + mine
        Idb.put(INDEX_KEY, JsonArray(households.map { s ->
            buildJsonObject { put("id", s.id); put("name", s.name); put("mode", s.mode) }
        }).toString())
        onChange()
    }

    /** What "Save a backup" gives: the household and every row, as the Android app writes them too. */
    fun backupText(): String = Backup.write(bundle!!, rows.map { it.transaction }, SyncEngine.iso(now()))

    suspend fun noteBackupSaved() {
        lastBackupAt = now().toEpochMilli()
        householdId?.let { Idb.put("last_backup:$it", lastBackupAt.toString()) }
        onChange()
    }

    // ── households ───────────────────────────────────────────────────────────

    suspend fun createLocal(name: String, currency: String, locale: String, displayName: String) {
        val initials = initialsOf(displayName)
        bundle = LocalHousehold.create(name, currency, locale, displayName, initials, colorIndex = 0)
        rows = emptyList()
        meta = Meta()
        persist()
        householdId?.let { GuideHost.start(it, io.github.sirallap.fulla.core.guide.GuideOrigin.CREATED) }
    }

    private fun initialsOf(name: String): String =
        name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

    /** A household full of invented data, to look around before starting. */
    suspend fun createDemo() {
        val demo = DemoData.build(LocalDate.now(), I18n.language)
        bundle = Wire.bundle(demo.config)
        rows = demo.transactions.map { Edits.create(it, connected = false, now = now()) }
        meta = Meta()
        persist()
        householdId?.let { GuideHost.start(it, io.github.sirallap.fulla.core.guide.GuideOrigin.DEMO) }
    }

    /** Restores a backup file's text, as a household that lives in this browser. One already here is never overwritten. */
    suspend fun restore(text: String): RestoreResult {
        val contents = try {
            Backup.read(text)
        } catch (e: Backup.Unreadable) {
            return when (e.reason) {
                Backup.Reason.NEWER_VERSION -> RestoreResult.NEWER
                Backup.Reason.DAMAGED -> RestoreResult.DAMAGED
                Backup.Reason.NOT_A_BACKUP -> if (text.lineSequence().firstOrNull()?.contains(',') == true) RestoreResult.CSV else RestoreResult.NOT_A_BACKUP
            }
        }
        if (Idb.get(STATE_PREFIX + contents.householdId) != null) return RestoreResult.ALREADY_HERE
        bundle = contents.bundle
        rows = contents.transactions.map { LocalTransaction(it, SyncState.LOCAL_ONLY, baseClientUpdatedAt = null) }
        meta = Meta()
        persist()
        return RestoreResult.RESTORED
    }

    /** Opens another household held in this browser. */
    suspend fun switchTo(id: String) {
        if (id == householdId) return
        if (open(id)) { Idb.put(ACTIVE_KEY, id); onChange(); requestSync() }
    }

    /** Forgets the open household in this browser. A shared one stays on the server; a local one has no other copy unless a backup was saved. */
    suspend fun forget() {
        val id = householdId ?: return
        Idb.remove(STATE_PREFIX + id)
        Idb.remove("last_backup:$id")
        Idb.put(INDEX_KEY, JsonArray(summaries().filter { it.id != id }.map { s ->
            buildJsonObject { put("id", s.id); put("name", s.name); put("mode", s.mode) }
        }).toString())
        bundle = null; rows = emptyList(); meta = Meta(); lastBackupAt = null
        households = summaries()
        val next = households.firstOrNull()
        if (next != null) { open(next.id); Idb.put(ACTIVE_KEY, next.id) } else Idb.remove(ACTIVE_KEY)
        onChange()
    }

    // ── rows ─────────────────────────────────────────────────────────────────

    /** Creates or changes a row; the id says which. */
    suspend fun save(t: Transaction) {
        val household = bundle?.let { LocalHousehold.config(it).household } ?: return
        val existing = rows.firstOrNull { it.id == t.id }
        val next = if (existing == null) Edits.create(t, connected, now(), household) else Edits.edit(existing, t, connected, now())
        rows = if (existing == null) rows + next else rows.map { if (it.id == t.id) next else it }
        persist()
        requestSync()
    }

    suspend fun delete(id: String) = change(id) { Edits.delete(it, connected, now()) }
    suspend fun undelete(id: String) = change(id) { Edits.restore(it, connected, now()) }

    private suspend fun change(id: String, f: (LocalTransaction) -> LocalTransaction) {
        val row = rows.firstOrNull { it.id == id } ?: return
        val next = f(row)
        rows = rows.map { if (it.id == id) next else it }
        persist()
        requestSync()
    }

    // ── structure ────────────────────────────────────────────────────────────

    /** Saves one piece of structure: here when the household is local, through the server when it is shared. */
    suspend fun upsert(kind: Structure, item: JsonObject) {
        val b = bundle ?: return
        if (connected) storeConfig(Remote.api!!.upsert(householdId!!, kind, item))
        else { bundle = LocalHousehold.upsert(b, kind, item); persist() }
        if (kind == Structure.RECURRING) generateRecurring()
    }

    /** Copies one month's budgets into another. */
    suspend fun copyBudgets(from: String, to: String) {
        val b = bundle ?: return
        if (connected) storeConfig(Remote.api!!.budgetCopy(householdId!!, from, to))
        else { bundle = LocalHousehold.copyBudgets(b, from, to); persist() }
    }

    /** Writes several rows at once, such as the settlements that start a shared pot. */
    suspend fun saveAll(list: List<Transaction>) { for (t in list) save(t) }

    /** Limits a field to some categories, or lifts the limit (empty). */
    suspend fun setFieldCategories(fieldId: String, categoryIds: Set<String>) {
        val b = bundle ?: return
        if (connected) storeConfig(Remote.api!!.setFieldCategories(householdId!!, fieldId, categoryIds))
        else { bundle = LocalHousehold.setFieldCategories(b, fieldId, categoryIds); persist() }
    }

    /** Removes a trip. Its expenses stay, as everyday expenses. */
    suspend fun deleteTrip(tripId: String) {
        val b = bundle ?: return
        if (connected) { storeConfig(Remote.api!!.tripDelete(householdId!!, tripId)); requestSync() }
        else {
            bundle = LocalHousehold.deleteTrip(b, tripId)
            rows = rows.map { r -> if (r.transaction.tripId == tripId) r.copy(transaction = r.transaction.copy(tripId = null, tripKnown = true)) else r }
            persist()
        }
    }

    suspend fun updateHousehold(patch: JsonObject) {
        val b = bundle ?: return
        if (connected) storeConfig(Remote.api!!.householdUpdate(householdId!!, patch))
        else { bundle = LocalHousehold.updateHousehold(b, patch); persist() }
    }

    /** Stores whatever config a call to the server answered with. A pot chosen before sharing survives it. */
    private suspend fun storeConfig(next: JsonObject) {
        bundle = LocalHousehold.keepDeferred(bundle, next)
        meta = meta.copy(configVersion = LocalHousehold.version(next))
        persist()
    }

    // ── people ───────────────────────────────────────────────────────────────

    /** Someone without the app, such as a child. They can claim their place later with an invite. */
    suspend fun addMember(name: String) {
        val id = randomUuid()
        val member = buildJsonObject {
            put("id", id); put("display_name", name.trim()); put("initials", initialsOf(name))
            put("color_index", (view?.config?.members?.size ?: 0) % 10)
            put("role", "member"); put("status", "active"); put("has_account", false)
        }
        if (connected) storeConfig(Remote.api!!.memberCreateVirtual(householdId!!, member))
        else { bundle = LocalHousehold.upsertMember(bundle ?: return, member); persist() }
    }

    suspend fun renameMember(memberId: String, name: String) {
        val patch = buildJsonObject { put("display_name", name.trim()); put("initials", initialsOf(name)) }
        if (connected) storeConfig(Remote.api!!.memberUpdate(householdId!!, memberId, patch))
        else {
            val m = view?.stored?.members?.firstOrNull { it.id == memberId } ?: return
            bundle = LocalHousehold.upsertMember(bundle ?: return, buildJsonObject {
                put("id", m.id); put("display_name", name.trim()); put("initials", initialsOf(name)); put("color_index", m.colorIndex)
                put("role", m.role.key); put("status", m.status.key); put("has_account", m.hasAccount)
            })
            persist()
        }
    }

    suspend fun setRole(memberId: String, role: String) = storeConfig(Remote.api!!.memberSetRole(householdId!!, memberId, role))
    suspend fun removeMember(memberId: String) {
        if (connected) storeConfig(Remote.api!!.memberRemove(householdId!!, memberId))
        else { bundle = LocalHousehold.upsertMember(bundle ?: return, buildJsonObject { put("id", memberId); put("status", "archived") }); persist() }
    }
    suspend fun transferOwnership(memberId: String) = storeConfig(Remote.api!!.ownerTransfer(householdId!!, memberId))

    suspend fun leave() {
        Remote.api!!.memberLeave(householdId!!)
        forget()
    }

    suspend fun invite(role: String, claimMemberId: String?): Invite = Remote.api!!.inviteCreate(householdId!!, role, claimMemberId, 72)
    suspend fun invites(): List<Invite> = Remote.api!!.inviteList(householdId!!)
    suspend fun revokeInvite(code: String) = Remote.api!!.inviteRevoke(householdId!!, code)

    // ── sharing ──────────────────────────────────────────────────────────────

    private fun connectRemote() {
        val url = meta.url ?: return
        val key = meta.anonKey ?: return
        Endpoint.parse(url, key)?.let { Remote.use(it) }
    }

    /**
     * Local mode becoming shared: the household is uploaded to the person's own
     * Supabase project as it is, every id kept, and every row written here is
     * owed to the server.
     */
    suspend fun share(endpoint: Endpoint, email: String, password: String, create: Boolean): Boolean {
        val b = bundle ?: return false
        Remote.use(endpoint)
        if (!Remote.signIn(email, password, create)) return false
        val api = Remote.api!!
        api.ping()
        val joined = api.householdCreateFromLocal(LocalHousehold.forUpload(b))
        val config = LocalHousehold.afterUpload(b, joined.config)
        bundle = config
        meta = Meta(CONNECTED, endpoint.url, endpoint.anonKey, 0, LocalHousehold.version(joined.config))
        rows = rows.map { r -> Edits.connect(listOf(r)).firstOrNull() ?: r }
        persist()
        // At once, not soon: a person may invite somebody before a timer fires, and what was written alone must reach the server first.
        sync()
        return true
    }

    /** Joins a household with an invite, as a member who signs in with their own account. */
    suspend fun join(endpoint: Endpoint, code: String, email: String, password: String, create: Boolean, displayName: String): Boolean {
        Remote.use(endpoint)
        if (!Remote.signIn(email, password, create)) return false
        val api = Remote.api!!
        api.ping()
        val joined: Joined = api.inviteAccept(code, displayName.trim(), initialsOf(displayName), 0)
        adopt(joined, endpoint)
        return true
    }

    private suspend fun adopt(joined: Joined, endpoint: Endpoint) {
        bundle = joined.config
        rows = emptyList()
        meta = Meta(CONNECTED, endpoint.url, endpoint.anonKey, 0, LocalHousehold.version(joined.config))
        persist()
        householdId?.let { GuideHost.start(it, io.github.sirallap.fulla.core.guide.GuideOrigin.JOINED) }
        sync()
    }

    // ── sync ─────────────────────────────────────────────────────────────────

    private var syncTimer: Int? = null

    /** Asks for a sync soon: after a change, or when the page comes back. Many asks make one. */
    fun requestSync(delayMs: Int = 1500) {
        if (!connected) return
        syncTimer?.let { window.clearTimeout(it) }
        syncTimer = window.setTimeout({ syncTimer = null; scope.launch { sync() } }, delayMs)
    }

    private suspend fun clientId(): String = Idb.get("client_id") ?: randomUuid().also { Idb.put("client_id", it) }

    suspend fun sync() {
        val id = householdId ?: return
        if (!connected || syncing) return
        connectRemote()
        val api = Remote.api ?: return
        syncing = true
        onChange()
        try {
            if (!Remote.hasSession()) throw FullaError(FullaError.NOT_AUTHENTICATED, "Not signed in.", 401)
            Syncer(store, api, clientId()).sync(id)
            finishSharing(id)
            needsSignIn = false
            meta = meta.copy(lastSyncAt = now().toEpochMilli(), lastError = null)
            generateRecurring()
        } catch (e: FullaError) {
            needsSignIn = e.needsSignIn
            meta = meta.copy(lastError = Remote.message(e))
        } catch (e: Throwable) {
            meta = meta.copy(lastError = t("something_failed"))
        } finally {
            syncing = false
            persist()
        }
    }

    /** Signs in again when the session has ended, then syncs. */
    suspend fun signInAgain(email: String, password: String): Boolean {
        if (!Remote.signIn(email, password, create = false)) return false
        needsSignIn = false
        sync()
        return true
    }

    /** Sets on the server the pot this household chose before it was shared, once nothing written here is left to send. */
    private suspend fun finishSharing(id: String) {
        val b = bundle ?: return
        val unsent = rows.count { it.state == SyncState.PENDING }
        val next = LocalHousehold.applyDeferred(b, unsent) { patch -> Remote.api!!.householdUpdate(id, patch) } ?: return
        bundle = next
    }

    /** The sync's view of what this browser holds. Every method that writes is one step with no waiting in it. */
    private val store = object : SyncStore {
        override suspend fun cursor(householdId: String): Long = meta.cursor
        override suspend fun configVersion(householdId: String): Int = meta.configVersion
        override suspend fun pending(householdId: String): List<LocalTransaction> = rows.filter { it.state == SyncState.PENDING }
        override suspend fun held(householdId: String, ids: Collection<String>): List<LocalTransaction> {
            val wanted = ids.toSet()
            return rows.filter { it.id in wanted }
        }

        override suspend fun settle(householdId: String, writes: List<GuardedWrite>, notes: List<ConflictNote>) {
            writeGuarded(writes)
            persist()
        }

        override suspend fun applyPull(
            householdId: String, config: JsonObject?, configVersion: Int, writes: List<GuardedWrite>,
            notes: List<ConflictNote>, cursor: Long,
        ) {
            if (config != null) bundle = LocalHousehold.keepDeferred(bundle, config)
            writeGuarded(writes)
            meta = meta.copy(cursor = cursor, configVersion = if (config != null) configVersion else meta.configVersion)
            persist()
        }
    }

    /** A row is written only if the one held still is the version the sync read: an edit made in between is never overwritten. */
    private fun writeGuarded(writes: List<GuardedWrite>) {
        val current = rows.associate { it.id to it.transaction.clientUpdatedAt }
        val accepted = writes.filter { current[it.row.id] == it.expectedStamp }.map { it.row }
        if (accepted.isEmpty()) return
        val byId = accepted.associateBy { it.id }
        val replaced = rows.map { byId[it.id] ?: it }
        val known = rows.map { it.id }.toSet()
        rows = replaced + accepted.filter { it.id !in known }
    }

    // ── fixed costs ──────────────────────────────────────────────────────────

    /**
     * Writes the recurring occurrences that have come due and that nothing has
     * written, and says how many it wrote. Safe to call as often as you like:
     * the ids are the same every time, so an occurrence is never written twice.
     */
    suspend fun generateRecurring(today: LocalDate = LocalDate.now()): Int {
        val b = bundle ?: return 0
        val config = LocalHousehold.config(b)
        if (config.recurringRules.none { it.active && !it.archived && it.autoCreate }) return 0
        val due = RecurringPlanner.plan(config, rows.map { it.id }.toSet(), today) { rows.map { it.transaction } }
        if (due.isEmpty()) return 0
        rows = rows + due.map { Edits.create(it, connected, now(), config.household) }
        persist()
        requestSync()
        return due.size
    }

    /** Writes one overdue occurrence (or, with [skip], lets it go). */
    suspend fun applyRecurring(ruleId: String, date: LocalDate, skip: Boolean = false): Boolean {
        val config = LocalHousehold.config(bundle ?: return false)
        val rule = config.recurringRules.firstOrNull { it.id == ruleId } ?: return false
        if (Scheduler.occurrences(rule, date, date).isEmpty()) return false
        val row = RecurringPlanner.occurrence(rule, date, config).copy(status = if (skip) Status.DELETED else Status.ACTIVE)
        if (rows.any { it.id == row.id }) return false
        rows = rows + Edits.create(row, connected, now(), config.household)
        persist()
        requestSync()
        return true
    }
}
