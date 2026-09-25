// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.repo

import androidx.room.withTransaction
import io.github.sirallap.fulla.client.local.Backup
import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.local.RecurringPlanner
import io.github.sirallap.fulla.client.remote.FullaApi
import io.github.sirallap.fulla.client.remote.Joined
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.sync.Edits
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.demo.DemoData
import io.github.sirallap.fulla.core.importers.CategorizationRule
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncEngine
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.data.local.ConflictNoteEntity
import io.github.sirallap.fulla.data.local.FullaDatabase
import io.github.sirallap.fulla.data.local.HouseholdEntity
import io.github.sirallap.fulla.data.local.Rows
import io.github.sirallap.fulla.data.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate

/** A household as the screens see it. */
data class HouseholdState(
    val id: String,
    val connected: Boolean,
    val bundle: JsonObject,
    val config: Config,
    val rules: List<CategorizationRule>,
    val lastSyncAt: Long?,
    val lastError: String?,
)

/**
 * Everything screens read and write, in one place. Reads are flows from
 * Room, so a screen shows what the phone holds at once and updates when a
 * sync lands. Writes go through [Edits] and ask for a sync; nothing waits on
 * the network except structure changes in a shared household, which the
 * server has to validate.
 */
class Ledger(
    private val db: FullaDatabase,
    private val settings: SettingsStore,
    private val requestSync: () -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    private val households = db.households()
    private val transactions = db.transactions()

    val active: Flow<HouseholdState?> = settings.settings.map { it.activeHouseholdId }.flatMapLatest { id ->
        if (id == null) flowOf(null) else households.observe(id).map { it?.let(::state) }
    }

    val all: Flow<List<HouseholdState>> = households.observeAll().map { list -> list.map(::state) }

    fun transactions(householdId: String): Flow<List<LocalTransaction>> =
        transactions.observeAll(householdId).map { list -> list.map(Rows::local) }

    fun pendingCount(householdId: String): Flow<Int> = transactions.observeCount(householdId, SyncState.PENDING.name)

    fun rejectedCount(householdId: String): Flow<Int> = transactions.observeCount(householdId, SyncState.REJECTED.name)

    fun conflicts(householdId: String): Flow<List<ConflictNoteEntity>> = db.conflicts().observeUnseen(householdId)

    suspend fun markConflictsSeen(householdId: String) = db.conflicts().markAllSeen(householdId)

    suspend fun household(id: String): HouseholdState? = households.get(id)?.let(::state)

    suspend fun transaction(id: String): LocalTransaction? = transactions.get(id)?.let(Rows::local)

    // ── transactions ─────────────────────────────────────────────────────────

    /** Creates or changes a row. The id decides which. A new row follows the household's shared pot rule. */
    suspend fun save(householdId: String, t: Transaction) {
        val h = households.get(householdId) ?: return
        val connected = h.mode == CONNECTED
        val household = householdOf(h)
        db.withTransaction {
            val existing = transactions.get(t.id)?.let(Rows::local)
            val row = if (existing == null) Edits.create(t, connected, now(), household) else Edits.edit(existing, t, connected, now())
            transactions.upsert(listOf(Rows.entity(householdId, row)))
        }
        if (connected) requestSync()
    }

    suspend fun saveAll(householdId: String, rows: List<Transaction>) {
        val h = households.get(householdId) ?: return
        val connected = h.mode == CONNECTED
        val household = householdOf(h)
        db.withTransaction {
            val held = rows.map { it.id }.chunked(500).flatMap { transactions.byIds(householdId, it) }.associateBy { it.id }
            transactions.upsert(rows.map { t ->
                val existing = held[t.id]?.let(Rows::local)
                Rows.entity(householdId, if (existing == null) Edits.create(t, connected, now(), household) else Edits.edit(existing, t, connected, now()))
            })
        }
        if (connected) requestSync()
    }

    suspend fun delete(householdId: String, id: String) = change(householdId, id) { row, connected -> Edits.delete(row, connected, now()) }

    suspend fun restore(householdId: String, id: String) = change(householdId, id) { row, connected -> Edits.restore(row, connected, now()) }

    private suspend fun change(householdId: String, id: String, f: (LocalTransaction, Boolean) -> LocalTransaction) {
        val h = households.get(householdId) ?: return
        val connected = h.mode == CONNECTED
        db.withTransaction {
            val row = transactions.get(id)?.let(Rows::local) ?: return@withTransaction
            transactions.upsert(listOf(Rows.entity(householdId, f(row, connected))))
        }
        if (connected) requestSync()
    }

    /** Whether a settlement written on this phone has not reached the server yet. */
    suspend fun hasUnsentSettlements(householdId: String): Boolean =
        transactions.all(householdId).map(Rows::local).any { it.state == SyncState.PENDING && it.transaction.kind == TransactionKind.SETTLEMENT }

    /** Writes the recurring occurrences that are due. Safe to call as often as you like. */
    suspend fun generateRecurring(householdId: String, today: LocalDate = LocalDate.now()) {
        val h = household(householdId) ?: return
        val due = RecurringPlanner.due(h.config, transactions.ids(householdId).toSet(), today)
        if (due.isNotEmpty()) saveAll(householdId, due)
    }

    // ── households ───────────────────────────────────────────────────────────

    suspend fun createLocal(name: String, currency: String, locale: String, displayName: String, initials: String, colorIndex: Int): String {
        val bundle = LocalHousehold.create(name, currency, locale, displayName, initials, colorIndex)
        val id = Wire.config(bundle).household.id
        households.upsert(HouseholdEntity(id, LOCAL, bundle.toString(), LocalHousehold.version(bundle)))
        settings.setActiveHousehold(id)
        return id
    }

    /** A household full of invented data, to look around before starting. It never syncs. */
    suspend fun createDemo(language: String): String {
        val demo = DemoData.build(LocalDate.now(), language)
        val bundle = Wire.bundle(demo.config)
        val id = demo.config.household.id
        db.withTransaction {
            households.upsert(HouseholdEntity(id, LOCAL, bundle.toString(), LocalHousehold.version(bundle)))
            transactions.upsert(demo.transactions.map { Rows.entity(id, Edits.create(it, connected = false, now = now())) })
        }
        settings.setActiveHousehold(id)
        return id
    }

    /** The household and every row, as a backup file's text. */
    suspend fun backup(householdId: String): String? {
        val h = households.get(householdId) ?: return null
        val rows = transactions.all(householdId).map { Rows.local(it).transaction }
        return Backup.write(Wire.json.parseToJsonElement(h.configJson).jsonObject, rows, SyncEngine.iso(now()))
    }

    /**
     * Restores a backup as a phone-only household, every id kept. A household
     * already on this phone is never overwritten: the file is refused.
     */
    suspend fun restore(contents: Backup.Contents): Boolean {
        val id = contents.householdId
        if (households.get(id) != null) return false
        db.withTransaction {
            households.upsert(HouseholdEntity(id, LOCAL, contents.bundle.toString(), LocalHousehold.version(contents.bundle)))
            transactions.upsert(contents.transactions.map {
                Rows.entity(id, LocalTransaction(it, SyncState.LOCAL_ONLY, baseClientUpdatedAt = null))
            })
        }
        settings.setActiveHousehold(id)
        return true
    }

    /** A shared household this phone just created or joined. Its rows arrive with the first sync. */
    suspend fun adopt(joined: Joined) {
        val existing = households.get(joined.householdId)
        households.upsert(HouseholdEntity(joined.householdId, CONNECTED, joined.config.toString(),
            LocalHousehold.version(joined.config), cursor = existing?.cursor ?: 0))
        settings.setActiveHousehold(joined.householdId)
        requestSync()
    }

    /**
     * Local mode becoming shared: the bundle is uploaded as it is, every id
     * kept, and every row this phone wrote becomes owed to the server. The
     * pot chosen here waits until those rows are in ([finishSharing]).
     */
    suspend fun connect(householdId: String, api: FullaApi) {
        val h = households.get(householdId) ?: return
        if (h.mode == CONNECTED) return
        val local = Wire.json.parseToJsonElement(h.configJson).jsonObject
        val joined = api.householdCreateFromLocal(LocalHousehold.forUpload(local))
        val config = LocalHousehold.afterUpload(local, joined.config)
        db.withTransaction {
            households.upsert(h.copy(mode = CONNECTED, configJson = config.toString(),
                configVersion = LocalHousehold.version(joined.config), cursor = 0))
            val rows = Edits.connect(transactions.all(householdId).map(Rows::local))
            transactions.upsert(rows.map { Rows.entity(householdId, it) })
        }
        requestSync()
    }

    /**
     * Sets on the server the pot this household chose before it was shared,
     * once nothing this phone wrote is left to send. Called after each sync.
     */
    suspend fun finishSharing(householdId: String, api: FullaApi) {
        val h = households.get(householdId) ?: return
        val unsent = transactions.inState(householdId, SyncState.PENDING.name).size
        val next = LocalHousehold.applyDeferred(Wire.json.parseToJsonElement(h.configJson).jsonObject, unsent) { patch ->
            api.householdUpdate(householdId, patch)
        } ?: return
        households.setConfig(householdId, next.toString(), LocalHousehold.version(next))
    }

    suspend fun connectedIds(): List<String> = households.all().filter { it.mode == CONNECTED }.map { it.id }

    /** Forgets a household on this phone. Its data on the server is untouched. */
    suspend fun forget(householdId: String) {
        db.withTransaction {
            transactions.forget(householdId)
            db.conflicts().forget(householdId)
            households.forget(householdId)
        }
        if (settings.current().activeHouseholdId == householdId) {
            settings.setActiveHousehold(households.all().firstOrNull()?.id)
        }
    }

    // ── structure ────────────────────────────────────────────────────────────

    /** Saves one piece of structure: on this phone in local mode, through the server when shared. */
    suspend fun upsert(householdId: String, kind: Structure, item: JsonObject, api: FullaApi?) = updateConfig(householdId, api) { bundle ->
        if (api == null) LocalHousehold.upsert(bundle, kind, item) else api.upsert(householdId, kind, item)
    }

    /** A money_mode in [patch] is the choice from now on: a pot still waiting from before sharing is dropped. */
    suspend fun updateHousehold(householdId: String, patch: JsonObject, api: FullaApi?) =
        updateConfig(householdId, api, keepDeferred = "money_mode" !in patch) { bundle ->
            if (api == null) LocalHousehold.updateHousehold(bundle, patch) else api.householdUpdate(householdId, patch)
        }

    /** Stores whatever config a structure call answered with. A pot waiting from before sharing survives it. */
    suspend fun storeConfig(householdId: String, bundle: JsonObject) {
        val stored = households.get(householdId)?.configJson?.let { Wire.json.parseToJsonElement(it).jsonObject }
        val next = LocalHousehold.keepDeferred(stored, bundle)
        households.setConfig(householdId, next.toString(), LocalHousehold.version(next))
    }

    private suspend fun updateConfig(householdId: String, api: FullaApi?, keepDeferred: Boolean = true, change: suspend (JsonObject) -> JsonObject) {
        val h = households.get(householdId) ?: return
        require((h.mode == CONNECTED) == (api != null)) { "A shared household changes through the server, a local one on the phone." }
        val next = change(Wire.json.parseToJsonElement(h.configJson).jsonObject)
        if (keepDeferred) storeConfig(householdId, next)
        else households.setConfig(householdId, JsonObject(next - LocalHousehold.DEFERRED_MONEY_MODE).toString(), LocalHousehold.version(next))
    }

    private fun householdOf(e: HouseholdEntity) = LocalHousehold.config(Wire.json.parseToJsonElement(e.configJson).jsonObject).household

    private fun state(e: HouseholdEntity): HouseholdState {
        val bundle = Wire.json.parseToJsonElement(e.configJson).jsonObject
        return HouseholdState(e.id, e.mode == CONNECTED, bundle, LocalHousehold.config(bundle), Wire.rules(bundle), e.lastSyncAt, e.lastError)
    }

    companion object {
        const val LOCAL = "local"
        const val CONNECTED = "connected"
    }
}
