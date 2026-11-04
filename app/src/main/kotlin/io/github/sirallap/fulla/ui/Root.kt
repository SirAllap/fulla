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
import androidx.compose.material.icons.outlined.Insights
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
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import io.github.sirallap.fulla.ui.components.AddDock
import io.github.sirallap.fulla.ui.components.LiquidTabBar
import io.github.sirallap.fulla.ui.components.TabItem
import io.github.sirallap.fulla.ui.guide.GuideHost
import io.github.sirallap.fulla.ui.guide.guideTarget
import io.github.sirallap.fulla.ui.theme.FullaMotion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
        val forms = remember { io.github.sirallap.fulla.ui.components.FormLayer() }
        CompositionLocalProvider(LocalContainer provides container, io.github.sirallap.fulla.ui.components.LocalFormLayer provides forms,
            io.github.sirallap.fulla.ui.components.LocalSaveColor provides s?.saveColor) {
          Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(FullaTheme.colors.paper)) {
                val l = loaded
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var unlocked by rememberSaveable { mutableStateOf(false) }
                val adding by container.addingHousehold.collectAsStateWithLifecycle()
                val invite by container.pendingInvite.collectAsStateWithLifecycle()
                val connect by container.pendingConnect.collectAsStateWithLifecycle()
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
                    adding || invite != null || connect != null -> Onboarding(onCancel = {
                        container.addingHousehold.value = false
                        container.pendingInvite.value = null
                        container.pendingConnect.value = null
                    })
                    else -> Household(l.household)
                }
            }
            // Forms, over everything, in this window (FormScreen).
            io.github.sirallap.fulla.ui.components.FormLayerHost(forms)
          }
        }
    }
}

/** The tabs, in the order of the bar: two on each side of Add, which is the round button in the middle. */
enum class Tab(val label: Int, val icon: ImageVector) {
    OVERVIEW(R.string.tab_overview, Icons.Outlined.Opacity),
    HISTORY(R.string.tab_history, Icons.Outlined.ReceiptLong),
    ADD(R.string.tab_add, Icons.Outlined.AddCircleOutline),
    INSIGHTS(R.string.insights, Icons.Outlined.Insights),
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
    /** What Add tells the tab bar's round button (Save, and the drop that discards). */
    val addDock = remember { AddDock() }
    /** An income asked for from outside (a shortcut), until Add has turned to it. */
    var incomeAsked by remember { mutableStateOf(false) }
    /** A category asked for from outside (the widget's shortcuts), until Add has taken it. */
    var categoryAsked by remember { mutableStateOf<String?>(null) }
    var syncOpen by remember { mutableStateOf(false) }
    var updateOpen by remember { mutableStateOf(false) }
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val pendingUpdate = settings?.pendingUpdate
    val guideTargets = io.github.sirallap.fulla.ui.guide.rememberGuideTargets()
    // A new version: told once, in whatever screen the person is on, and again a day later while it is not installed.
    // Not over the guide. Dismissing it leaves the gear's dot and the banner at the top of Settings.
    val foreground by container.foreground.collectAsStateWithLifecycle()
    LaunchedEffect(pendingUpdate?.version, settings?.updateAnnouncedVersion, settings?.updateAnnouncedAt, settings?.guideHousehold, foreground) {
        val s = settings ?: return@LaunchedEffect
        val version = s.pendingUpdate?.version ?: return@LaunchedEffect
        // Just installed: the stored update is cleared at the next check, and until then it must not be announced again.
        if (!io.github.sirallap.fulla.core.version.Versions.isNewer(io.github.sirallap.fulla.BuildConfig.VERSION_NAME, version)) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (s.guideHousehold == null && io.github.sirallap.fulla.client.remote.UpdateAnnouncement.due(version, s.updateAnnouncedVersion, s.updateAnnouncedAt, now)) {
            container.settings.setUpdateAnnounced(version, now)
            updateOpen = true
        }
    }

    // The widget and the launcher shortcuts: back to the tabs, on Add or on the overview.
    val open by container.pendingOpen.collectAsStateWithLifecycle()
    LaunchedEffect(open) {
        val request = open ?: return@LaunchedEffect
        container.pendingOpen.value = null
        nav.popBackStack("tabs", inclusive = false)
        when (request.where) {
            io.github.sirallap.fulla.OpenRequest.Where.ADD -> { tab = Tab.ADD; categoryAsked = request.categoryId }
            io.github.sirallap.fulla.OpenRequest.Where.ADD_INCOME -> { tab = Tab.ADD; incomeAsked = true }
            io.github.sirallap.fulla.OpenRequest.Where.OVERVIEW -> tab = Tab.OVERVIEW
        }
    }

