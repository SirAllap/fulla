// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

enum class Tab(val label: String, val iconPath: String) {
    ADD("tab_add", "M12 5v14M5 12h14"),
    OVERVIEW("tab_overview", "M4 19V9m6 10V5m6 14v-7m4 7H2"),
    HISTORY("tab_history", "M4 6h16M4 12h16M4 18h10"),
    SETTINGS("settings", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"),
}

/** The page: what is on it, and drawing it again whenever something changes. */
object App {
    private lateinit var root: HTMLElement
    var tab: Tab = Tab.ADD
    /** The settings page being looked at, or null for the list. */
    var settingsPage: SettingsPage? = null
    /** A row being edited from History, or null. */
    var editing: String? = null

    fun start(element: HTMLElement) {
        root = element
        Ledger.onChange = { render() }
    }

    fun go(to: Tab) {
        tab = to
        if (to != Tab.SETTINGS) settingsPage = null
        render()
        window.scrollTo(0.0, 0.0)
    }

    fun setLanguage(code: String) {
        I18n.language = code
        runCatching { localStorage.setItem("fulla.lang", code) }
        document.documentElement?.setAttribute("lang", code)
        render()
    }

    fun render() {
        val scrollY = window.scrollY
        root.clear()
        val view = Ledger.view
        if (view == null) {
            root.appendChild(Onboarding.screen())
            return
        }
        val format = Format(view.config)
        val content = when (tab) {
            Tab.ADD -> AddScreen.build(view, format)
            Tab.OVERVIEW -> OverviewScreen.build(view, format)
            Tab.HISTORY -> HistoryScreen.build(view, format)
            Tab.SETTINGS -> SettingsScreen.build(view, format)
        }
        root.appendChild(content)
        root.appendChild(tabBar())
        window.scrollTo(0.0, scrollY)
    }

    private fun tabBar(): HTMLElement = el("nav", "tabbar") {
        attr("aria-label", "Fulla")
        for (entry in Tab.entries) {
            child("button", if (entry == tab) "tab selected" else "tab") {
                attr("type", "button")
                if (entry == tab) attr("aria-current", "page")
                icon(entry.iconPath)
                span("tab-label") { text(t(entry.label)) }
                click { go(entry) }
            }
        }
    }

    /** A short message at the bottom, with an optional way back. */
    fun toast(message: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
        document.getElementById("toast")?.let { it.parentNode?.removeChild(it) }
        val toast = el("div", "toast") {
            id = "toast"
            attr("role", "status")
            span { text(message) }
        }
        if (actionLabel != null) toast.button(actionLabel, "toast-action") {
            onAction()
            toast.parentNode?.removeChild(toast)
        }
        document.body?.appendChild(toast)
        window.setTimeout({ toast.parentNode?.removeChild(toast) }, if (actionLabel != null) 6000 else 2500)
    }

    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
