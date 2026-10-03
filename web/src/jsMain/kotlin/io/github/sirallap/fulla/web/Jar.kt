// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import org.w3c.dom.HTMLElement

/**
 * The overview's jar: income a light liquid floating at the top, spending a
 * heavy one settled at the bottom, and the empty space between them the
 * savings, to scale (core's Hero gives the fractions). Drawn as SVG, in the
 * money colours.
 */
object Jar {
    fun build(income: Double, spent: Double): HTMLElement = el("div", "jar") {
        val ns = "http://www.w3.org/2000/svg"
        val svg = kotlinx.browser.document.createElementNS(ns, "svg")
        svg.setAttribute("viewBox", "0 0 100 100")
        svg.setAttribute("role", "img")
        svg.setAttribute("aria-label", t("money_in") + " / " + t("money_out"))
        fun add(tag: String, attrs: Map<String, String>) {
            val e = kotlinx.browser.document.createElementNS(ns, tag)
            for ((k, v) in attrs) e.setAttribute(k, v)
            svg.appendChild(e)
        }
        val inFraction = income.coerceIn(0.0, 1.0)
        val outFraction = spent.coerceIn(0.0, 1.0)
        // The inside of the jar is a circle of radius 42 around (50, 50); liquid is clipped to it.
        val clip = kotlinx.browser.document.createElementNS(ns, "clipPath")
        clip.setAttribute("id", "jar-clip")
        val circle = kotlinx.browser.document.createElementNS(ns, "circle")
        circle.setAttribute("cx", "50"); circle.setAttribute("cy", "50"); circle.setAttribute("r", "42")
        clip.appendChild(circle)
        svg.appendChild(clip)
        val top = 8.0
        val height = 84.0
        val inHeight = height * inFraction
        val outHeight = height * outFraction
        add("g", mapOf("clip-path" to "url(#jar-clip)"))
        val g = svg.lastChild as org.w3c.dom.Element
        fun wave(y: Double, fill: String, up: Boolean) {
            val e = kotlinx.browser.document.createElementNS(ns, "path")
            val a = if (up) -2.0 else 2.0
            e.setAttribute("d", "M0,$y C20,${y - a} 35,${y + a} 50,$y S80,${y - a} 100,$y")
            e.setAttribute("fill", fill)
            g.appendChild(e)
        }
        fun rect(y: Double, h: Double, fill: String) {
            val e = kotlinx.browser.document.createElementNS(ns, "rect")
            e.setAttribute("x", "0"); e.setAttribute("y", y.toString()); e.setAttribute("width", "100"); e.setAttribute("height", h.toString()); e.setAttribute("fill", fill)
            g.appendChild(e)
        }
        // income at the top (light), spending at the bottom (heavy)
        if (inHeight > 0) { rect(top, inHeight, "var(--in-surface)"); wave(top + inHeight, "var(--in-surface)", true) }
        if (outHeight > 0) { rect(top + height - outHeight, outHeight, "var(--out-surface)"); wave(top + height - outHeight, "var(--out-surface)", false) }
        add("circle", mapOf("cx" to "50", "cy" to "50", "r" to "45", "fill" to "none", "stroke" to "var(--highlight)", "stroke-width" to "6"))
        appendChild(svg)
    }
}
