// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.sirallap.fulla.AppContainer
import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.data.repo.HouseholdState

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer") }

/**
 * The active household as every tab sees it: its structure, its rows, and
 * the ways to write them down. Rebuilt when either changes.
 */
class HouseholdView(
    val state: HouseholdState,
    val rows: List<LocalTransaction>,
    /** The app's language: default categories and accounts read in it. */
    language: String = java.util.Locale.getDefault().language,
) {
    val id: String get() = state.id
    val config = io.github.sirallap.fulla.core.defaults.Defaults.localized(state.config, language)
    val formats = Formats(config)
    val analytics = Analytics(config, formats.periodRule)
    val active: List<Transaction> = rows.map { it.transaction }.filter { it.isActive }
    val me: Member? get() = config.me()

    fun memberName(id: String?): String = config.member(id)?.displayName ?: "?"
    fun categoryName(id: String?): String? = config.category(id)?.name
    fun accountName(id: String?): String? = config.account(id)?.name
}
