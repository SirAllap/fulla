// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/** The first thing a new browser shows: start here, bring a backup, or look around. */
object Onboarding {
    private var creating = false
    private var problem: String? = null

    fun screen(): HTMLElement = el("main", "screen welcome") {
        div("lang-row") { languagePicker() }
        div("welcome-mark") { icon("M12 3c-3 4-6 6.5-6 10a6 6 0 0 0 12 0c0-3.5-3-6-6-10z", 56, "icon mark") }
        child("h1", "title") { text("Fulla") }
        child("p", "lead") { text(t("welcome_line")) }
        child("p", "muted") { text(t("welcome_text")) }
        if (creating) appendChild(createForm()) else {
            div("stack") {
                button(t("get_started"), "btn primary wide") { creating = true; problem = null; App.render() }
                restoreButton()
                button(t("look_around"), "btn quiet wide") { App.launch { Ledger.createDemo() } }
                child("p", "muted small") { text(t("look_around_text")) }
            }
        }
        problem?.let { child("p", "problem") { attr("role", "alert"); text(it) } }
        child("p", "muted small foot") { text(t("web_local_only")) }
    }

    private fun HTMLElement.languagePicker() {
        val select = child("select", "select") {
            attr("aria-label", t("language"))
            for ((code, name) in I18n.languages) child("option") {
                attr("value", code); text(name)
                if (code == I18n.language) attr("selected", "selected")
            }
        } as HTMLSelectElement
        select.on("change") { App.setLanguage(select.value) }
    }

    private fun HTMLElement.restoreButton() {
        val input = input("file", cls = "hidden") {
            accept = ".json,application/json"
            id = "restore-file"
        }
        input.on("change") { readBackup(input) }
        button(t("backup_restore"), "btn secondary wide") { input.click() }
    }

    private fun createForm(): HTMLElement = el("form", "stack form") {
        attr("autocomplete", "off")
        val name = field(t("household_name"), t("default_household_name"))
        val you = field(t("your_name"), "")
        val currency = el("select", "select") {
            for (code in Format.currencies) child("option") {
                attr("value", code); text(code)
                if (code == Format.proposedCurrency()) attr("selected", "selected")
            }
        } as HTMLSelectElement
        div("field") { label(t("currency")); appendChild(currency); child("small", "muted") { text(t("currency_help")) } }
        button(t("create"), "btn primary wide") { submit(name, you, currency) }
        button(t("cancel"), "btn quiet wide") { creating = false; App.render() }
        on("submit") { it.preventDefault(); submit(name, you, currency) }
    }

    private fun submit(name: HTMLInputElement, you: HTMLInputElement, currency: HTMLSelectElement) {
        val householdName = name.value.trim().ifEmpty { t("default_household_name") }
        val displayName = you.value.trim()
        if (displayName.isEmpty()) { problem = t("your_name"); App.render(); return }
        creating = false
        problem = null
        App.launch { Ledger.createLocal(householdName, currency.value, I18n.language, displayName) }
    }

    private fun readBackup(input: HTMLInputElement) {
        val file = input.files?.item(0) ?: return
        App.launch {
            val text = readText(file)
            showResult(Ledger.restore(text))
        }
    }

    fun showResult(result: RestoreResult) {
        when (result) {
            RestoreResult.RESTORED -> { problem = null; App.toast(t("backup_restored")) }
            RestoreResult.ALREADY_HERE -> problem = t("backup_already_here")
            RestoreResult.NOT_A_BACKUP -> problem = t("backup_not_a_backup")
            RestoreResult.NEWER -> problem = t("backup_newer")
            RestoreResult.DAMAGED -> problem = t("backup_damaged")
            RestoreResult.CSV -> problem = t("backup_is_csv")
        }
        App.render()
    }
}

/** A labelled text field. */
fun HTMLElement.field(title: String, value: String = "", type: String = "text", build: HTMLInputElement.() -> Unit = {}): HTMLInputElement {
    var input: HTMLInputElement? = null
    div("field") {
        label(title)
        input = input(type, value, "input", build)
    }
    return input!!
}
