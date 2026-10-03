// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement

/** The sections of Settings, in the order and with the icons of the Android app. */
enum class SettingsPage(val title: String, val icon: String) {
    HOUSEHOLDS("settings_households", "swap_horiz"),
    HOUSEHOLD("settings_household", "home"),
    MEMBERS("settings_members", "group"),
    CATEGORIES("settings_categories", "category"),
    ACCOUNTS("settings_accounts", "account_balance"),
    BUDGETS("settings_budgets", "pie_chart"),
    RECURRING("settings_recurring", "event"),
    BACKUP("settings_backup", "save_alt"),
    APPEARANCE("settings_appearance", "palette"),
    SYNC("settings_sync", "cloud"),
    ABOUT("settings_about", "info"),
}

/** Settings, and nothing but settings: each section is a screen of its own, reached from the list. */
object SettingsScreen {
    private const val WEEK_IN_MS = 7 * 24 * 60 * 60 * 1000L

    fun build(view: HouseholdView, format: Format): HTMLElement = el("main", "screen no-tabs") {
        val page = App.settingsPage
        backHeader(t(page?.title ?: "settings")) {
            if (page == null) App.closeSettings() else { App.settingsPage = null; FixedForm.close(); App.render() }
        }
        val canEdit = view.config.me()?.let { io.github.sirallap.fulla.core.roles.Permissions.canEditStructure(it) } ?: true
        when (page) {
            null -> index(view)
            SettingsPage.HOUSEHOLDS -> HouseholdsPage.build(this, view)
            SettingsPage.HOUSEHOLD -> HouseholdPage.build(this, view, format, canEdit)
            SettingsPage.MEMBERS -> MembersPage.build(this, view)
            SettingsPage.CATEGORIES -> CategoriesPage.build(this, view, canEdit)
            SettingsPage.ACCOUNTS -> AccountsPage.build(this, view, format, canEdit)
            SettingsPage.BUDGETS -> BudgetsPage.build(this, view, format, canEdit)
            SettingsPage.RECURRING -> FixedForm.page(this, view, format, canEdit)
            SettingsPage.BACKUP -> backup(view, format)
            SettingsPage.APPEARANCE -> appearance()
            SettingsPage.SYNC -> SyncPage.build(this, view)
            SettingsPage.ABOUT -> about()
        }
    }

    private fun backupDue(): Boolean {
        val last = Ledger.lastBackupAt ?: return true
        return io.github.sirallap.fulla.core.time.Instant.now().toEpochMilli() - last > 4 * WEEK_IN_MS
    }

    private fun HTMLElement.index(view: HouseholdView) {
        InstallHint.card()?.let { appendChild(it) }
        div("rows") {
            for (p in SettingsPage.entries) {
                // A phone-only household has nobody to share with yet: the same sections, the same order.
                listRow(t(p.title), start = { span("lead") { ui(p.icon) } },
                    end = {
                        if (p == SettingsPage.BACKUP && backupDue()) span("dot") { attr("aria-hidden", "true") }
                        chevron()
                    }) { App.settingsPage = p; App.render(); window.scrollTo(0.0, 0.0) }
            }
        }
        if (!Idb.available) child("p", "problem") { text(t("web_storage_missing")) }
    }

    private fun HTMLElement.appearance() {
        section(t("language"), first = true)
        div("field") {
            val select = child("select", "select") {
                attr("aria-label", t("language"))
                for ((code, name) in I18n.languages) child("option") {
                    attr("value", code); text(name)
                    if (code == I18n.language) attr("selected", "selected")
                }
            } as HTMLSelectElement
            select.on("change") { App.setLanguage(select.value) }
        }
    }

    private fun HTMLElement.backup(view: HouseholdView, format: Format) {
        child("p", "muted pad") { text(t("web_backup_text")) }
        if (backupDue()) child("p", "warn pad") { text(t("web_backup_due_text")) }
        div("actions") {
            primaryButton(t("backup_save")) {
                App.launch {
                    val name = "fulla-${view.config.household.name.filter { it.isLetterOrDigit() }.lowercase()}-${io.github.sirallap.fulla.core.time.LocalDate.now()}.json"
                    if (shareOrDownload(name, "application/json", Ledger.backupText())) {
                        Ledger.noteBackupSaved()
                        App.toast(t("backup_saved"))
                    }
                }
            }
            secondaryButton(t("export_csv")) {
                App.launch {
                    val name = "fulla-${io.github.sirallap.fulla.core.time.LocalDate.now()}.csv"
                    shareOrDownload(name, "text/csv", io.github.sirallap.fulla.client.local.CsvExport.write(view.stored, view.rows.map { it.transaction }))
                }
            }
        }
        child("p", "muted pad") { text(t("backup_save_text")) }
        val persisted = Idb.available
        child("p", if (persisted) "muted pad" else "problem") { text(if (persisted) t("web_storage_note") else t("web_storage_missing")) }
    }

    private fun HTMLElement.about() {
        child("p", "pad") { text(t("privacy_text")) }
        child("p", "muted pad") { text(t("licence_text")) }
        child("p", "muted pad") { text(t("third_party_licences_text")) }
        child("p", "pad") {
            val link = child("a") {
                attr("href", "https://github.com/SirAllap/fulla"); attr("rel", "noopener"); text("github.com/SirAllap/fulla")
            }
            link.attr("target", "_blank")
        }
    }
}
