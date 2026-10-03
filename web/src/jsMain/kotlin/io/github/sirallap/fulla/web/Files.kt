// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import kotlinx.browser.document
import kotlinx.coroutines.await
import org.w3c.dom.HTMLAnchorElement
import org.w3c.files.File
import kotlin.js.Promise

/** The text of a file the person chose. */
suspend fun readText(file: File): String = (file.asDynamic().text() as Promise<String>).await()

/**
 * Hands a file to the person: through the phone's share sheet where there is
 * one (on an iPhone: Files, iCloud, AirDrop), else as a download.
 */
suspend fun shareOrDownload(name: String, mime: String, content: String): Boolean {
    val file = js("new File([content], name, { type: mime })")
    val nav = js("navigator")
    val data = js("({ files: [file] })")
    val canShare = nav.canShare != undefined && nav.share != undefined && (nav.canShare(data) as Boolean)
    if (canShare) {
        try {
            (nav.share(data) as Promise<dynamic>).await()
            return true
        } catch (e: Throwable) {
            // Closed without choosing: not an error, and nothing was saved.
            if ((e.asDynamic().name as? String) == "AbortError") return false
        }
    }
    val url = js("URL.createObjectURL(file)") as String
    val link = document.createElement("a") as HTMLAnchorElement
    link.href = url
    link.download = name
    document.body?.appendChild(link)
    link.click()
    document.body?.removeChild(link)
    js("setTimeout(function () { URL.revokeObjectURL(url) }, 10000)")
    return true
}
