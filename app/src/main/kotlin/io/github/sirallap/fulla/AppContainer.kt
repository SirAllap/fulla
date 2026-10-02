// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla

import android.content.Context
import io.github.sirallap.fulla.client.remote.DbUpdateStatus
import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.FullaApi
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.InviteLink
import io.github.sirallap.fulla.client.remote.Supabase
import io.github.sirallap.fulla.client.remote.UpdateCheck
import io.github.sirallap.fulla.client.remote.checkSchemaVersion
import io.github.sirallap.fulla.client.remote.dbUpdateDelta
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import io.github.sirallap.fulla.client.sync.Syncer
import io.github.sirallap.fulla.core.version.Versions
import io.github.sirallap.fulla.data.local.FullaDatabase
import io.github.sirallap.fulla.data.local.RoomSyncStore
import io.github.sirallap.fulla.data.prefs.KeystoreSessionStore
import io.github.sirallap.fulla.data.prefs.SettingsStore
import io.github.sirallap.fulla.data.repo.Ledger
import io.github.sirallap.fulla.data.sync.SyncOutcome
import io.github.sirallap.fulla.data.sync.SyncScheduler
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** What the header's cloud shows. */
data class SyncStatus(
    val running: Boolean = false,
    val lastError: FullaError? = null,
    val needsSignIn: Boolean = false,
    val lastFinishedAt: Long? = null,
)

/**
 * The app's objects, built once. No dependency injection framework: the
 * graph is small enough to read in one screen, and this is it.
 */
class AppContainer(private val context: Context) {
    /**
     * Outlives any single screen: for work that must finish even if the
     * screen that started it closes right after, such as deleting a trip
     * (TripScreen calls `onBack()` the moment the person confirms). A
     * screen's own `rememberCoroutineScope` (via `rememberChange`) is
     * cancelled as soon as it leaves the composition, which on a slow
     * connection can land mid-RPC.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: FullaDatabase = FullaDatabase.open(context)
    val settings = SettingsStore(context)
    val sessions = KeystoreSessionStore(context)
    val ledger = Ledger(db, settings, requestSync = { SyncScheduler.requestSoon(context) })

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
            }
        }
    }

    /** Supabase's Management API, used only while setting up a household's own project. */
    fun projectSetup(token: String) = io.github.sirallap.fulla.client.remote.ProjectSetup(http, token)

    /** GitHub's release API and the release asset's own hosts; see UpdateCheck's own doc comment. */
    val updateCheck = UpdateCheck(http)

    /**
     * Asks GitHub about a newer release, at most once every [UPDATE_CHECK_INTERVAL_MS],
     * skipped entirely on a debug build (a different signing key can never
     * install over this one) or when the setting is off. Also clears a
     * stored pending update once this build is that version or newer, so a
     * phone that just installed the update stops offering it to itself.
     */
    enum class UpdateCheckResult { FOUND, UP_TO_DATE, FAILED, SKIPPED }

    /**
     * Asks GitHub for a newer release. On its own (every time the app is
     * opened, and once an hour while it stays open) at most every hour and
     * only when the setting is on; [now] is the "Check now" row, which asks
     * right away. One small request: it costs next to nothing.
     */
    suspend fun checkForUpdates(now: Boolean = false): UpdateCheckResult {
        if (BuildConfig.DEBUG) return UpdateCheckResult.SKIPPED
        val current = settings.current()
        current.pendingUpdate?.let { pending ->
            if (!Versions.isNewer(BuildConfig.VERSION_NAME, pending.version)) settings.setPendingUpdate(null)
        }
        if (!now && !current.checkForUpdates) return UpdateCheckResult.SKIPPED
        val time = System.currentTimeMillis()
        if (!now && time - settings.lastUpdateCheckAt() < UPDATE_CHECK_INTERVAL_MS) return UpdateCheckResult.SKIPPED
        settings.setLastUpdateCheckAt(time)
        val update = runCatching { updateCheck.latest() }.getOrElse { return UpdateCheckResult.FAILED }
            ?: return UpdateCheckResult.UP_TO_DATE
        return if (Versions.isNewer(BuildConfig.VERSION_NAME, update.version)) {
            settings.setPendingUpdate(update); UpdateCheckResult.FOUND
        } else UpdateCheckResult.UP_TO_DATE
    }

    /** The database setup script shipped inside the app, bundled from the migrations at build time. */
    fun setupSql(): String = context.assets.open("setup.sql").bufferedReader().use { it.readText() }

    /**
     * Only the migrations a stale backend is missing (see `DbUpdate.kt`'s
     * `dbUpdateDelta`), not the whole `setup.sql` -- pasting the whole
     * history into the Supabase SQL Editor in a phone browser is what froze
     * it. Re-asks `fulla_schema_version` rather than trusting the flag
     * `checkDbSchema` stored, since the row that calls this already implies
     * a live connection is worth spending. A version that fails to fetch is
     * treated the same as a missing function -- the row was only shown
     * because `checkDbSchema` already saw `NeedsUpdate` once.
     */
    suspend fun dbUpdateSql(): String {
        val supabase = supabase()
        val version = supabase?.let {
            runCatching { (it.rpc("fulla_schema_version") as? JsonPrimitive)?.intOrNull }.getOrNull()
        }
        val migrations = context.assets.list("migrations").orEmpty().mapNotNull { name ->
            MIGRATION_FILENAME.find(name)?.groupValues?.get(1)?.toInt()?.let { n ->
                n to context.assets.open("migrations/$name").bufferedReader().use { it.readText() }
            }
        }
        return dbUpdateDelta(migrations, version)
    }

