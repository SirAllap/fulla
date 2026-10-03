// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.remote.Endpoint
import org.w3c.dom.HTMLElement

/**
 * Where the household lives. One that is only here can be shared through the
 * person's own Supabase project; a shared one shows who is signed in and when
 * it last synced. The same screen as the Android app's, in the same order.
 */
object SyncPage {
    private var url = ""
    private var key = ""
    private var email = ""
    private var password = ""
    private var create = false
    private var busy = false
    private var error: String? = null
    private var signedInAs: String? = null
    private var checked: String? = null

    fun build(parent: HTMLElement, view: HouseholdView) = parent.run {
        val connected = view.connected
        if (checked != view.id + connected + Ledger.needsSignIn) {
            checked = view.id + connected + Ledger.needsSignIn
            App.launch {
                if (connected) Remote.endpoint ?: Endpoint.parse(view.meta.url.orEmpty(), view.meta.anonKey.orEmpty())?.let { Remote.use(it) }
                signedInAs = Remote.session()?.email
                App.render()
            }
        }
        val signedIn = signedInAs != null && !Ledger.needsSignIn
        section(t("where_it_lives"), first = true)
        div("rows") {
            listRow(t(if (connected) "shared" else "on_this_phone"),
                context = if (!connected) t("local_only_explained") else view.meta.url?.removePrefix("https://"))
            if (signedIn) listRow(t("signed_in_as"), context = signedInAs)
            if (connected) {
                val status = when {
                    Ledger.syncing -> t("syncing")
                    view.meta.lastError != null -> view.meta.lastError
                    view.pendingCount > 0 -> t("sync_pending", view.pendingCount)
                    view.meta.lastSyncAt != null -> t("last_synced", syncedAt(view.meta.lastSyncAt!!))
                    else -> null
                }
                listRow(t("sync_now"), context = status, start = leadIcon("sync")) { App.launch { Ledger.sync() } }
            }
        }

        if (!connected || !signedIn) {
            section(t(if (connected) "sign_in" else "share_household"))
            if (!connected) note(t("share_household_text"))
            form(view, connected)
        }

        if (connected && signedIn) div("actions") {
            primaryButton(t("invite_someone")) { App.settingsPage = SettingsPage.MEMBERS; App.render() }
            secondaryButton(t("sign_out")) { App.launch { Remote.signOut(); signedInAs = null; checked = null; App.render() } }
        }
        if (signedIn) {
            section(t("your_account"))
            div("rows") {
                listRow(t("delete_account"), context = t("delete_account_text"), dim = false) {
                    confirmSheet(t("delete_account"), t("delete_account_confirm"), t("delete_account_yes"), danger = true) {
                        App.launch {
                            try {
                                Remote.api!!.accountDelete()
                                Remote.signOut()
                                for (h in Ledger.households.filter { it.mode == "connected" }) Ledger.switchTo(h.id).also { Ledger.forget() }
                                App.settingsPage = null
                            } catch (e: Throwable) { App.toast(Remote.message(e)) }
                        }
                    }
                }
            }
        }
        section(t("this_phone"))
        div("rows") {
            listRow(t("forget_household"), context = t("forget_household_text")) {
                confirmSheet(t("forget_household"), t(if (connected) "forget_shared_confirm" else "forget_local_confirm"), t("forget"), danger = true) {
                    App.launch { Ledger.forget(); App.settingsPage = null; App.settingsOpen = false; App.render() }
                }
            }
        }
    }

    private fun syncedAt(ms: Long): String {
        val d = js("new Date(ms)")
        return d.toLocaleTimeString(I18n.language, js("({ hour: '2-digit', minute: '2-digit' })")) as String
    }

    private fun HTMLElement.form(view: HouseholdView, connected: Boolean) {
        if (!connected) {
            val u = field(t("project_url"), url, "url") { attr("autocapitalize", "none"); attr("autocorrect", "off"); attr("inputmode", "url") }
            u.on("input") { url = u.value }
            val k = field(t("anon_key"), key) { attr("autocapitalize", "none"); attr("autocorrect", "off") }
            k.on("input") { key = k.value }
        }
        val e = field(t("email"), email, "email") { attr("autocapitalize", "none"); attr("autocomplete", "email") }
        e.on("input") { email = e.value.trim() }
        val p = field(t("password"), password, "password") { attr("autocomplete", if (create) "new-password" else "current-password") }
        p.on("input") { password = p.value }
        if (!connected) switchRow(t("create_account"), null, create) { create = it; App.render() }
        error?.let { child("p", "problem") { attr("role", "alert"); text(it) } }
        div("actions") {
            primaryButton(t(if (connected) "sign_in" else "share_household"), enabled = !busy) { submit(view, connected) }
        }
    }

    private fun submit(view: HouseholdView, connected: Boolean) {
        busy = true; error = null; App.render()
        App.launch {
            try {
                if (connected) {
                    if (!Ledger.signInAgain(email, password)) error = t("confirm_email")
                } else {
                    val endpoint = Endpoint.parse(url, key) ?: throw IllegalArgumentException(t("something_failed"))
                    if (!Ledger.share(endpoint, email, password, create)) error = t("confirm_email")
                }
                if (error == null) { password = ""; checked = null }
            } catch (e: IllegalArgumentException) {
                error = e.message
            } catch (e: Throwable) {
                error = Remote.message(e)
            } finally {
                busy = false
                App.render()
            }
        }
    }
}
