// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sirallap.fulla.client.wire.Wire
import kotlinx.serialization.json.put
import io.github.sirallap.fulla.core.demo.DemoData
import io.github.sirallap.fulla.core.design.MoneyPalette
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.data.repo.HouseholdState
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.Tab
import io.github.sirallap.fulla.ui.balances.BalancesScreen
import io.github.sirallap.fulla.ui.components.AddDock
import io.github.sirallap.fulla.ui.components.AddPhase
import io.github.sirallap.fulla.ui.components.LiquidTabBar
import io.github.sirallap.fulla.ui.components.TabItem
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.sirallap.fulla.ui.entry.EntryScreen
import io.github.sirallap.fulla.ui.history.HistoryScreen
import io.github.sirallap.fulla.ui.home.HomeScreen
import io.github.sirallap.fulla.ui.insights.InsightsScreen
import io.github.sirallap.fulla.ui.onboarding.Onboarding
import io.github.sirallap.fulla.ui.settings.SettingsScreen
import io.github.sirallap.fulla.ui.settings.SettingsSection
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.ThemeMode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Every main screen with the demo household, light and dark, on a
 * Pixel-sized screen. They are pictures to look at, for the store listing
 * and for whoever changes a screen, not assertions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
open class ScreenshotTest {
    protected open val prefix = "en"

    private val context get() = ApplicationProvider.getApplicationContext<FullaApp>()
    private val dir = System.getProperty("fulla.screenshots.dir") ?: "build/screenshots"

    private val view: HouseholdView by lazy {
        val demo = DemoData.build(LocalDate.now(), prefix)
        val bundle = Wire.bundle(demo.config)
        HouseholdView(
            HouseholdState(demo.config.household.id, false, bundle, Wire.config(bundle), emptyList(), null, null),
            demo.transactions.map { LocalTransaction(it, SyncState.LOCAL_ONLY) },
        )
    }

    @Before
    fun still() {
        // The jar's settling and the figure's count-up are drawn at rest.
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        for ((mode, suffix) in listOf(ThemeMode.LIGHT to "light", ThemeMode.DARK to "dark")) {
            captureRoboImage("$dir/$prefix-$name-$suffix.png") {
                FullaTheme(mode = mode, palette = MoneyPalette.DEFAULT) {
                    CompositionLocalProvider(LocalContainer provides context.container) {
                        Box(Modifier.fillMaxSize().background(FullaTheme.colors.paper)) { content() }
                    }
                }
            }
        }
    }

