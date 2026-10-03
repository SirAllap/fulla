// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.InviteLink
import io.github.sirallap.fulla.core.money.Currency
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/**
 * The first thing a new browser shows, laid out as the Android app's welcome: the mark, the name and a line on the
 * left, the buttons at the bottom. Start here, join with an invite, bring a backup, or look around.
 */
object Onboarding {
    private enum class Step { WELCOME, CREATE, SHARED, JOIN }

    private var step = Step.WELCOME
    private var more = false
    private var problem: String? = null
    private var busy = false

    /** Adding another household to a browser that already holds one. */
    var adding = false

    private var householdName = ""
    private var yourName = ""
    private var currency = ""
    private var url = ""
    private var key = ""
    private var email = ""
    private var password = ""
    private var createAccount = false
    private var code = ""
    private var linked: InviteLink? = null

    init {
        // An invite opened from a link: #join=<the fulla://join link>.
        val hash = window.location.hash
        if (hash.startsWith("#join=")) {
            val link = InviteLink.parse(js("decodeURIComponent")(hash.removePrefix("#join=")) as String)
            if (link != null) { linked = link; code = link.code; url = link.endpoint.url; key = link.endpoint.anonKey; step = Step.JOIN; adding = true }
        }
    }

    fun screen(): HTMLElement {
        if (householdName.isEmpty()) householdName = t("default_household_name")
        if (currency.isEmpty()) currency = Format.proposedCurrency()
        return when (step) {
            Step.WELCOME -> welcome()
            Step.CREATE -> form(t("get_started")) { createFields() }
            Step.SHARED -> form(t("shared")) { projectFields(); accountFields(); createFields() }
            Step.JOIN -> form(t("join_household")) { joinFields() }
        }
    }

    private fun goto(next: Step) { step = next; problem = null; App.render(); window.scrollTo(0.0, 0.0) }

    private fun leave() { step = Step.WELCOME; adding = false; problem = null; linked = null; window.history.replaceState(null, "", window.location.pathname) }

    // ── the welcome ──────────────────────────────────────────────────────────

    private fun welcome(): HTMLElement = el("main", "screen no-tabs welcome") {
        if (adding) {
            div("lang") { appendChild(iconButton("arrow_back", t("back")) { leave(); App.render() }) }
        } else div("lang") { languagePicker() }
        div("grow")
        span("mark") { icon("M12 2.5c-3.4 4.6-7 7.6-7 12a7 7 0 0 0 14 0c0-4.4-3.6-7.4-7-12z", 88, "icon mark-icon") }
        child("h1", "name") { text("Fulla") }
        child("p", "lead") { text(t("welcome_line")) }
        child("p", "copy") { text(t("welcome_text")) }
        div("grow")
        div("buttons") {
            primaryButton(t("get_started")) { goto(Step.CREATE) }
            secondaryButton(t("have_invite")) { goto(Step.JOIN) }
            problem?.let { child("p", "problem flush") { attr("role", "alert"); text(it) } }
            quietButton(t(if (more) "fewer_options" else "more_options")) { more = !more; App.render() }
            if (more) div("rows") {
                listRow(t("shared"), detail = t("shared_text"), start = leadIcon("cloud")) { goto(Step.SHARED) }
                val picker = input("file", cls = "hidden") { accept = ".json,application/json"; id = "restore-file" }
                picker.on("change") { readBackup(picker) }
                listRow(t("backup_restore"), detail = t("backup_restore_welcome"), start = leadIcon("restore")) { picker.click() }
                listRow(t("look_around"), detail = t("look_around_text"), start = leadIcon("visibility"), divider = false) {
                    App.launch { Ledger.createDemo(); adding = false }
                }
            }
            child("p", "muted flush center") { text(t("web_local_only")) }
        }
    }

    private fun HTMLElement.languagePicker() {
        val select = child("select", "select") {
            attr("aria-label", t("language"))
            for ((c, name) in I18n.languages) child("option") {
                attr("value", c); text(name)
                if (c == I18n.language) attr("selected", "selected")
            }
        } as HTMLSelectElement
        select.on("change") { App.setLanguage(select.value) }
    }

    private fun readBackup(input: HTMLInputElement) {
        val file = input.files?.item(0) ?: return
        App.launch { showResult(Ledger.restore(readText(file))) }
    }

