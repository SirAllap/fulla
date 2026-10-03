// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.ProjectSetup
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

/**
 * The household's own Supabase project, set up from here exactly as the Android app does it: an account on
 * supabase.com, a personal access token pasted once, and Fulla creates the project, installs itself and connects.
 * Somebody who already has a project is one tap away ("I already have a project"). The token is used for these calls
 * only and never kept. A build made for one project (the Android build's) never shows any of this.
 */
object ProjectGuide {
    /** The project to use, once there is one: set up here, or typed by a person who already has one. */
    var endpoint: Endpoint? = null
    var manual = false
    private var token = ""
    private var busy = false
    private var reached: ProjectSetup.Step? = null
    private var error: String? = null
    private var choices: List<ProjectSetup.Project>? = null
    private var justDone = false
    private var detail: String? = null
    var url = ""
    var key = ""

    /** The endpoint a form should use: the build's, the one set up here, or what was typed. */
    fun current(): Endpoint? = Hosted.endpoint ?: endpoint ?: if (manual) Endpoint.parse(url, key) else null

    /** Whether the person still has to choose a project before anything else is asked. */
    fun needed(): Boolean = Hosted.endpoint == null && endpoint == null && !manual

    /** The guide itself, or the project's address and key when the person has one; nothing when the build has a project. */
    fun build(parent: HTMLElement, onChange: () -> Unit) = parent.run {
        if (Hosted.endpoint != null) return@run
        if (endpoint != null) {
            if (justDone) {
                note(t("setup_token_delete"))
                child("button", "text-btn") { attr("type", "button"); text(t("setup_token_delete_link")); click { window.open("https://supabase.com/dashboard/account/tokens", "_blank", "noopener") } }
            }
            return@run
        }
        if (manual) {
            note(t("setup_manual_help"))
            val u = field(t("project_url"), url, "url") { attr("autocapitalize", "none"); attr("autocorrect", "off"); attr("inputmode", "url") }
            u.on("input") { url = u.value }
            val k = field(t("anon_key"), key, help = t("anon_key_help")) { attr("autocapitalize", "none"); attr("autocorrect", "off") }
            k.on("input") { key = k.value }
            child("button", "text-btn") { attr("type", "button"); text(t("setup_back_to_guide")); click { manual = false; onChange() } }
            return@run
        }
        note(t("setup_intro"), "")
        step(1, t("setup_step_account"), t("setup_step_account_text"), t("setup_open")) { window.open(ProjectSetup.SIGN_UP_PAGE, "_blank", "noopener") }
        step(2, t("setup_step_token"), t("setup_step_token_text"), t("setup_open")) { window.open(ProjectSetup.TOKEN_PAGE, "_blank", "noopener") }
        step(3, t("setup_step_paste"), t("setup_step_paste_text"), null) {}
        val tk = field(t("setup_token"), token, "password") { attr("placeholder", "sbp_…"); attr("autocapitalize", "none"); attr("autocorrect", "off"); attr("autocomplete", "off"); if (busy) attr("disabled", "") }
        tk.on("input") { token = tk.value.trim(); refresh() }
        if (busy || reached != null) {
            val order = ProjectSetup.Step.entries
            val labels = listOf("setup_creating", "setup_starting", "setup_installing", "setup_configuring")
            div("setup-progress") {
                for ((i, label) in labels.withIndex()) {
                    val done = reached != null && order.indexOf(reached!!) > i
                    val now = reached != null && order.indexOf(reached!!) == i
                    child("p", if (done || now) "t-secondary" else "t-secondary muted") { text((if (done) "✓  " else if (now) "…  " else "   ") + t(label)) }
                }
            }
        }
        choices?.let { list ->
            note(t("setup_choose_project"), "")
            note(t("setup_choose_note"))
            div("rows") {
                for (p in list) listRow(p.name, context = listOfNotNull(p.region, t("setup_paused").takeIf { p.paused }).joinToString(" · ").ifEmpty { null },
                    onClick = if (busy) null else ({ run(onChange) { setup, sql -> setup.connect(p.ref, sql, ownProject = false) { reached = it; onChange() } } }))
            }
        }
        error?.let { child("p", "problem") { attr("role", "alert"); text(it) }; detail?.let { d -> child("p", "muted pad detail") { text(d) } } }
        actionButton = null
        div("actions") {
            actionButton = primaryButton(t(if (choices == null) "setup_action" else "setup_create_new"), enabled = token.length >= 20 && !busy) {
                if (choices != null) run(onChange) { setup, sql -> createNew(setup, sql, onChange) }
                else run(onChange) { setup, sql ->
                    // Fulla's own project from before is picked up by itself; with none and no others, one is created; otherwise the person chooses.
                    val projects = setup.projects()
                    val own = projects.firstOrNull { it.name == ProjectSetup.PROJECT_NAME }
                    when {
                        own != null -> setup.connect(own.ref, sql, ownProject = true) { reached = it; onChange() }
                        projects.isEmpty() -> createNew(setup, sql, onChange)
                        else -> { choices = projects; null }
                    }
                }
            }
            quietButton(t("setup_manual")) { manual = true; onChange() }
        }
    }