    @Test fun overview() = shot("1-overview") { HomeScreen(view, {}, {}, {}, {}, {}, {}) }
    @Test fun add() = shot("2-add") { EntryScreen(view, editingId = null, headerActions = {}, onDone = {}) }
    @Test fun history() = shot("3-history") { HistoryScreen(view, {}, {}, {}) }
    @Test fun balances() = shot("4-balances") { BalancesScreen(view, {}) }
    @Test fun insights() = shot("5-insights") { InsightsScreen(view, {}) }
    @Test fun welcome() = shot("0-welcome") { Onboarding(onCancel = null) }
    @Test fun settings() = shot("6-settings") { SettingsScreen(view, SettingsSection.INDEX, {}, {}) }
    @Test fun people() = shot("7-people") { SettingsScreen(view, SettingsSection.MEMBERS, {}, {}) }
    @Test fun budgets() = shot("8-budgets") { SettingsScreen(view, SettingsSection.BUDGETS, {}, {}) }
    @Test fun saveBar() {
        for ((phase, words) in listOf(AddPhase.IDLE to "", AddPhase.NEEDS_CATEGORY to "Pick a category", AddPhase.READY to "Save · 25.00", AddPhase.SAVED to "Save · 25.00")) {
            val dock = AddDock().also { it.publish(phase, words, {}, {}, {}) }
            shot("10-bar-" + phase.name.lowercase()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f))
                    LiquidTabBar(Tab.entries.map { TabItem(stringResource(it.label), it.icon) }, Tab.ADD.ordinal, center = Tab.ADD.ordinal, dock = dock) {}
                }
            }
        }
    }
    @Test fun widgets() {
        for ((name, paper) in listOf("light" to 0xFFFAF8F3.toInt(), "dark" to 0xFF1B1830.toInt())) {
            val sets = listOf(
                "pebble" to io.github.sirallap.fulla.widget.WidgetArt.card(700, 340, 50f, 0.3f, paper, 0xFFE9B949.toInt(), 0xFFC9971F.toInt(), 4f),
                "pebbleQuick" to io.github.sirallap.fulla.widget.WidgetArt.card(700, 340, 50f, 0.5f, paper, 0xFFE9B949.toInt(), 0xFFC9971F.toInt(), 4f, quick = true),
                "bubble" to io.github.sirallap.fulla.widget.WidgetArt.card(320, 320, 50f, 0.5f, paper, 0xFFE9B949.toInt(), 0xFFC9971F.toInt(), 4f, io.github.sirallap.fulla.widget.WidgetArt.Shape.BUBBLE),
            )
            for ((shape, bitmap) in sets) {
                val file = java.io.File("$dir/$prefix-11-widget-$shape-$name.png").apply { parentFile?.mkdirs() }
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    @Test fun edit() = shot("12-edit") {
        EntryScreen(view, editingId = view.rows.first { it.transaction.kind == io.github.sirallap.fulla.core.model.TransactionKind.EXPENSE }.transaction.id,
            headerActions = null, onDone = {})
    }
    @Test fun saveDock() = shot("13-save-dock") {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            io.github.sirallap.fulla.ui.components.SaveDock("No changes", {}, enabled = false, busy = false, onDiscard = {})
            io.github.sirallap.fulla.ui.components.SaveDock("Apply changes · 6.00", {}, enabled = true, busy = false, onDiscard = {})
            io.github.sirallap.fulla.ui.components.SegmentedChoice(listOf("a" to "Expense", "b" to "Income", "c" to "Refund", "d" to "Transfer"), "b", {})
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                io.github.sirallap.fulla.ui.components.LiquidSwitch(false); io.github.sirallap.fulla.ui.components.LiquidSwitch(true)
            }
            io.github.sirallap.fulla.ui.components.SearchPill("", {}, "Search")
        }
    }
    @Test fun accountPick() = shot("14-account-pick") {
        Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
            io.github.sirallap.fulla.ui.components.PickHead(androidx.compose.ui.res.stringResource(io.github.sirallap.fulla.R.string.account_pick_title))
            val open = view.config.accounts.filter { !it.archived }
            io.github.sirallap.fulla.ui.components.AccountGrid(open + open.take(1), view.analytics.accountBalances(view.active, view.config.accounts, LocalDate.now()),
                { v -> view.formats.money(v) }, {})
        }
    }
    @Test fun activity() = shot("17-activity") {
        val txs = view.rows.map { it.transaction }.filter { it.isActive }.take(4)
        fun json(vararg p: Pair<String, Any?>) = kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in p) when (v) {
                is Number -> put(k, v)
                is String -> put(k, v)
                else -> put(k, kotlinx.serialization.json.JsonNull)
            }
        }
        val who = view.config.members.firstOrNull()?.id
        val now = java.time.Instant.now()
        val events = listOf(
            Wire.AuditEvent(6, now.toString(), "transaction", txs[0].id, "delete", who, false, listOf("status"), json("status" to "active"), json("status" to "deleted")),
            Wire.AuditEvent(5, now.minusSeconds(600).toString(), "transaction", txs[1].id, "update", who, false, listOf("amount_minor", "note"),
                json("amount_minor" to 1250, "note" to "copias"), json("amount_minor" to txs[1].amountMinor, "note" to "copias y carpeta", "client_updated_at" to now.minusSeconds(600).toString())),
            Wire.AuditEvent(4, now.minusSeconds(1200).toString(), "transaction", txs[2].id, "create", who, false, emptyList(), null, json("note" to "Ternera", "amount_minor" to txs[2].amountMinor)),
            Wire.AuditEvent(3, now.minusSeconds(1800).toString(), "transaction", txs[0].id, "restore", null, false, listOf("status"), json("status" to "deleted"), json("status" to "active")),
            Wire.AuditEvent(1, now.minusSeconds(90_000).toString(), "transaction", txs[3].id, "delete", null, true, emptyList(), null, null),
        )
        Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
            io.github.sirallap.fulla.ui.balances.ActivityList(view, events, hasMore = true, loading = false, failed = false, loaded = true,
                connected = true, onMore = {}, onRetry = {}, onOpen = {})
        }
    }
    @Test fun accountLedger() = shot("15-account-ledger") {
        io.github.sirallap.fulla.ui.balances.AccountLedgerScreen(view, accountId = view.config.accounts.first { !it.archived }.id, onBack = {}, onOpen = {})
    }
    @Test fun transferTiles() = shot("16-transfer-tiles") {
        Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
            val open = view.config.accounts.filter { !it.archived }
            val balances = view.analytics.accountBalances(view.active, view.config.accounts, LocalDate.now())
            io.github.sirallap.fulla.ui.components.TransferTiles(open[0], open.getOrNull(1), view.config.accounts, balances, 5_000, { v -> view.formats.money(v) }, {}, {})
            io.github.sirallap.fulla.ui.components.TransferTiles(open[0], null, view.config.accounts, balances, 0, { v -> view.formats.money(v) }, {}, {})
        }
    }
    @Test fun appearance() = shot("9-appearance") { SettingsScreen(view, SettingsSection.APPEARANCE, {}, {}) }
}

/** The same screens in Spanish, for the Spanish store listing. */
@Config(sdk = [34], qualifiers = "es-rES-w411dp-h891dp-xxhdpi")
class ScreenshotEsTest : ScreenshotTest() {
    override val prefix = "es"
}