    fun showResult(result: RestoreResult) {
        when (result) {
            RestoreResult.RESTORED -> { problem = null; adding = false; App.toast(t("backup_restored")) }
            RestoreResult.ALREADY_HERE -> problem = t("backup_already_here")
            RestoreResult.NOT_A_BACKUP -> problem = t("backup_not_a_backup")
            RestoreResult.NEWER -> problem = t("backup_newer")
            RestoreResult.DAMAGED -> problem = t("backup_damaged")
            RestoreResult.CSV -> problem = t("backup_is_csv")
        }
        App.render()
    }

    // ── a form: back, title, fields, one action at the bottom ────────────────

    private fun form(title: String, fields: HTMLElement.() -> Unit): HTMLElement = el("main", "screen no-tabs form-screen") {
        backHeader(title) { step = Step.WELCOME; problem = null; App.render() }
        div("body") {
            fields()
            problem?.let { child("p", "problem") { attr("role", "alert"); text(it) } }
        }
        div("actionbar no-tabs") {
            div("actions") {
                primaryButton(t(when (step) { Step.SHARED -> "connect"; Step.JOIN -> "join_household"; else -> "create" }), enabled = !busy) { submit() }
            }
        }
    }

    private fun HTMLElement.createFields() {
        val name = field(t("household_name"), householdName) { attr("maxlength", "60") }
        name.on("input") { householdName = name.value }
        val you = field(t("your_name"), yourName) { attr("maxlength", "40"); attr("autocomplete", "name") }
        you.on("input") { yourName = you.value }
        selectField(t("currency"), Format.currencies.map { it to it }, currency, help = t("currency_help")) { currency = it }
    }

    private fun HTMLElement.projectFields() {
        val u = field(t("project_url"), url, "url") { attr("autocapitalize", "none"); attr("autocorrect", "off"); attr("inputmode", "url") }
        u.on("input") { url = u.value }
        val k = field(t("anon_key"), key) { attr("autocapitalize", "none"); attr("autocorrect", "off") }
        k.on("input") { key = k.value }
    }

    private fun HTMLElement.accountFields() {
        val e = field(t("email"), email, "email") { attr("autocapitalize", "none"); attr("autocomplete", "email") }
        e.on("input") { email = e.value.trim() }
        val p = field(t("password"), password, "password") { attr("autocomplete", if (createAccount) "new-password" else "current-password") }
        p.on("input") { password = p.value }
        switchRow(t("create_account"), null, createAccount) { createAccount = it; App.render() }
    }

    private fun HTMLElement.joinFields() {
        val c = field(t("invite_code"), if (linked != null) linked!!.code else code, help = t("invite_link_or_code")) { attr("autocapitalize", "characters"); attr("autocorrect", "off") }
        c.on("input") {
            val text = c.value.trim()
            val link = InviteLink.parse(text)
            if (link != null) { linked = link; code = link.code; url = link.endpoint.url; key = link.endpoint.anonKey; App.render() }
            else { linked = null; code = text.uppercase().take(20) }
        }
        if (linked == null) projectFields()
        else child("p", "muted pad") { text(t("invite_confirm_host", linked!!.endpoint.host)) }
        val you = field(t("your_name"), yourName) { attr("maxlength", "40"); attr("autocomplete", "name") }
        you.on("input") { yourName = you.value }
        accountFields()
    }

    private fun submit() {
        if (yourName.isBlank()) { problem = t("your_name"); App.render(); return }
        val currencyOk = Currency.of(currency.trim().uppercase()) != null
        if (step != Step.JOIN && (householdName.isBlank() || !currencyOk)) { problem = t("household_name"); App.render(); return }
        val endpoint = if (step == Step.CREATE) null else Endpoint.parse(url, key)
        if (step != Step.CREATE && endpoint == null) { problem = t("url_not_supabase"); App.render(); return }
        busy = true; problem = null; App.render()
        App.launch {
            try {
                when (step) {
                    Step.CREATE -> Ledger.createLocal(householdName.trim(), currency.trim().uppercase(), I18n.language, yourName.trim())
                    Step.SHARED -> {
                        Ledger.createLocal(householdName.trim(), currency.trim().uppercase(), I18n.language, yourName.trim())
                        val shared = try { Ledger.share(endpoint!!, email, password, createAccount) } catch (e: Throwable) { Ledger.forget(); throw e }
                        if (!shared) { Ledger.forget(); problem = t("confirm_email") }
                    }
                    Step.JOIN -> if (!Ledger.join(endpoint!!, code.trim(), email, password, createAccount, yourName.trim())) problem = t("confirm_email")
                    Step.WELCOME -> Unit
                }
                if (problem == null) { password = ""; adding = false; step = Step.WELCOME; linked = null }
            } catch (e: Throwable) {
                problem = Remote.message(e)
            } finally {
                busy = false
                App.render()
            }
        }
    }
}
