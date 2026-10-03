// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.core.design.BaseColors
import io.github.sirallap.fulla.core.design.IdentityColors
import io.github.sirallap.fulla.core.design.MoneyPalette
import io.github.sirallap.fulla.core.design.ThemeColors
import kotlinx.browser.document

/**
 * Fulla's colours are written once, in core/design/Colors.kt, where a test
 * holds every text colour to WCAG AA. The page takes them from there as CSS
 * variables, light and dark, and never writes a hex value of its own.
 */
object Theme {
    private fun hex(argb: Long): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')

    private fun variables(c: ThemeColors, dark: Boolean): String {
        val money = MoneyPalette.DEFAULT.of(dark)
        return buildString {
            append("--paper:${hex(c.paper)};--paper-high:${hex(c.paperHigh)};--ink:${hex(c.ink)};--ink-muted:${hex(c.inkMuted)};")
            append("--line:${hex(c.line)};--accent:${hex(c.accent)};--on-accent:${hex(c.onAccent)};--warning:${hex(c.warning)};")
            append("--danger:${hex(c.danger)};--highlight:${hex(c.highlight)};--on-highlight:${hex(c.onHighlight)};")
            append("--in:${hex(money.inText)};--out:${hex(money.outText)};--in-surface:${hex(money.inSurface)};--out-surface:${hex(money.outSurface)};")
            for (i in 0 until 12) append("--cat-$i:${hex(IdentityColors.category(i, dark))};")
        }
    }

    fun install() {
        val css = ":root{${variables(BaseColors.LIGHT, false)}color-scheme:light dark}" +
            "@media (prefers-color-scheme: dark){:root{${variables(BaseColors.DARK, true)}}}"
        val style = document.createElement("style")
        style.id = "theme"
        style.textContent = css
        document.head?.appendChild(style)
        // The colour of the browser's own bar, to match the page.
        val light = hex(BaseColors.LIGHT.paper)
        val dark = hex(BaseColors.DARK.paper)
        for ((media, color) in listOf("(prefers-color-scheme: light)" to light, "(prefers-color-scheme: dark)" to dark)) {
            val meta = document.createElement("meta")
            meta.setAttribute("name", "theme-color")
            meta.setAttribute("media", media)
            meta.setAttribute("content", color)
            document.head?.appendChild(meta)
        }
    }
}
