// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import org.w3c.dom.HTMLElement

/*
 * The pieces every screen is built from, named and sized as the Android app's
 * ui/components/Pieces.kt names and sizes them (TabHeader, Section, ListRow,
 * Chip, MemberBadge, PrimaryButton…). A screen that draws its own row is a
 * screen that drifts from the app.
 */

/** The top of a tab: its title, then the gear (always last, always in the same place). */
fun HTMLElement.tabHeader(title: String, vararg actions: HTMLElement) {
    div("header") {
        child("h1", "t-screen") { text(title) }
        for (a in actions) appendChild(a)
        appendChild(gearButton())
    }
}

/** The top of a screen reached from a tab: back, title. */
fun HTMLElement.backHeader(title: String, onBack: () -> Unit) {
    div("header back") {
        appendChild(iconButton("arrow_back", t("back"), onBack))
        child("h1", "") { text(title) }
    }
}

fun iconButton(name: String, label: String, cls: String = "", onClick: () -> Unit): HTMLElement = el("button", "icon-btn $cls") {
    attr("type", "button")
    attr("aria-label", label)
    ui(name)
    click(onClick)
}

fun iconButton(name: String, label: String, onClick: () -> Unit): HTMLElement = iconButton(name, label, "", onClick)

private fun gearButton(): HTMLElement = iconButton("settings", t("settings")) { App.openSettings() }

/** The name of a group of rows: small capitals with room above and below. */
fun HTMLElement.section(text: String, first: Boolean = false) {
    div(if (first) "section first" else "section") { span("t-section") { text(text) } }
}

/**
 * A row: a title, at most a line of context and a line of detail; [start] and [end] hold a badge, an amount, a chevron.
 * With [onClick] it is a button, with a hairline under it indented to the text.
 */
fun HTMLElement.listRow(
    title: String,
    context: String? = null,
    detail: String? = null,
    start: (HTMLElement.() -> Unit)? = null,
    end: (HTMLElement.() -> Unit)? = null,
    divider: Boolean = true,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
): HTMLElement = child("div", buildString {
    append("row")
    if (!divider) append(" flat")
    if (dim) append(" archived")
    if (onClick != null) append(" tap")
}) {
    if (onClick != null) {
        // A row is a button, but may hold buttons of its own (delete): a tap on one of those is theirs.
        attr("role", "button")
        attr("tabindex", "0")
        on("click") { e ->
            val inner = e.target.asDynamic().closest("button")
            if (inner == null || inner == undefined) onClick()
        }
        on("keydown") { e -> if (e.asDynamic().key == "Enter") onClick() }
    }
    start?.invoke(this)
    div("text") {
        span("title") { text(title) }
        if (!context.isNullOrBlank()) span("sub") { text(context) }
        if (!detail.isNullOrBlank()) span("sub") { text(detail) }
    }
    end?.invoke(this)
}

fun HTMLElement.chevron() { span("chev") { ui("chevron_right") } }

fun HTMLElement.amountText(value: String, tone: String = "") { span("amount $tone") { text(value) } }

/** A chip: a check when it is on, never colour alone. */
fun HTMLElement.chip(label: String, selected: Boolean, onClick: () -> Unit): HTMLElement = child("button", if (selected) "chip selected" else "chip") {
    attr("type", "button")
    attr("aria-pressed", selected.toString())
    if (selected) icon(MaterialPaths.ui.getValue("check"), 18)
    text(label)
    click(onClick)
}

fun HTMLElement.chipRow(wrap: Boolean = false, build: HTMLElement.() -> Unit) = div(if (wrap) "chips wrap" else "chips", build)

/** A person: their colour and their initials, never the colour alone. */
fun HTMLElement.badge(initials: String, colorIndex: Int, size: Int = 32): HTMLElement = span("badge") {
    style.setProperty("background", "var(--member-${colorIndex.mod(10)})")
    if (size != 32) { style.width = "${size}px"; style.height = "${size}px" }
    text(initials.take(2))
}

fun HTMLElement.primaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit): HTMLElement = child("button", "btn primary") {
    attr("type", "button")
    if (!enabled) attr("disabled", "")
    text(label)
    click(onClick)
}

fun HTMLElement.secondaryButton(label: String, onClick: () -> Unit): HTMLElement = child("button", "btn secondary") {
    attr("type", "button"); text(label); click(onClick)
}

fun HTMLElement.quietButton(label: String, onClick: () -> Unit): HTMLElement = child("button", "btn quiet") {
    attr("type", "button"); text(label); click(onClick)
}

fun HTMLElement.dangerButton(label: String, onClick: () -> Unit): HTMLElement = child("button", "btn danger-line") {
    attr("type", "button"); text(label); click(onClick)
}

