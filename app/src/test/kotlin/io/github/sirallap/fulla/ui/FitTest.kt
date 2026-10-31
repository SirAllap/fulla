// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import io.github.sirallap.fulla.FullaApp
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.demo.DemoData
import io.github.sirallap.fulla.core.design.MoneyPalette
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.data.repo.HouseholdState
import io.github.sirallap.fulla.ui.balances.BalancesScreen
import io.github.sirallap.fulla.ui.entry.EntryScreen
import io.github.sirallap.fulla.ui.history.HistoryScreen
import io.github.sirallap.fulla.ui.home.HomeScreen
import io.github.sirallap.fulla.ui.insights.InsightsScreen
import io.github.sirallap.fulla.ui.onboarding.Onboarding
import io.github.sirallap.fulla.ui.settings.SettingsScreen
import io.github.sirallap.fulla.ui.settings.SettingsSection
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Every screen on the smallest phone Fulla supports (320 dp wide) with the
 * system text at 200 % (docs/ux.md, "Every phone, every text size"). Fails
 * when anything ends up past the side of the screen: cut off, or reachable
 * only by a scroll the person cannot see. Rows that scroll sideways on
 * purpose (filter chips) are left out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class FitTest {
    @get:Rule val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<FullaApp>()

    private val view: HouseholdView by lazy {
        val demo = DemoData.build(LocalDate.now(), "en")
        val bundle = Wire.bundle(demo.config)
        HouseholdView(
            HouseholdState(demo.config.household.id, false, bundle, Wire.config(bundle), emptyList(), null, null),
            demo.transactions.map { LocalTransaction(it, SyncState.LOCAL_ONLY) },
        )
    }

    private fun fits(content: @Composable () -> Unit) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                FullaTheme(mode = ThemeMode.LIGHT, palette = MoneyPalette.DEFAULT) {
                    CompositionLocalProvider(LocalContainer provides context.container) {
                        Box(Modifier.fillMaxSize().background(FullaTheme.colors.paper)) { content() }
                    }
                }
            }
        }
        rule.waitForIdle()
        val outside = mutableListOf<String>()
        for (root in rule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()) {
            val width = root.boundsInRoot.width
            fun walk(node: SemanticsNode) {
                // A row that scrolls sideways is meant to run past the edge.
                if (node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null) return
                val b = node.boundsInRoot
                if (b.width > 0f && (b.left < -0.5f || b.right > width + 0.5f)) {
                    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                        ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                        ?: "node ${node.id}"
                    outside += "\"$text\" spans ${b.left.toInt()}..${b.right.toInt()} of $width px"
                }
                node.children.forEach(::walk)
            }
            walk(root)
        }
        assertTrue("past the side of the screen:\n" + outside.joinToString("\n"), outside.isEmpty())
    }

    @Test fun welcome() = fits { Onboarding(onCancel = null) }
    @Test fun overview() = fits { HomeScreen(view, {}, {}, {}, {}, {}, {}) }
    @Test fun add() = fits { EntryScreen(view, editingId = null, headerActions = {}, onDone = {}) }
    @Test fun history() = fits { HistoryScreen(view, {}, {}, {}) }
    @Test fun balances() = fits { BalancesScreen(view, {}) }
    @Test fun insights() = fits { InsightsScreen(view, {}) }

    @Test fun settingsIndex() = section(SettingsSection.INDEX)
    @Test fun households() = section(SettingsSection.HOUSEHOLDS)
    @Test fun household() = section(SettingsSection.HOUSEHOLD)
    @Test fun members() = section(SettingsSection.MEMBERS)
    @Test fun categories() = section(SettingsSection.CATEGORIES)
    @Test fun fields() = section(SettingsSection.FIELDS)
    @Test fun accounts() = section(SettingsSection.ACCOUNTS)
    @Test fun budgets() = section(SettingsSection.BUDGETS)
    @Test fun trips() = section(SettingsSection.TRIPS)
    @Test fun goals() = section(SettingsSection.GOALS)
    @Test fun recurring() = section(SettingsSection.RECURRING)
    @Test fun import() = section(SettingsSection.IMPORT)
    @Test fun rules() = section(SettingsSection.RULES)
    @Test fun backup() = section(SettingsSection.BACKUP)
    @Test fun appearance() = section(SettingsSection.APPEARANCE)
    @Test fun sync() = section(SettingsSection.SYNC)
    @Test fun about() = section(SettingsSection.ABOUT)

    private fun section(s: SettingsSection) = fits { SettingsScreen(view, s, {}, {}) }
}
