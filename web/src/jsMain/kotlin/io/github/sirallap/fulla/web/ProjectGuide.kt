// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.ProjectSetup
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

/**
 * A household's own Supabase project, from a browser. The Android app creates it with an access token; a browser page
 * is not allowed to call Supabase's management API (it answers no cross-site request), so here the person does it
 * once by hand, with every step explained and its page one tap away: an account, a project, Fulla's database (the
 * script is copied to the clipboard, and it lets accounts in without a confirmation email), then the project's ID and
 * public key, each with a link to the page that shows it, and the Fulla account. A build made for one project (the
 * Android build's) never shows any of this.
 */
object ProjectGuide {
    var url = ""
    var key = ""

    /** The project to use: the build's, or what the person typed. */
    fun current(): Endpoint? = Hosted.endpoint ?: parse(url, key)

    private val REF = Regex("[a-z0-9]{20}")

    /** The address as people find it: the project's own, its 20-letter code alone, or a dashboard page of it. */
    fun parse(rawUrl: String, anonKey: String): Endpoint? {
        val t = rawUrl.trim()
        val ref = if (REF.matches(t)) t else Regex("/project/([a-z0-9]{20})").find(t)?.groupValues?.get(1)
        return Endpoint.parse(if (ref != null) "https://$ref.supabase.co" else t, anonKey)
    }

    /** Kept so the forms ask for the account only once there is a project to sign in to. */
    fun needed(): Boolean = false

    fun build(parent: HTMLElement, onChange: () -> Unit) = parent.run {
        if (Hosted.endpoint != null) return@run
        note(t("setup_manual_intro"), "")
        step(1, t("setup_step_account"), t("setup_step_account_text"), t("setup_open")) { open(ProjectSetup.SIGN_UP_PAGE) }
        step(2, t("setup_manual_project"), t("setup_manual_project_text"), t("setup_open")) { open("https://supabase.com/dashboard/new") }
        div("setup-step") {
            span("t-title muted num") { text("3") }
            div("text") { span("title") { text(t("setup_manual_sql")) }; span("sub") { text(t("setup_manual_sql_text")) } }
            div("setup-buttons") {
                child("button", "text-btn") { attr("type", "button"); text(t("setup_copy_script")); click { copyScript() } }
                child("button", "text-btn") { attr("type", "button"); text(t("setup_open")); click { open(page("sql/new")) } }
            }
        }
        step(4, t("setup_manual_id"), t("setup_manual_id_text"), t("setup_open")) { open(page("settings/general")) }
        val u = field(t("project_id"), url, "url", help = t("project_id_help")) { attr("autocapitalize", "none"); attr("autocorrect", "off"); attr("inputmode", "url") }
        u.on("input") { url = u.value }
        step(5, t("setup_manual_key"), t("setup_manual_key_text"), t("setup_open")) { open(page("settings/api-keys")) }
        val k = field(t("public_key"), key) { attr("autocapitalize", "none"); attr("autocorrect", "off") }
        k.on("input") { key = k.value }
        step(6, t("setup_manual_account"), t("setup_manual_account_text"), null) {}
    }

    /** A page of the person's project in Supabase's dashboard: theirs once the ID is in, else Supabase asks which one. */
    private fun page(path: String): String {
        val ref = parse(url, "-")?.host?.removeSuffix(".supabase.co")
        return "https://supabase.com/dashboard/project/${ref ?: "_"}/$path"
    }

    private fun open(page: String) { window.open(page, "_blank", "noopener") }

    private fun HTMLElement.step(n: Int, title: String, body: String, action: String?, onAction: () -> Unit) {
        div("setup-step") {
            span("t-title muted num") { text("$n") }
            div("text") { span("title") { text(title) }; span("sub") { text(body) } }
            if (action != null) child("button", "text-btn") { attr("type", "button"); text(action); click { onAction() } }
        }
    }

    /** Fulla's database, every migration in one file, on the clipboard; safe to run again. */
    private fun copyScript() {
        App.launch {
            try {
                val sql = (window.fetch("setup.sql").await().text() as Promise<String>).await()
                val extra = (window.fetch("autoconfirm.sql").await().text() as Promise<String>).await()
                (window.navigator.asDynamic().clipboard.writeText(sql + "\n" + extra) as Promise<dynamic>).await()
                App.toast(t("setup_script_copied"))
            } catch (e: Throwable) {
                App.toast(t("something_failed"))
            }
        }
    }
}
