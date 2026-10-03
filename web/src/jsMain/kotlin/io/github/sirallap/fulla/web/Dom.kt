// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

/*
 * A very small way to build the page: elements are made with createElement and
 * text with createTextNode, never from a string of HTML, so nothing a person
 * types can become markup.
 */

typealias Build = HTMLElement.() -> Unit

fun el(tag: String, cls: String = "", build: Build = {}): HTMLElement {
    val e = document.createElement(tag) as HTMLElement
    if (cls.isNotEmpty()) e.className = cls
    e.build()
    return e
}

fun HTMLElement.child(tag: String, cls: String = "", build: Build = {}): HTMLElement =
    el(tag, cls, build).also { appendChild(it) }

fun HTMLElement.div(cls: String = "", build: Build = {}) = child("div", cls, build)
fun HTMLElement.span(cls: String = "", build: Build = {}) = child("span", cls, build)

fun HTMLElement.text(value: String) {
    appendChild(document.createTextNode(value))
}

fun HTMLElement.label(value: String) = child("span", "label") { text(value) }

fun HTMLElement.on(event: String, handler: (Event) -> Unit) {
    addEventListener(event, handler)
}

fun HTMLElement.attr(name: String, value: String) = setAttribute(name, value)

fun HTMLElement.click(handler: () -> Unit) = on("click") { handler() }

fun Element.clear() {
    while (firstChild != null) removeChild(firstChild!!)
}

/** A tappable thing: a real button, so a keyboard and a screen reader find it. */
fun HTMLElement.button(label: String, cls: String = "btn", onClick: () -> Unit): HTMLElement = child("button", cls) {
    attr("type", "button")
    text(label)
    click(onClick)
}

fun HTMLElement.input(type: String, value: String = "", cls: String = "", build: HTMLInputElement.() -> Unit = {}): HTMLInputElement {
    val i = document.createElement("input") as HTMLInputElement
    i.type = type
    i.value = value
    if (cls.isNotEmpty()) i.className = cls
    i.build()
    appendChild(i)
    return i
}

/** Inline SVG from a path, in the page's current colour. */
fun HTMLElement.icon(path: String, size: Int = 24, cls: String = "icon"): Element {
    val ns = "http://www.w3.org/2000/svg"
    val svg = document.createElementNS(ns, "svg")
    svg.setAttribute("viewBox", "0 0 24 24")
    svg.setAttribute("width", size.toString())
    svg.setAttribute("height", size.toString())
    svg.setAttribute("aria-hidden", "true")
    svg.setAttribute("class", cls)
    val p = document.createElementNS(ns, "path")
    p.setAttribute("d", path)
    p.setAttribute("fill", "none")
    p.setAttribute("stroke", "currentColor")
    p.setAttribute("stroke-width", "2")
    p.setAttribute("stroke-linecap", "round")
    p.setAttribute("stroke-linejoin", "round")
    svg.appendChild(p)
    appendChild(svg)
    return svg
}
