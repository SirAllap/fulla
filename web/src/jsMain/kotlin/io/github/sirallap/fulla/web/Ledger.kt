// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.local.Backup
import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.local.RecurringPlanner
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.sync.Edits
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.defaults.Defaults
import io.github.sirallap.fulla.core.demo.DemoData
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncEngine
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.core.time.Instant
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The household as the screens see it: structure, rows, and the sums over them. */
class HouseholdView(val bundle: JsonObject, val rows: List<LocalTransaction>, language: String) {
    val stored: Config = LocalHousehold.config(bundle)
    val config: Config = Defaults.localized(stored, language)
    val id: String get() = config.household.id
    val active: List<Transaction> = rows.map { it.transaction }.filter { it.isActive }
    val rule: PeriodRule = PeriodRule.of(config, active)
    val analytics = Analytics(config, rule)
    /** Ids of rows written off: a fixed cost skipped for a month is not waited for. */
    val deletedIds: Set<String> = rows.filter { !it.transaction.isActive }.map { it.transaction.id }.toSet()

    /** The period that contains [today], for an ordinary expense. */
    fun currentPeriod(today: LocalDate = LocalDate.now()): YearMonth =
        rule.periodOf(today, TransactionKind.EXPENSE, Recurrence.VARIABLE)
}

enum class RestoreResult { RESTORED, ALREADY_HERE, NOT_A_BACKUP, NEWER, DAMAGED, CSV }

/**
 * The one household this browser holds, and every way to change it. Each
 * change is made on the rows in memory by the same rules the Android app
 * uses (Edits, RecurringPlanner, LocalHousehold), then written to the
 * browser's storage as a backup file's text: what is stored is exactly what
 * "Save a backup" gives.
 */
object Ledger {
    private const val ACTIVE_KEY = "active"
    private const val HOUSEHOLD_PREFIX = "household:"

    private var bundle: JsonObject? = null
    private var rows: List<LocalTransaction> = emptyList()

    /** Whenever something changes, so the page can draw it again. */
    var onChange: () -> Unit = {}

    /** When the person last saved a backup file (epoch millis), or null. */
    var lastBackupAt: Long? = null
        private set

    val view: HouseholdView? get() = bundle?.let { HouseholdView(it, rows, I18n.language) }

    private fun now(): Instant = Instant.now()

    // ── storage ──────────────────────────────────────────────────────────────

    /** Brings back the household the browser holds, if any. */
    suspend fun load(): Boolean {
        Idb.open()
        val id = Idb.get(ACTIVE_KEY) ?: Idb.keys().firstOrNull { it.startsWith(HOUSEHOLD_PREFIX) }?.removePrefix(HOUSEHOLD_PREFIX) ?: return false
        val text = Idb.get(HOUSEHOLD_PREFIX + id) ?: return false
        val contents = try { Backup.read(text) } catch (e: Backup.Unreadable) { return false }
        bundle = contents.bundle
        rows = contents.transactions.map { LocalTransaction(it, SyncState.LOCAL_ONLY, baseClientUpdatedAt = null) }
        lastBackupAt = Idb.get("last_backup")?.toLongOrNull()
        return true
    }

    private suspend fun persist() {
        val b = bundle ?: return
        val id = Wire.config(b).household.id
        Idb.put(HOUSEHOLD_PREFIX + id, backupText())
        Idb.put(ACTIVE_KEY, id)
        onChange()
    }

    fun backupText(): String = Backup.write(bundle!!, rows.map { it.transaction }, SyncEngine.iso(now()))

    suspend fun noteBackupSaved() {
        lastBackupAt = now().toEpochMilli()
        Idb.put("last_backup", lastBackupAt.toString())
        onChange()
    }

    // ── households ───────────────────────────────────────────────────────────

    suspend fun createLocal(name: String, currency: String, locale: String, displayName: String) {
        val initials = displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
        bundle = LocalHousehold.create(name, currency, locale, displayName, initials, colorIndex = 0)
        rows = emptyList()
        persist()
    }

    /** A household full of invented data, to look around before starting. */
    suspend fun createDemo() {
        val demo = DemoData.build(LocalDate.now(), I18n.language)
        bundle = Wire.bundle(demo.config)
        rows = demo.transactions.map { Edits.create(it, connected = false, now = now()) }
        persist()
    }

    /** Restores a backup file's text. A household already here is never overwritten. */
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
        if (Idb.get(HOUSEHOLD_PREFIX + contents.householdId) != null) return RestoreResult.ALREADY_HERE
        bundle = contents.bundle
        rows = contents.transactions.map { LocalTransaction(it, SyncState.LOCAL_ONLY, baseClientUpdatedAt = null) }
        persist()
        return RestoreResult.RESTORED
    }

    /** Forgets the household in this browser. There is no other copy unless a backup was saved. */
    suspend fun forget() {
        val id = bundle?.let { Wire.config(it).household.id } ?: return
        Idb.remove(HOUSEHOLD_PREFIX + id)
        Idb.remove(ACTIVE_KEY)
        Idb.remove("last_backup")
        bundle = null
        rows = emptyList()
        lastBackupAt = null
        onChange()
    }

    // ── rows ─────────────────────────────────────────────────────────────────

    /** Creates or changes a row; the id says which. */
    suspend fun save(t: Transaction) {
        val household = bundle?.let { LocalHousehold.config(it).household } ?: return
        val existing = rows.firstOrNull { it.id == t.id }
        val next = if (existing == null) Edits.create(t, connected = false, now = now(), household = household)
        else Edits.edit(existing, t, connected = false, now = now())
        rows = if (existing == null) rows + next else rows.map { if (it.id == t.id) next else it }
        persist()
    }

    suspend fun delete(id: String) = change(id) { Edits.delete(it, connected = false, now = now()) }
    suspend fun undelete(id: String) = change(id) { Edits.restore(it, connected = false, now = now()) }

    private suspend fun change(id: String, f: (LocalTransaction) -> LocalTransaction) {
        val row = rows.firstOrNull { it.id == id } ?: return
        val next = f(row)
        rows = rows.map { if (it.id == id) next else it }
        persist()
    }

    // ── structure ────────────────────────────────────────────────────────────

    suspend fun upsert(kind: Structure, item: JsonObject) {
        bundle = LocalHousehold.upsert(bundle ?: return, kind, item)
        persist()
        if (kind == Structure.RECURRING) generateRecurring()
    }

    suspend fun updateHousehold(patch: JsonObject) {
        bundle = LocalHousehold.updateHousehold(bundle ?: return, patch)
        persist()
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
        rows = rows + due.map { Edits.create(it, connected = false, now = now(), household = config.household) }
        persist()
        return due.size
    }

    /** Writes one overdue occurrence (or, with [skip], lets it go). */
    suspend fun applyRecurring(ruleId: String, date: LocalDate, skip: Boolean = false): Boolean {
        val config = LocalHousehold.config(bundle ?: return false)
        val rule = config.recurringRules.firstOrNull { it.id == ruleId } ?: return false
        if (io.github.sirallap.fulla.core.recurring.Scheduler.occurrences(rule, date, date).isEmpty()) return false
        val row = RecurringPlanner.occurrence(rule, date, config)
            .copy(status = if (skip) io.github.sirallap.fulla.core.model.Status.DELETED else io.github.sirallap.fulla.core.model.Status.ACTIVE)
        if (rows.any { it.id == row.id }) return false
        rows = rows + Edits.create(row, connected = false, now = now(), household = config.household)
        persist()
        return true
    }
}
