// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.design

/**
 * Every colour Fulla uses, as ARGB, in one place a test can check.
 *
 * Kept in core, not in the app, so that ColorsTest can hold every colour that
 * carries text to WCAG AA (4.5:1) against both surfaces of its theme without
 * an Android device. The app only wraps these values.
 */
data class ThemeColors(
    val paper: Long,
    val paperHigh: Long,
    val ink: Long,
    val inkMuted: Long,
    val line: Long,
    val accent: Long,
    val onAccent: Long,
    val warning: Long,
    /** Destructive actions. No money palette changes it. */
    val danger: Long,
    /** What is chosen right now: the tab, the chip. Fulla's gold. */
    val highlight: Long,
    val onHighlight: Long,
    val isDark: Boolean,
)

object BaseColors {
    /**
     * Ink and gold. On paper the primary action is ink; in the dark it is
     * gold. Gold always marks what is selected, with ink on it.
     */
    val LIGHT = ThemeColors(
        paper = 0xFFFAF8F3, paperHigh = 0xFFF2EEE5, ink = 0xFF1B1830, inkMuted = 0xFF625C6E,
        line = 0xFFE3DDCF, accent = 0xFF1B1830, onAccent = 0xFFF6F1E7, warning = 0xFF8A5010,
        danger = 0xFFB8223F, highlight = 0xFFE9B949, onHighlight = 0xFF1B1830, isDark = false,
    )
    val DARK = ThemeColors(
        paper = 0xFF13111B, paperHigh = 0xFF1E1B29, ink = 0xFFF3EFE6, inkMuted = 0xFFA8A2B5,
        line = 0xFF2F2B3D, accent = 0xFFE9B949, onAccent = 0xFF1B1830, warning = 0xFFF0AE62,
        danger = 0xFFFF7A9C, highlight = 0xFFE9B949, onHighlight = 0xFF1B1830, isDark = true,
    )

    fun of(dark: Boolean): ThemeColors = if (dark) DARK else LIGHT

    /**
     * The base theme with the phone's accent: it marks the selection in both
     * themes and, in the dark, is the primary action too. On paper the primary
     * action stays ink, whatever the accent.
     */
    fun of(dark: Boolean, accent: Accent): ThemeColors {
        val base = of(dark)
        return if (dark) base.copy(highlight = accent.fill, onHighlight = accent.onFill, accent = accent.fill, onAccent = accent.onFill)
        else base.copy(highlight = accent.fill, onHighlight = accent.onFill)
    }
}

/**
 * The accent, chosen per phone in Settings › Appearance. Gold is Fulla's own
 * and the default. Every accent is light enough that ink reads on it and, in
 * the dark, that it reads as text on the paper.
 */
enum class Accent(val fill: Long, val onFill: Long = 0xFF1B1830) {
    GOLD(0xFFE9B949),
    LAVENDER(0xFFB6A6FF),
    SEA(0xFF7CCFDF),
    SAGE(0xFFA3D39C),
    ROSE(0xFFF4A7BE),
    EMBER(0xFFF3A57A),
    PLATINUM(0xFFD2CDDA);

