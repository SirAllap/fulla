// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/** The four tabs of the Android app, with its icons. Everything else hangs off the gear. */
enum class Tab(val label: String, val icon: String) {
    ADD("tab_add", "add_circle_outline"),
    OVERVIEW("tab_overview", "opacity"),
    HISTORY("tab_history", "receipt_long"),
    BALANCES("tab_balances", "account_balance_wallet"),
}

/** The page: what is on it, and drawing it again whenever something changes. */
object App {
    private lateinit var root: HTMLElement
    var tab: Tab = Tab.ADD
    /** Settings is a screen of its own over the tabs, reached by the gear. */
    var settingsOpen: Boolean = false
    /** The settings page being looked at, or null for the list. */
    var settingsPage: SettingsPage? = null
    /** The trip being looked at, or null. */
    var tripId: String? = null
    /** A row being edited from History, or null. */
    var editing: String? = null

    fun start(element: HTMLElement) {
        root = element
        Ledger.onChange = { render() }
    }

    fun go(to: Tab) {
        tab = to
        settingsOpen = false
        render()
        window.scrollTo(0.0, 0.0)
    }

    fun openSettings(page: SettingsPage? = null) {
        settingsOpen = true
        settingsPage = page
        render()
        window.scrollTo(0.0, 0.0)
    }

    fun openTrip(id: String) { tripId = id; render(); window.scrollTo(0.0, 0.0) }
    fun closeTrip() { tripId = null; render() }

    fun closeSettings() {
        settingsOpen = false
        settingsPage = null
        FixedForm.close()
        render()
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
        if (view == null || Onboarding.adding) {
            root.appendChild(Onboarding.screen())
            return
        }
        val format = Format(view.config)
        if (tripId != null) {
            root.appendChild(TripScreen.build(view, format, tripId!!))
        } else if (settingsOpen) {
            root.appendChild(SettingsScreen.build(view, format))
        } else {
            root.appendChild(when (tab) {
                Tab.ADD -> AddScreen.build(view, format)
                Tab.OVERVIEW -> OverviewScreen.build(view, format)
                Tab.HISTORY -> HistoryScreen.build(view, format)
                Tab.BALANCES -> BalancesScreen.build(view, format)
            })
            root.appendChild(tabBar())
        }
        window.scrollTo(0.0, scrollY)
    }

    private fun tabBar(): HTMLElement = el("nav", "tabbar") {
        attr("aria-label", "Fulla")
        div("tabbar-inner") {
            for (entry in Tab.entries) {
                child("button", if (entry == tab) "tab selected" else "tab") {
                    attr("type", "button")
                    if (entry == tab) attr("aria-current", "page")
                    ui(entry.icon, 22)
                    span("") { text(t(entry.label)) }
                    click { go(entry) }
                }
            }
        }
    }

    /** A short message at the bottom, with an optional way back. */
    fun toast(message: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
        document.getElementById("toast")?.let { it.parentNode?.removeChild(it) }
        val toast = el("div", if (settingsOpen) "toast no-tabs" else "toast") {
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
