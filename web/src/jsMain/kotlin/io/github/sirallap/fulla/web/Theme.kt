// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.core.design.Accent
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
        val money = palette.of(dark)
        return buildString {
            append("--paper:${hex(c.paper)};--paper-high:${hex(c.paperHigh)};--ink:${hex(c.ink)};--ink-muted:${hex(c.inkMuted)};")
            append("--line:${hex(c.line)};--accent:${hex(c.accent)};--on-accent:${hex(c.onAccent)};--warning:${hex(c.warning)};")
            append("--danger:${hex(c.danger)};--highlight:${hex(c.highlight)};--on-highlight:${hex(c.onHighlight)};")
            append("--in-body:${hex(money.inBody)};--out-body:${hex(money.outBody)};")
            append("--in:${hex(money.inText)};--out:${hex(money.outText)};--in-surface:${hex(money.inSurface)};--out-surface:${hex(money.outSurface)};")
            for (i in 0 until 12) append("--cat-$i:${hex(IdentityColors.category(i, dark))};")
            for (i in 0 until 10) append("--member-$i:${hex(IdentityColors.member(i, dark))};")
        }
    }

    /** What the person chose in Settings › Appearance, kept in this browser. */
    var mode: String = pref("theme") ?: "system"
    var accent: Accent = Accent.of(pref("accent"))
    var palette: MoneyPalette = MoneyPalette.entries.firstOrNull { it.name == pref("palette") } ?: MoneyPalette.DEFAULT

    private fun pref(key: String): String? = runCatching { kotlinx.browser.localStorage.getItem("fulla.$key") }.getOrNull()

    fun choose(mode: String? = null, accent: Accent? = null, palette: MoneyPalette? = null) {
        mode?.let { this.mode = it; runCatching { kotlinx.browser.localStorage.setItem("fulla.theme", it) } }
        accent?.let { this.accent = it; runCatching { kotlinx.browser.localStorage.setItem("fulla.accent", it.name) } }
        palette?.let { this.palette = it; runCatching { kotlinx.browser.localStorage.setItem("fulla.palette", it.name) } }
        install()
        App.render()
    }

    fun install() {
        val light = variables(BaseColors.of(false, accent), false)
        val dark = variables(BaseColors.of(true, accent), true)
        val css = when (mode) {
            "light" -> ":root{$light color-scheme:light}"
            "dark" -> ":root{$dark color-scheme:dark}"
            else -> ":root{$light color-scheme:light dark}@media (prefers-color-scheme: dark){:root{$dark}}"
        }
        document.getElementById("theme")?.let { it.parentNode?.removeChild(it) }
        val style = document.createElement("style")
        style.id = "theme"
        style.textContent = css
        document.head?.appendChild(style)
        // The colour of the browser's own bar, to match the page.
        val olds = document.querySelectorAll("meta[name=theme-color]")
        for (i in 0 until olds.length) olds.item(i)?.let { it.parentNode?.removeChild(it) }
        val lightBar = hex(BaseColors.LIGHT.paper)
        val darkBar = hex(BaseColors.DARK.paper)
        val bars = when (mode) { "light" -> listOf("all" to lightBar); "dark" -> listOf("all" to darkBar); else -> listOf("(prefers-color-scheme: light)" to lightBar, "(prefers-color-scheme: dark)" to darkBar) }
        for ((media, color) in bars) {
            val meta = document.createElement("meta")
            meta.setAttribute("name", "theme-color")
            meta.setAttribute("media", media)
            meta.setAttribute("content", color)
            document.head?.appendChild(meta)
        }
    }
}
