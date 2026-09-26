// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.Update
import io.github.sirallap.fulla.core.design.Accent
import io.github.sirallap.fulla.core.design.MoneyPalette
import io.github.sirallap.fulla.core.guide.GuideCursor
import io.github.sirallap.fulla.core.guide.GuideOrigin
import io.github.sirallap.fulla.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** The phone's own settings: never synced, never shared. */
data class Settings(
    val activeHouseholdId: String? = null,
    val endpoint: Endpoint? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: MoneyPalette = MoneyPalette.DEFAULT,
    val accent: Accent = Accent.DEFAULT,
    val lock: Boolean = false,
    val onboarded: Boolean = false,
    /** Set once a language has been chosen, first-run or from settings, so the picker is never shown twice. */
    val languageChosen: Boolean = false,
    val checkForUpdates: Boolean = true,
    /** Found by AppContainer.checkForUpdates, cleared once installed. */
    val pendingUpdate: Update? = null,
    /** The household the guide is currently running for, or null when it is not showing. */
    val guideHousehold: String? = null,
    /** Why the guide started (see GuideOrigin), or null before the first run. */
    val guideOrigin: String? = null,
    /** GuideCursor.encode's format, e.g. "setup:MONTH_START" or "tour:2". */
    val guideStep: String? = null,
    /** Set once the tour has been finished (or skipped) at least once. */
    val tourDone: Boolean = false,
)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val household = stringPreferencesKey("active_household")
        val url = stringPreferencesKey("project_url")
        val anonKey = stringPreferencesKey("anon_key")
        val theme = stringPreferencesKey("theme")
        val palette = stringPreferencesKey("palette")
        val accent = stringPreferencesKey("accent")
        val lock = booleanPreferencesKey("lock")
        val onboarded = booleanPreferencesKey("onboarded")
        val languageChosen = booleanPreferencesKey("language_chosen")
        val deviceId = stringPreferencesKey("device_id")
        val checkForUpdates = booleanPreferencesKey("check_for_updates")
        val lastUpdateCheckAt = longPreferencesKey("last_update_check_at")
        val updateVersion = stringPreferencesKey("update_version")
        val updateNotes = stringPreferencesKey("update_notes")
        val updateApkUrl = stringPreferencesKey("update_apk_url")
        val updateSizeBytes = longPreferencesKey("update_size_bytes")
        val updateSha256 = stringPreferencesKey("update_sha256")
        val guideHousehold = stringPreferencesKey("guide_household")
        val guideOrigin = stringPreferencesKey("guide_origin")
        val guideStep = stringPreferencesKey("guide_step")
        val tourDone = booleanPreferencesKey("tour_done")
    }

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            activeHouseholdId = p[Keys.household],
            endpoint = p[Keys.url]?.let { url -> p[Keys.anonKey]?.let { Endpoint.parse(url, it) } },
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            palette = p[Keys.palette]?.let { runCatching { MoneyPalette.valueOf(it) }.getOrNull() } ?: MoneyPalette.DEFAULT,
            accent = Accent.of(p[Keys.accent]),
            lock = p[Keys.lock] ?: false,
            onboarded = p[Keys.onboarded] ?: false,
            languageChosen = p[Keys.languageChosen] ?: false,
            checkForUpdates = p[Keys.checkForUpdates] ?: true,
            pendingUpdate = p[Keys.updateVersion]?.let { version ->
                p[Keys.updateApkUrl]?.let { url ->
                    Update(version, p[Keys.updateNotes] ?: "", url, p[Keys.updateSizeBytes] ?: 0L, p[Keys.updateSha256])
                }
            },
            guideHousehold = p[Keys.guideHousehold],
            guideOrigin = p[Keys.guideOrigin],
            guideStep = p[Keys.guideStep],
            tourDone = p[Keys.tourDone] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setActiveHousehold(id: String?) { store.edit { if (id == null) { it.remove(Keys.household) } else { it[Keys.household] = id } } }

    suspend fun setEndpoint(endpoint: Endpoint?) {
        store.edit {
            if (endpoint == null) { it.remove(Keys.url); it.remove(Keys.anonKey) }
            else { it[Keys.url] = endpoint.url; it[Keys.anonKey] = endpoint.anonKey }
        }
    }

    suspend fun setTheme(mode: ThemeMode) { store.edit { it[Keys.theme] = mode.name } }
    suspend fun setPalette(palette: MoneyPalette) { store.edit { it[Keys.palette] = palette.name } }
    suspend fun setAccent(accent: Accent) { store.edit { it[Keys.accent] = accent.name } }
    suspend fun setLock(on: Boolean) { store.edit { it[Keys.lock] = on } }
    suspend fun setOnboarded(done: Boolean) { store.edit { it[Keys.onboarded] = done } }
    suspend fun setLanguageChosen(done: Boolean) { store.edit { it[Keys.languageChosen] = done } }
    suspend fun setCheckForUpdates(on: Boolean) { store.edit { it[Keys.checkForUpdates] = on } }

    /**
     * Starts the guide for [id], from [origin]. Overwrites whatever guide was
     * running before, and always leaves `guide_step` set to
     * [GuideCursor.START] (never null and never a step left over from a
     * previous household's guide), so GuideHost's `guide_step != null` gate
     * shows it right away instead of nothing happening.
     */
    suspend fun startGuide(id: String, origin: GuideOrigin) {
        store.edit {
            it[Keys.guideHousehold] = id
            it[Keys.guideOrigin] = origin.name.lowercase()
            it[Keys.guideStep] = GuideCursor.START
        }
    }

    suspend fun setGuideStep(s: String) { store.edit { it[Keys.guideStep] = s } }

    /** Ends the guide: nothing left to show, and the tour will not be offered again by itself. */
    suspend fun finishGuide() {
        store.edit {
            it.remove(Keys.guideHousehold); it.remove(Keys.guideOrigin); it.remove(Keys.guideStep)
            it[Keys.tourDone] = true
        }
    }

    suspend fun lastUpdateCheckAt(): Long = store.data.first()[Keys.lastUpdateCheckAt] ?: 0L
    suspend fun setLastUpdateCheckAt(at: Long) { store.edit { it[Keys.lastUpdateCheckAt] = at } }

    /** The update found by AppContainer.checkForUpdates, or null to clear it once installed. */
    suspend fun setPendingUpdate(update: Update?) {
        store.edit { p ->
            if (update == null) {
                p.remove(Keys.updateVersion); p.remove(Keys.updateNotes); p.remove(Keys.updateApkUrl)
                p.remove(Keys.updateSizeBytes); p.remove(Keys.updateSha256)
            } else {
                p[Keys.updateVersion] = update.version
                p[Keys.updateNotes] = update.notes
                p[Keys.updateApkUrl] = update.apkUrl
                p[Keys.updateSizeBytes] = update.sizeBytes
                val sha = update.sha256
                if (sha != null) p[Keys.updateSha256] = sha else p.remove(Keys.updateSha256)
            }
        }
    }

    /** When a backup of [householdId] was last saved from this phone, epoch millis, or null. */
    fun lastBackup(householdId: String): Flow<Long?> = store.data.map { it[longPreferencesKey("backup_at_$householdId")] }

    suspend fun setLastBackup(householdId: String, at: Long) {
        store.edit { it[longPreferencesKey("backup_at_$householdId")] = at }
    }

    /** A random id for this installation: the push's `client_id`. Not tied to the device or the person. */
    suspend fun deviceId(): String {
        store.data.first()[Keys.deviceId]?.let { return it }
        val id = UUID.randomUUID().toString()
        store.edit { if (it[Keys.deviceId] == null) { it[Keys.deviceId] = id } }
        return store.data.first()[Keys.deviceId] ?: id
    }
}