    companion object {
        val DEFAULT = GOLD
        fun of(name: String?): Accent = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The colours of money: text for amounts in and out, and the two liquids of
 * the home screen (a light surface and a deeper body for each).
 */
data class MoneyColors(
    val inText: Long,
    val outText: Long,
    val inSurface: Long,
    val inBody: Long,
    val outSurface: Long,
    val outBody: Long,
)

/** Chosen per phone in Settings › Appearance. Ink is the default. */
enum class MoneyPalette(val light: MoneyColors, val dark: MoneyColors) {
    /** Ink and gold: money in is gold, money out is ink. */
    INK(
        light = MoneyColors(0xFF7A5608, 0xFF3B3648, 0xFFEBC766, 0xFFB8871F, 0xFFA29CAE, 0xFF3B3648),
        dark = MoneyColors(0xFFEBC766, 0xFFB6B0C2, 0xFFEBC766, 0xFFA87A17, 0xFFB6B0C2, 0xFF4F4962),
    ),
    AMBER(
        light = MoneyColors(0xFF7F5B12, 0xFF5E1A33, 0xFFE8C35A, 0xFFA7791A, 0xFFA8476A, 0xFF5E1A33),
        dark = MoneyColors(0xFFF2CF6B, 0xFFE08AAB, 0xFFF2CF6B, 0xFFB8871F, 0xFFC4648A, 0xFF6E2440),
    ),
    SEA(
        light = MoneyColors(0xFF1F5E73, 0xFF8A4829, 0xFF6BB8C8, 0xFF1F5E73, 0xFFD69A70, 0xFF8C4A2B),
        dark = MoneyColors(0xFF8FD0DD, 0xFFE6AE86, 0xFF8FD0DD, 0xFF2D7A90, 0xFFE6AE86, 0xFFA55A36),
    ),
    FOREST(
        light = MoneyColors(0xFF2F6B3A, 0xFF5C2F55, 0xFF8CC08A, 0xFF2F6B3A, 0xFFB889AB, 0xFF5C2F55),
        dark = MoneyColors(0xFFA6D6A3, 0xFFCFA2C3, 0xFFA6D6A3, 0xFF3F8A4C, 0xFFCFA2C3, 0xFF7A4270),
    ),
    CLASSIC(
        light = MoneyColors(0xFF0A6E55, 0xFFC0254E, 0xFF35B894, 0xFF0B795E, 0xFFE86A8C, 0xFFCC2955),
        dark = MoneyColors(0xFF4FD1B0, 0xFFFF7A9C, 0xFF6FE0C2, 0xFF2E9C80, 0xFFFF9DB6, 0xFFC9486E),
    );

    fun of(dark: Boolean): MoneyColors = if (dark) this.dark else light

    companion object {
        val DEFAULT = INK
        fun of(name: String?): MoneyPalette = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Accents that tell members apart (always together with their initials, never
 * alone) and colours for categories. Ten members, twelve categories.
 */
object IdentityColors {
    val members: List<Long> = listOf(
        0xFF7A5A0E, 0xFF0A6E55, 0xFFB0452A, 0xFF1F5E73, 0xFF8F3E8A,
        0xFF4B3F8F, 0xFFC0254E, 0xFF2F6B3A, 0xFF3D5A8F, 0xFF7A4B2A,
    )
    val membersDark: List<Long> = listOf(
        0xFFE9C66A, 0xFF4FD1B0, 0xFFF29A7E, 0xFF8FD0DD, 0xFFE3A2DD,
        0xFFB8ADEB, 0xFFFF7A9C, 0xFFA6D6A3, 0xFF9DB6FF, 0xFFE0B08A,
    )
    val categories: List<Long> = listOf(
        0xFF7A5A0E, 0xFF0A6E55, 0xFFB0452A, 0xFF1F5E73, 0xFF8F3E8A, 0xFF4B3F8F,
        0xFFC0254E, 0xFF2F6B3A, 0xFF3D5A8F, 0xFF7A4B2A, 0xFF4D5B6B, 0xFF6B6480,
    )
    val categoriesDark: List<Long> = listOf(
        0xFFE9C66A, 0xFF4FD1B0, 0xFFF29A7E, 0xFF8FD0DD, 0xFFE3A2DD, 0xFFB8ADEB,
        0xFFFF7A9C, 0xFFA6D6A3, 0xFF9DB6FF, 0xFFE0B08A, 0xFFB7C3D0, 0xFFA49CBB,
    )

    fun member(index: Int, dark: Boolean): Long = (if (dark) membersDark else members)[index.mod(10)]
    fun category(index: Int, dark: Boolean): Long = (if (dark) categoriesDark else categories)[index.mod(12)]
}

/** WCAG 2.x contrast. */
object Contrast {
    const val TEXT_MINIMUM = 4.5

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}

/**
 * The mark's colours: Fulla's golden band on ink. Never used for text, so they
 * are outside the contrast rules; the launcher icon repeats them as resources.
 */
object BrandColors {
    const val INK = 0xFF1B1830
    const val CREAM = 0xFFF6F1E7
    /** The band on a dark ground. */
    const val GOLD = 0xFFE9B949
    /** The band on a light ground, a shade deeper so it does not wash out. */
    const val GOLD_DEEP = 0xFFC9971F
}
