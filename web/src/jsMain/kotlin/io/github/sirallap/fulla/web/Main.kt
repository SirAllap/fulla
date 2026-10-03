// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/** Every coroutine of the page. A failure is written to the console with its trace, never swallowed. */
val scope = MainScope() + CoroutineExceptionHandler { _, e -> console.error("Fulla: " + e.stackTraceToString()) }

fun main() {
    Theme.install()
    I18n.language = I18n.forTag(runCatching { localStorage.getItem("fulla.lang") }.getOrNull() ?: Format.browserTag())
    document.documentElement?.setAttribute("lang", I18n.language)
    val root = document.getElementById("app") as HTMLElement
    App.start(root)
    registerServiceWorker()
    scope.launch {
        Ledger.load()
        App.render()
        // Ask the browser to keep this storage: the household may be the only copy there is.
        Idb.persist()
        Ledger.generateRecurring()
    }
    // Coming back to the page (a phone left in a pocket all day): look at the day again.
    document.addEventListener("visibilitychange", {
        if (document.asDynamic().visibilityState == "visible") scope.launch { Ledger.generateRecurring(); App.render() }
    })
    window.setInterval({ scope.launch { Ledger.generateRecurring() } }, 60 * 60 * 1000)
}

private fun registerServiceWorker() {
    val sw = window.navigator.asDynamic().serviceWorker
    if (sw != null && sw != undefined) {
        window.addEventListener("load", { sw.register("sw.js") })
    }
}