    /**
     * The household server this build ships with, or null in a build where
     * people bring their own Supabase project.
     */
    val hosted: Endpoint? = Endpoint.parse(BuildConfig.PROJECT_URL, BuildConfig.ANON_KEY)

    /** Google sign-in is offered when the build has a server and a client id for it. */
    val googleClientId: String? = BuildConfig.GOOGLE_CLIENT_ID.takeIf { it.isNotBlank() && hosted != null }

    private val remoteLock = Mutex()
    private var remote: Pair<Endpoint, Supabase>? = null

    /** The transport for [endpoint], or for the saved project when null. */
    suspend fun supabase(endpoint: Endpoint? = null): Supabase? = remoteLock.withLock {
        val target = endpoint ?: settings.current().endpoint ?: hosted ?: return@withLock null
        remote?.takeIf { it.first == target }?.second
            ?: Supabase(target, http, sessions).also { remote = target to it }
    }

    suspend fun api(endpoint: Endpoint? = null): FullaApi? = supabase(endpoint)?.let(::FullaApi)

    /** Counts every time the app comes to the front, so the screen can look again at what needs saying (a new version). */
    val foreground = MutableStateFlow(0)

    /** An invite link the app was opened with, waiting for the person to act on it. */
    val pendingInvite = MutableStateFlow<InviteLink?>(null)

    /** The welcome flow shown on top of an existing household, to add another one. */
    val addingHousehold = MutableStateFlow(false)

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val syncLock = Mutex()

    /**
     * Writes the fixed costs that came due, in every household on this phone,
     * phone-only ones too: nothing here needs a network or a sync. It looks
     * whenever the app is opened or comes back, once a day while it stays
     * open, every few hours in the background, and as soon as a fixed cost is
     * saved. Safe to call as often as you like; it never throws, because the
     * screen has to open whatever happens here.
     */
    suspend fun generateRecurring(): Int = try {
        ledger.generateRecurringEverywhere(LocalDate.now())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        0
    }

    /** Syncs every shared household on this phone. Called by the worker and by pull-to-refresh. */
    suspend fun syncAll(): SyncOutcome = syncLock.withLock {
        // First, so what is due goes out in this very sync, shared household or not.
        generateRecurring()
        val shared = db.households().all().filter { it.mode == Ledger.CONNECTED }
        if (shared.isEmpty()) return@withLock SyncOutcome.NOTHING_TO_DO
        val api = api() ?: return@withLock SyncOutcome.NOTHING_TO_DO
        // Trips' one-time re-pull, before this pass's sync: a household still
        // owing it must not pull with a stale cursor even once more.
        shared.filterNot { it.tripsRepulled }.forEach { db.households().resetForTripsRepull(it.id) }
        if (supabase()?.currentSession() == null) {
            _syncStatus.update { it.copy(needsSignIn = true) }
            return@withLock SyncOutcome.NEEDS_SIGN_IN
        }
        _syncStatus.update { it.copy(running = true) }
        val syncer = Syncer(RoomSyncStore(db), api, settings.deviceId())
        var outcome = SyncOutcome.DONE
        var error: FullaError? = null
        for (h in shared) {
            try {
                syncer.sync(h.id)
                ledger.finishSharing(h.id, api)
                db.households().setSyncResult(h.id, System.currentTimeMillis(), null)
            } catch (e: FullaError) {
                error = e
                db.households().setSyncResult(h.id, h.lastSyncAt, e.code)
                outcome = when {
                    e.needsSignIn -> SyncOutcome.NEEDS_SIGN_IN
                    e.isTransient -> SyncOutcome.RETRY
                    else -> outcome
                }
            }
        }
        _syncStatus.update {
            SyncStatus(running = false, lastError = error, needsSignIn = outcome == SyncOutcome.NEEDS_SIGN_IN,
                lastFinishedAt = System.currentTimeMillis())
        }
        // A fixed cost another phone just made is known now: it is written, and goes out with the next sync.
        generateRecurring()
        outcome
    }

    fun requestSync() = SyncScheduler.requestSoon(context)

    /**
     * Asks the connected backend's `fulla_schema_version` and stores whether
     * it is behind this build (`Settings.dbNeedsUpdate`), so Settings can
     * show the "database needs an update" row. Silent no-op with nothing
     * connected or nobody signed in yet, and an ambiguous failure (offline,
     * the server down) leaves the stored flag untouched rather than guessing
     * -- see [DbUpdateStatus.Unknown] and `checkSchemaVersion`'s own comment.
     * Called on app start and once an in-app update finishes installing
     * (MainActivity.onStart, UpdateSheet's InstallOutcome.Success).
     */
    suspend fun checkDbSchema() {
        val supabase = supabase() ?: return
        if (supabase.currentSession() == null) return
        when (checkSchemaVersion(supabase)) {
            DbUpdateStatus.NeedsUpdate -> settings.setDbNeedsUpdate(true)
            DbUpdateStatus.UpToDate -> settings.setDbNeedsUpdate(false)
            DbUpdateStatus.Unknown -> {}
        }
    }

    companion object {
        const val UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000L
        private val MIGRATION_FILENAME = Regex("""^(\d{4})_.+\.sql$""")
    }
}
