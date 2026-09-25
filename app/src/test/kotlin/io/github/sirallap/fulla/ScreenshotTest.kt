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
import io.github.sirallap.fulla.core.demo.DemoData
import io.github.sirallap.fulla.core.design.MoneyPalette
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.data.repo.HouseholdState
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
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

    @Test fun overview() = shot("1-overview") { HomeScreen(view, {}, {}, {}, {}, {}) }
    @Test fun add() = shot("2-add") { EntryScreen(view, editingId = null, headerActions = {}, onDone = {}) }
    @Test fun history() = shot("3-history") { HistoryScreen(view, {}, {}, {}) }
    @Test fun balances() = shot("4-balances") { BalancesScreen(view, {}) }
    @Test fun insights() = shot("5-insights") { InsightsScreen(view, {}) }
    @Test fun welcome() = shot("0-welcome") { Onboarding(onCancel = null) }
    @Test fun settings() = shot("6-settings") { SettingsScreen(view, SettingsSection.INDEX, {}, {}) }
    @Test fun people() = shot("7-people") { SettingsScreen(view, SettingsSection.MEMBERS, {}, {}) }
    @Test fun budgets() = shot("8-budgets") { SettingsScreen(view, SettingsSection.BUDGETS, {}, {}) }
    @Test fun appearance() = shot("9-appearance") { SettingsScreen(view, SettingsSection.APPEARANCE, {}, {}) }
}

/** The same screens in Spanish, for the Spanish store listing. */
@Config(sdk = [34], qualifiers = "es-rES-w411dp-h891dp-xxhdpi")
class ScreenshotEsTest : ScreenshotTest() {
    override val prefix = "es"
}
