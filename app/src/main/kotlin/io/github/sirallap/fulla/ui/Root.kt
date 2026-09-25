// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import io.github.sirallap.fulla.ui.components.LiquidTabBar
import io.github.sirallap.fulla.ui.components.TabItem
import io.github.sirallap.fulla.ui.theme.FullaMotion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.sirallap.fulla.AppContainer
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.data.repo.HouseholdState
import io.github.sirallap.fulla.ui.balances.BalancesScreen
import io.github.sirallap.fulla.ui.entry.EntryScreen
import io.github.sirallap.fulla.ui.history.HistoryScreen
import io.github.sirallap.fulla.ui.home.HomeScreen
import io.github.sirallap.fulla.ui.language.LanguagePickerScreen
import io.github.sirallap.fulla.ui.onboarding.Onboarding
import io.github.sirallap.fulla.ui.settings.SettingsScreen
import io.github.sirallap.fulla.ui.settings.SettingsSection
import io.github.sirallap.fulla.ui.sync.SyncSheet
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.ThemeMode
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private class Loaded(val household: HouseholdState?)

@Composable
fun FullaRoot(container: AppContainer, activity: androidx.fragment.app.FragmentActivity) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val loaded by remember { container.ledger.active.map { Loaded(it) } }.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    FullaTheme(mode = s?.theme ?: ThemeMode.SYSTEM, palette = s?.palette ?: io.github.sirallap.fulla.core.design.MoneyPalette.DEFAULT,
        accent = s?.accent ?: io.github.sirallap.fulla.core.design.Accent.DEFAULT) {
        CompositionLocalProvider(LocalContainer provides container) {
            Box(Modifier.fillMaxSize().background(FullaTheme.colors.paper)) {
                val l = loaded
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var unlocked by rememberSaveable { mutableStateOf(false) }
                val adding by container.addingHousehold.collectAsStateWithLifecycle()
                val invite by container.pendingInvite.collectAsStateWithLifecycle()
                // A new active household (created, joined, restored or switched to) ends the adding flow.
                androidx.compose.runtime.LaunchedEffect(l?.household?.id) { container.addingHousehold.value = false }
                when {
                    s == null || l == null -> Unit // Room answers in a frame; nothing to pretend to wait for.
                    // Upgrading users are already onboarded and never see this;
                    // only a fresh install, before the welcome screen.
                    !s.languageChosen && !s.onboarded -> LanguagePickerScreen(
                        initial = io.github.sirallap.fulla.core.text.AppLanguage.preselectFor(
                            androidx.compose.ui.platform.LocalConfiguration.current.locales[0].toLanguageTag(),
                        ),
                    ) { chosen ->
                        LanguageApplier.set(activity, chosen)
                        scope.launch {
                            container.settings.setLanguageChosen(true)
                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) activity.recreate()
                        }
                    }
                    s.lock && !unlocked -> LockScreen(activity) { unlocked = true }
                    l.household == null -> Onboarding(onCancel = null)
                    adding || invite != null -> Onboarding(onCancel = {
                        container.addingHousehold.value = false
                        container.pendingInvite.value = null
                    })
                    else -> Household(l.household)
                }
            }
        }
    }
}

enum class Tab(val label: Int, val icon: ImageVector) {
    ADD(R.string.tab_add, Icons.Outlined.AddCircleOutline),
    OVERVIEW(R.string.tab_overview, Icons.Outlined.Opacity),
    HISTORY(R.string.tab_history, Icons.Outlined.ReceiptLong),
    BALANCES(R.string.tab_balances, Icons.Outlined.AccountBalanceWallet),
}