    private var actionButton: HTMLElement? = null
    private fun refresh() { actionButton?.let { if (token.length >= 20 && !busy) it.removeAttribute("disabled") else it.setAttribute("disabled", "") } }

    private fun HTMLElement.step(n: Int, title: String, body: String, action: String?, onAction: () -> Unit) {
        div("setup-step") {
            span("t-title muted num") { text("$n") }
            div("text") { span("title") { text(title) }; span("sub") { text(body) } }
            if (action != null) child("button", "text-btn") { attr("type", "button"); text(action); click { onAction() } }
        }
    }

    private suspend fun createNew(setup: ProjectSetup, sql: String, onChange: () -> Unit): Endpoint {
        val organization = setup.organizations().firstOrNull() ?: throw FullaError(ProjectSetup.SETUP_FAILED, t("setup_no_organization"))
        val zone = js("Intl.DateTimeFormat().resolvedOptions().timeZone") as String
        return setup.run(organization.slug, sql, ProjectSetup.regionFor(zone)) { reached = it; onChange() }
    }

    /** Runs one setup call with the token, and connects when it hands back an endpoint (null: a choice to make first). */
    private fun run(onChange: () -> Unit, work: suspend (ProjectSetup, String) -> Endpoint?) {
        busy = true; error = null; detail = null; onChange()
        App.launch {
            try {
                val sql = (window.fetch("setup.sql").await().text() as Promise<String>).await()
                val found = work(Remote.setup(token.trim()), sql)
                if (found != null) { token = ""; endpoint = found; justDone = true }
            } catch (e: Throwable) {
                console.error("Fulla: setup failed: " + e.stackTraceToString())
                val code = (e as? FullaError)?.code
                // What Supabase (or the page) really said, for the person who has to tell somebody about it.
                detail = listOfNotNull(code, (e as? FullaError)?.status?.takeIf { it > 0 }?.toString(), e.message?.take(160)).joinToString(" · ")
                error = when (code) {
                    ProjectSetup.PROJECT_PAUSED -> t("setup_project_paused")
                    ProjectSetup.TOKEN_REFUSED -> t("setup_token_refused")
                    ProjectSetup.PROJECT_LIMIT -> t("setup_project_limit")
                    ProjectSetup.SETUP_SLOW -> t("setup_slow")
                    FullaError.NETWORK -> t("setup_browser_blocked")
                    else -> if (code == ProjectSetup.SETUP_FAILED && e.message == t("setup_no_organization")) t("setup_no_organization") else t("setup_failed")
                }
            }
            busy = false
            onChange()
        }
    }
}
