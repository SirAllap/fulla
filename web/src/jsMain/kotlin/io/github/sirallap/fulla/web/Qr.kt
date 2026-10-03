// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import org.w3c.dom.HTMLElement

@JsModule("qrcode-generator")
@JsNonModule
external fun qrcode(typeNumber: Int, errorCorrectionLevel: String): dynamic

/** An invite as a QR code, drawn module by module as an SVG, so it stays sharp at any size. Medium error correction. */
fun HTMLElement.qrCode(text: String, label: String): HTMLElement = div("qr") {
    attr("role", "img"); attr("aria-label", label)
    val qr = qrcode(0, "M")
    qr.addData(text, "Byte")
    qr.make()
    val n = qr.getModuleCount() as Int
    val ns = "http://www.w3.org/2000/svg"
    val svg = kotlinx.browser.document.createElementNS(ns, "svg")
    svg.setAttribute("viewBox", "0 0 $n $n"); svg.setAttribute("shape-rendering", "crispEdges")
    val path = StringBuilder()
    for (y in 0 until n) for (x in 0 until n) if (qr.isDark(y, x) as Boolean) path.append("M$x,${y}h1v1h-1z")
    val p = kotlinx.browser.document.createElementNS(ns, "path")
    p.setAttribute("d", path.toString()); p.setAttribute("fill", "currentColor")
    svg.appendChild(p)
    appendChild(svg)
}
