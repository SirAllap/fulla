// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.sirallap.fulla.core.design.Accent
import io.github.sirallap.fulla.core.design.BaseColors
import io.github.sirallap.fulla.core.design.IdentityColors
import io.github.sirallap.fulla.core.design.MoneyPalette

/**
 * The colours screens read. They ask for `FullaTheme.colors.moneyIn`, never
 * for a hex value: every value comes from core's design/Colors.kt, where a
 * test holds text colours to 4.5:1.
 */
@Immutable
data class FullaColors(
    val paper: Color,
    val paperHigh: Color,
    val ink: Color,
    val inkMuted: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val warning: Color,
    val danger: Color,
    /** What is selected right now: the tab, the chip. */
    val highlight: Color,
    val onHighlight: Color,
    val moneyIn: Color,
    val moneyOut: Color,
    val inSurface: Color,
    val inBody: Color,
    val outSurface: Color,
    val outBody: Color,
    val isDark: Boolean,
) {
    fun member(index: Int): Color = Color(IdentityColors.member(index, isDark))
    fun category(index: Int): Color = Color(IdentityColors.category(index, isDark))

    companion object {
        fun of(dark: Boolean, palette: MoneyPalette, accent: Accent = Accent.DEFAULT): FullaColors {
            val b = BaseColors.of(dark, accent)
            val m = palette.of(dark)
            return FullaColors(
                paper = Color(b.paper), paperHigh = Color(b.paperHigh), ink = Color(b.ink),
                inkMuted = Color(b.inkMuted), line = Color(b.line), accent = Color(b.accent),
                onAccent = Color(b.onAccent), warning = Color(b.warning), danger = Color(b.danger),
                highlight = Color(b.highlight), onHighlight = Color(b.onHighlight),
                moneyIn = Color(m.inText), moneyOut = Color(m.outText),
                inSurface = Color(m.inSurface), inBody = Color(m.inBody),
                outSurface = Color(m.outSurface), outBody = Color(m.outBody),
                isDark = dark,
            )
        }
    }
}

private val LocalFullaColors = staticCompositionLocalOf { FullaColors.of(false, MoneyPalette.DEFAULT) }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object FullaTheme {
    val colors: FullaColors
        @Composable get() = LocalFullaColors.current
}

@Composable
fun FullaTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    palette: MoneyPalette = MoneyPalette.DEFAULT,
    accent: Accent = Accent.DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = FullaColors.of(dark, palette, accent)
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.paper, onBackground = c.ink,
            surface = c.paper, onSurface = c.ink, surfaceVariant = c.paperHigh, onSurfaceVariant = c.inkMuted,
            surfaceContainer = c.paperHigh, surfaceContainerHigh = c.paperHigh, outline = c.line,
            outlineVariant = c.line, error = c.danger,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.paper, onBackground = c.ink,
            surface = c.paper, onSurface = c.ink, surfaceVariant = c.paperHigh, onSurfaceVariant = c.inkMuted,
            surfaceContainer = c.paperHigh, surfaceContainerHigh = c.paperHigh, outline = c.line,
            outlineVariant = c.line, error = c.danger,
        )
    }
    CompositionLocalProvider(LocalFullaColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = FullaType.material, content = content)
    }
}
