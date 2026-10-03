// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * On an iPhone, a web page in Safari's own tab can lose its data and cannot
 * work offline; added to the home screen it can. So until it is, say so once.
 */
object InstallHint {
    private const val KEY = "fulla.installHint"

    private fun onIos(): Boolean = Regex("iPhone|iPad|iPod").containsMatchIn(window.navigator.userAgent)

    private fun installed(): Boolean {
        val standalone = window.navigator.asDynamic().standalone == true
        val displayMode = window.matchMedia("(display-mode: standalone)").matches
        return standalone || displayMode
    }

    private fun dismissed(): Boolean = runCatching { localStorage.getItem(KEY) == "1" }.getOrDefault(false)

    fun card(): HTMLElement? {
        if (!onIos() || installed() || dismissed()) return null
        return el("div", "card hint") {
            attr("role", "note")
            child("h2", "card-title") { text(t("web_install_title")) }
            child("p") { text(t("web_install_text")) }
            button(t("done"), "btn secondary small") {
                runCatching { localStorage.setItem(KEY, "1") }
                App.render()
            }
        }
    }
}