    CompositionLocalProvider(io.github.sirallap.fulla.ui.guide.LocalGuideTargets provides guideTargets) {
        val headerActions: @Composable () -> Unit = {
            SyncCloud(view, onClick = { syncOpen = true })
            IconButton(onClick = { nav.go("settings") }, modifier = Modifier.guideTarget(io.github.sirallap.fulla.core.guide.TourStop.GEAR)) {
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
            screen("tabs") {
                Column(Modifier.fillMaxSize()) {
                    AnimatedContent(tab, Modifier.weight(1f), label = "tab", transitionSpec = {
                        val toRight = targetState.ordinal > initialState.ordinal
                        (fadeIn(fade) + slideInHorizontally(enterSpec) { if (toRight) it / 20 else -it / 20 }) togetherWith fadeOut(fade)
                    }) { shown ->
                        when (shown) {
                            Tab.ADD -> EntryScreen(view, editingId = null, headerActions = headerActions, onDone = { tab = Tab.OVERVIEW },
                                incomeAsked = incomeAsked, onIncomeShown = { incomeAsked = false },
                                categoryAsked = categoryAsked, onCategoryShown = { categoryAsked = null }, dock = addDock)
                            Tab.OVERVIEW -> HomeScreen(view, headerActions, onOpen = { nav.go("edit/$it") }, onBudgets = { nav.go("settings/budgets") }, onFixedCosts = { nav.go("settings/recurring") }, onInsights = { tab = Tab.INSIGHTS }, onBackup = { nav.go("settings/backup") }, onTrip = { nav.go("trip/$it") }, onAccounts = { tab = Tab.BALANCES })
                            Tab.HISTORY -> HistoryScreen(view, headerActions, onOpen = { nav.go("edit/$it") }, onImport = { nav.go("settings/import") })
                            Tab.INSIGHTS -> io.github.sirallap.fulla.ui.insights.InsightsScreen(view, onBack = { tab = Tab.OVERVIEW }, onFixedCosts = { nav.go("settings/recurring") }, headerActions = headerActions)
                            Tab.BALANCES -> BalancesScreen(view, headerActions, onHouseholdSettings = { nav.go("settings/household") }, onGoals = { nav.go("settings/goals") }, onAccounts = { nav.go("settings/accounts") }, onActivity = { nav.go("activity") }, onOpenAccount = { nav.go("ledger/$it") })
                        }
                    }
                    LiquidTabBar(Tab.entries.map { TabItem(stringResource(it.label), it.icon) }, tab.ordinal, center = Tab.ADD.ordinal, dock = addDock) { tab = Tab.entries[it] }
                }
            }
            screen("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                EntryScreen(view, editingId = entry.arguments?.getString("id"), headerActions = null, onDone = { nav.back() })
            }
            screen("activity") {
                io.github.sirallap.fulla.ui.balances.ActivityScreen(view, accountId = null, onBack = { nav.back() }, onOpen = { nav.go("edit/$it") })
            }
            screen("activity/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                io.github.sirallap.fulla.ui.balances.ActivityScreen(view, accountId = entry.arguments?.getString("id"), onBack = { nav.back() }, onOpen = { nav.go("edit/$it") })
            }
            screen("ledger/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                io.github.sirallap.fulla.ui.balances.AccountLedgerScreen(view, accountId = entry.arguments?.getString("id") ?: "",
                    onBack = { nav.back() }, onOpen = { nav.go("edit/$it") },
                    onChanges = { nav.go("activity/${entry.arguments?.getString("id")}") })
            }
            screen("insights") { io.github.sirallap.fulla.ui.insights.InsightsScreen(view, onBack = { nav.back() }, onFixedCosts = { nav.go("settings/recurring") }) }
            screen("trip/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                io.github.sirallap.fulla.ui.trips.TripScreen(view, tripId = entry.arguments?.getString("id") ?: "",
                    onBack = { nav.back() }, onOpenTransaction = { nav.go("edit/$it") },
                    onAdd = { nav.popBackStack("tabs", inclusive = false); tab = Tab.ADD })
            }
            screen("settings") {
                SettingsScreen(view, SettingsSection.INDEX, onBack = { nav.back() }, onOpen = { nav.go("settings/${it.route}") },
                    onUpdate = { updateOpen = true })
            }
            screen("settings/{section}", arguments = listOf(navArgument("section") { type = NavType.StringType })) { entry ->
                val section = SettingsSection.entries.firstOrNull { it.route == entry.arguments?.getString("section") } ?: SettingsSection.INDEX
                SettingsScreen(view, section, onBack = { nav.back() }, onOpen = { nav.go("settings/${it.route}") },
                    onUpdate = { updateOpen = true })
            }
        }

        if (syncOpen) SyncSheet(view, onDismiss = { syncOpen = false }, onSettings = { syncOpen = false; nav.go("settings/sync") })
        if (updateOpen) pendingUpdate?.let {
            io.github.sirallap.fulla.ui.settings.UpdateSheet(it, onDismiss = { updateOpen = false })
        }

        GuideHost(view, tab, setTab = { tab = it })

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
    IconButton(onClick = onClick, modifier = Modifier.guideTarget(io.github.sirallap.fulla.core.guide.TourStop.SYNC_CLOUD)) {
        BadgedBox(badge = {
            when {
                attention -> Badge(containerColor = c.warning)
                pending > 0 -> Badge(containerColor = c.accent, contentColor = c.onAccent) { Text("$pending") }
            }
        }) { Icon(icon, description, tint = if (attention) c.warning else c.inkMuted) }
    }
}


/**
 * A screen of the NavHost that stops listening the moment it starts leaving.
 *
 * While a screen slides and fades out it is still drawn, on top of the one coming back, and used to
 * take the touches meant for the screen under it: Alice leaves the account book, taps an account on
 * the Balances tab at once, and the tap lands on a row of the book that is on its way out. The
 * entering screen is never blocked: its target is already `Visible`.
 */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route, arguments = arguments) { entry ->
    val leaving = transition.targetState != EnterExitState.Visible
    Box(Modifier.fillMaxSize()) {
        content(entry)
        if (leaving) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            })
        }
    }
}

/** Opens a route once: a second tap on the same thing while it opens does not stack a second copy. */
private fun NavController.go(route: String) = navigate(route) { launchSingleTop = true }

/** Goes back once: a second tap on Back while the first is still animating must not pop the tabs away. */
private fun NavController.back() {
    if (previousBackStackEntry != null) popBackStack()
}