@Composable
private fun Household(state: HouseholdState) {
    val container = LocalContainer.current
    val rows by remember(state.id) { container.ledger.transactions(state.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val view = remember(state, rows, language) { HouseholdView(state, rows, language) }
    val nav = rememberNavController()
    var tab by rememberSaveable { mutableStateOf(Tab.ADD) }
    var syncOpen by remember { mutableStateOf(false) }
    var updateOpen by remember { mutableStateOf(false) }
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val pendingUpdate = settings?.pendingUpdate

    val headerActions: @Composable () -> Unit = {
        SyncCloud(view, onClick = { syncOpen = true })
        IconButton(onClick = { nav.navigate("settings") }) {
            if (pendingUpdate != null) {
                BadgedBox(badge = { Badge(containerColor = FullaTheme.colors.accent) }) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.settings), tint = FullaTheme.colors.inkMuted)
                }
            } else {
                Icon(Icons.Outlined.Settings, stringResource(R.string.settings), tint = FullaTheme.colors.inkMuted)
            }
        }
    }

    // Opening something slides it in a little from the side while what was
    // there steps back and dims; going back is the same, reversed.
    val reduced = FullaMotion.reduced()
    val enterSpec = FullaMotion.settle<androidx.compose.ui.unit.IntOffset>(reduced)
    val fade = FullaMotion.functional<Float>(reduced)
    NavHost(nav, startDestination = "tabs", modifier = Modifier.fillMaxSize().statusBarsPadding(),
        enterTransition = { slideInHorizontally(enterSpec) { it / 14 } + fadeIn(fade) },
        exitTransition = { slideOutHorizontally(enterSpec) { -it / 28 } + fadeOut(fade) },
        popEnterTransition = { slideInHorizontally(enterSpec) { -it / 28 } + fadeIn(fade) },
        popExitTransition = { slideOutHorizontally(enterSpec) { it / 14 } + fadeOut(fade) },
    ) {
        composable("tabs") {
            Column(Modifier.fillMaxSize()) {
                AnimatedContent(tab, Modifier.weight(1f), label = "tab", transitionSpec = {
                    val toRight = targetState.ordinal > initialState.ordinal
                    (fadeIn(fade) + slideInHorizontally(enterSpec) { if (toRight) it / 20 else -it / 20 }) togetherWith fadeOut(fade)
                }) { shown ->
                    when (shown) {
                        Tab.ADD -> EntryScreen(view, editingId = null, headerActions = headerActions, onDone = { tab = Tab.OVERVIEW })
                        Tab.OVERVIEW -> HomeScreen(view, headerActions, onOpen = { nav.navigate("edit/$it") }, onBudgets = { nav.navigate("settings/budgets") }, onInsights = { nav.navigate("insights") }, onBackup = { nav.navigate("settings/backup") })
                        Tab.HISTORY -> HistoryScreen(view, headerActions, onOpen = { nav.navigate("edit/$it") }, onImport = { nav.navigate("settings/import") })
                        Tab.BALANCES -> BalancesScreen(view, headerActions, onHouseholdSettings = { nav.navigate("settings/household") })
                    }
                }
                LiquidTabBar(Tab.entries.map { TabItem(stringResource(it.label), it.icon) }, tab.ordinal) { tab = Tab.entries[it] }
            }
        }
        composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            EntryScreen(view, editingId = entry.arguments?.getString("id"), headerActions = null, onDone = { nav.popBackStack() })
        }
        composable("insights") { io.github.sirallap.fulla.ui.insights.InsightsScreen(view, onBack = { nav.popBackStack() }) }
        composable("settings") {
            SettingsScreen(view, SettingsSection.INDEX, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("settings/${it.route}") },
                onUpdate = { updateOpen = true })
        }
        composable("settings/{section}", arguments = listOf(navArgument("section") { type = NavType.StringType })) { entry ->
            val section = SettingsSection.entries.firstOrNull { it.route == entry.arguments?.getString("section") } ?: SettingsSection.INDEX
            SettingsScreen(view, section, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("settings/${it.route}") },
                onUpdate = { updateOpen = true })
        }
    }

    if (syncOpen) SyncSheet(view, onDismiss = { syncOpen = false }, onSettings = { syncOpen = false; nav.navigate("settings/sync") })
    if (updateOpen) pendingUpdate?.let {
        io.github.sirallap.fulla.ui.settings.UpdateSheet(it, onDismiss = { updateOpen = false })
    }
}

/** The quiet indicator in every header. It never blocks and never pops up. */
@Composable
private fun SyncCloud(view: HouseholdView, onClick: () -> Unit) {
    val container = LocalContainer.current
    val c = FullaTheme.colors
    val status by container.syncStatus.collectAsStateWithLifecycle()
    val pending by remember(view.id) { container.ledger.pendingCount(view.id) }.collectAsStateWithLifecycle(initialValue = 0)
    val rejected by remember(view.id) { container.ledger.rejectedCount(view.id) }.collectAsStateWithLifecycle(initialValue = 0)
    val attention = rejected > 0 || status.needsSignIn || (status.lastError != null && status.lastError?.isTransient == false)
    val icon = when {
        !view.state.connected -> Icons.Outlined.PhoneAndroid
        attention -> Icons.Outlined.SyncProblem
        status.lastError?.isTransient == true -> Icons.Outlined.CloudOff
        pending > 0 -> Icons.Outlined.CloudUpload
        else -> Icons.Outlined.CloudDone
    }
    val description = when {
        !view.state.connected -> stringResource(R.string.sync_local_only)
        status.needsSignIn -> stringResource(R.string.sync_sign_in_again)
        attention -> stringResource(R.string.sync_attention)
        pending > 0 -> stringResource(R.string.sync_pending, pending)
        else -> stringResource(R.string.sync_up_to_date)
    }
    IconButton(onClick = onClick) {
        BadgedBox(badge = {
            when {
                attention -> Badge(containerColor = c.warning)
                pending > 0 -> Badge(containerColor = c.accent, contentColor = c.onAccent) { Text("$pending") }
            }
        }) { Icon(icon, description, tint = if (attention) c.warning else c.inkMuted) }
    }
}