/** A switch with its title and a line under it. */
fun HTMLElement.switchRow(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    child("label", "row") {
        div("text") { span("title") { text(title) }; if (sub != null) span("sub") { text(sub) } }
        val box = input("checkbox", "", "switch") { checked = value }
        box.on("change") { onChange(box.checked) }
    }
}

/** A labelled field. */
fun HTMLElement.labelled(title: String, help: String? = null, build: HTMLElement.() -> Unit) {
    div("field") {
        span("label") { text(title) }
        build()
        if (help != null) child("small") { text(help) }
    }
}

/** Something that rises from the bottom: the details of an entry, an invite. */
fun sheet(title: String?, build: HTMLElement.(close: () -> Unit) -> Unit) {
    lateinit var scrim: HTMLElement
    val close: () -> Unit = { scrim.parentNode?.removeChild(scrim); Unit }
    scrim = el("div", "scrim") {
        on("click") { e -> if (e.target === this) close() }
        div("sheet") {
            attr("role", "dialog")
            div("grab")
            if (title != null) child("h2", "t-title") { text(title) }
            build(close)
        }
    }
    kotlinx.browser.document.body?.appendChild(scrim)
}

/** An icon, a title and a line: what a screen says when there is nothing to show. */
fun HTMLElement.emptyState(iconName: String, title: String, body: String) {
    div("empty") {
        span("muted") { ui(iconName, 48) }
        child("h2", "t-title") { text(title) }
        child("p", "muted") { text(body) }
    }
}

/** The icon at the start of a row, in the muted ink of the Android app's ListRow. */
fun leadIcon(name: String): HTMLElement.() -> Unit = { span("lead") { ui(name) } }

/** A labelled text field. */
fun HTMLElement.field(title: String, value: String = "", type: String = "text", help: String? = null, build: org.w3c.dom.HTMLInputElement.() -> Unit = {}): org.w3c.dom.HTMLInputElement {
    var input: org.w3c.dom.HTMLInputElement? = null
    div("field") {
        label(title)
        input = input(type, value, "input", build)
        if (help != null) child("small") { text(help) }
    }
    return input!!
}

/** A labelled choice from a list of (value, label). */
fun HTMLElement.selectField(title: String?, options: List<Pair<String, String>>, selected: String?, help: String? = null, onChange: (String) -> Unit): org.w3c.dom.HTMLSelectElement {
    var select: org.w3c.dom.HTMLSelectElement? = null
    div("field") {
        if (title != null) label(title)
        select = child("select", "select") {
            if (title != null) attr("aria-label", title)
            for ((value, name) in options) child("option") {
                attr("value", value); text(name)
                if (value == selected) attr("selected", "selected")
            }
        } as org.w3c.dom.HTMLSelectElement
        if (help != null) child("small") { text(help) }
    }
    select!!.on("change") { onChange(select!!.value) }
    return select!!
}

/** Asks before doing something that cannot be undone with a tap. */
fun confirmSheet(title: String?, message: String, confirmLabel: String, danger: Boolean = false, onConfirm: () -> Unit) {
    sheet(title) { close ->
        child("p", "pad") { text(message) }
        div("actions") {
            if (danger) dangerButton(confirmLabel) { close(); onConfirm() } else primaryButton(confirmLabel) { close(); onConfirm() }
            quietButton(t("cancel")) { close() }
        }
    }
}

/** One text value to change: the Android app's EditDialog. */
fun promptSheet(title: String, label: String, initial: String, type: String = "text", inputMode: String? = null, onSave: (String) -> Unit) {
    sheet(title) { close ->
        val input = field(label, initial, type) { if (inputMode != null) attr("inputmode", inputMode) }
        div("actions") {
            primaryButton(t("save")) { val v = input.value.trim(); close(); onSave(v) }
            quietButton(t("cancel")) { close() }
        }
        input.focus()
    }
}

/** A plain paragraph in the screen's inset. */
fun HTMLElement.note(body: String, cls: String = "muted") { child("p", "$cls pad") { text(body) } }

/** A square of the category grid: its icon in its colour, or a check when it is picked. */
fun HTMLElement.tile(label: String, iconName: String, tint: String, selected: Boolean, uiIcon: String? = null, onClick: () -> Unit) {
    child("button", if (selected) "tile selected" else "tile") {
        attr("type", "button")
        attr("aria-pressed", selected.toString())
        style.setProperty("--tint", tint)
        val glyph = if (selected) icon(MaterialPaths.ui.getValue("check"), 24) else if (uiIcon != null) icon(MaterialPaths.ui.getValue(uiIcon), 24) else icon(MaterialPaths.category[iconName] ?: MaterialPaths.category.getValue("label"), 24)
        if (!selected) glyph.setAttribute("style", "color:$tint")
        span("label") { text(label) }
        click(onClick)
    }
}

