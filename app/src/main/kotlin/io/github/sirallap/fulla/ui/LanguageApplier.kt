// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import io.github.sirallap.fulla.core.text.AppLanguage
import java.util.Locale

/**
 * Applies a chosen language to the process. Android 13+ keeps a per-app
 * language through [LocaleManager]; older phones have no such thing, so the
 * tag is kept in a small synchronous SharedPreferences (DataStore is async
 * and [MainActivity.attachBaseContext] cannot suspend) and read back there to
 * wrap the base [Context] on every launch.
 */
object LanguageApplier {
    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    /** Set on 26–32; read by [wrap] before Compose or anything else runs. */
    fun set(context: Context, language: AppLanguage) {
        val tag = language.tag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, tag).apply()
        }
        Locale.setDefault(Locale.forLanguageTag(tag))
    }

    /** The tag stored for 26–32, or null when none was chosen yet (or on 33+, where the system tracks it). */
    private fun storedTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)

    /**
     * Called from [android.app.Activity.attachBaseContext] on 26–32 only: wraps
     * the base context with a [Configuration] carrying the stored locale, and
     * sets [Locale.setDefault] to match so everything reading it agrees.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = storedTag(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
