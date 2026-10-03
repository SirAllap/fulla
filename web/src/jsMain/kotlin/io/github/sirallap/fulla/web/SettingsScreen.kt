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
    FIELDS("settings_fields", "tune"),
    ACCOUNTS("settings_accounts", "account_balance"),
    BUDGETS("settings_budgets", "pie_chart"),
    TRIPS("settings_trips", "luggage"),
    RECURRING("settings_recurring", "event"),
    IMPORT("import_statement", "file_upload"),
    RULES("settings_rules", "rule"),
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
            SettingsPage.FIELDS -> FieldsPage.build(this, view, canEdit)
            SettingsPage.IMPORT -> ImportPage.build(this, view, format)
            SettingsPage.RULES -> RulesPage.build(this, view, canEdit)
            SettingsPage.ACCOUNTS -> AccountsPage.build(this, view, format, canEdit)
            SettingsPage.BUDGETS -> BudgetsPage.build(this, view, format, canEdit)
            SettingsPage.TRIPS -> TripsPage.build(this, view, format)
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
        section(t("theme"), first = true)
        chipRow {
            for ((m, label) in listOf("system" to "theme_system", "light" to "theme_light", "dark" to "theme_dark")) chip(t(label), Theme.mode == m) { Theme.choose(mode = m) }
        }
        section(t("language"))
        div("rows") {
            listRow(t("app_language"), context = I18n.languages.first { it.first == I18n.language }.second, detail = t("app_language_text"), end = { chevron() }) {
                sheet(t("app_language")) { close ->
                    div("rows") {
                        for ((code, name) in I18n.languages) listRow(name, end = { if (code == I18n.language) span("check") { ui("check") } }) { close(); App.setLanguage(code) }
                    }
                }
            }
        }
        section(t("accent"))
        note(t("accent_text"))
        div("swatches") {
            for (a in io.github.sirallap.fulla.core.design.Accent.entries) {
                val name = t(when (a) {
                    io.github.sirallap.fulla.core.design.Accent.GOLD -> "accent_gold"; io.github.sirallap.fulla.core.design.Accent.LAVENDER -> "accent_lavender"
                    io.github.sirallap.fulla.core.design.Accent.SEA -> "accent_sea"; io.github.sirallap.fulla.core.design.Accent.SAGE -> "accent_sage"
                    io.github.sirallap.fulla.core.design.Accent.ROSE -> "accent_rose"; io.github.sirallap.fulla.core.design.Accent.EMBER -> "accent_ember"
                    io.github.sirallap.fulla.core.design.Accent.PLATINUM -> "accent_platinum"
                })
                child("button", "swatch") {
                    attr("type", "button"); attr("aria-label", name); attr("aria-pressed", (Theme.accent == a).toString())
                    span("swatch-dot") {
                        style.background = "#" + (a.fill and 0xFFFFFF).toString(16).padStart(6, '0')
                        if (Theme.accent == a) { style.color = "#" + (a.onFill and 0xFFFFFF).toString(16).padStart(6, '0'); ui("check", 20) }
                    }
                    click { Theme.choose(accent = a) }
                }
            }
        }
        section(t("money_colours"))
        note(t("money_colours_text"))
        div("rows") {
            val dark = Liquids.dark()
            for (p in io.github.sirallap.fulla.core.design.MoneyPalette.entries) {
                val colors = p.of(dark)
                fun hex(c: Long) = "#" + (c and 0xFFFFFF).toString(16).padStart(6, '0')
                listRow(t(when (p.name) { "INK" -> "palette_ink"; "AMBER" -> "palette_amber"; "SEA" -> "palette_sea"; "FOREST" -> "palette_forest"; else -> "palette_classic" }),
                    start = {
                        span("dots") {
                            span("dot-c") { style.background = hex(colors.inSurface) }
                            span("dot-c") { style.background = hex(colors.outSurface) }
                        }
                    },
                    end = { if (Theme.palette == p) span("check") { ui("check") } }) { Theme.choose(palette = p) }
            }
        }
    }

    private fun HTMLElement.backup(view: HouseholdView, format: Format) {
        note(t(if (view.connected) "backup_text_shared" else "backup_text_local"))
        if (!view.connected && backupDue()) note(t("web_backup_due_text"), "warn")
        div("rows") {
            listRow(t("backup_save"), context = t("backup_save_text"), start = leadIcon("file_download")) {
                App.launch {
                    val name = "fulla-${view.config.household.name.filter { it.isLetterOrDigit() }.lowercase()}-${io.github.sirallap.fulla.core.time.LocalDate.now()}.json"
                    if (shareOrDownload(name, "application/json", Ledger.backupText())) { Ledger.noteBackupSaved(); App.toast(t("backup_saved")) }
                }
            }
            val picker = input("file", cls = "hidden") { accept = ".json,application/json" }
            picker.on("change") {
                val file = picker.files?.item(0)
                if (file != null) App.launch {
                    when (Ledger.restore(readText(file))) {
                        RestoreResult.RESTORED -> App.toast(t("backup_restored"))
                        RestoreResult.ALREADY_HERE -> App.toast(t("backup_already_here"))
                        RestoreResult.NOT_A_BACKUP -> App.toast(t("backup_not_a_backup"))
                        RestoreResult.NEWER -> App.toast(t("backup_newer"))
                        RestoreResult.DAMAGED -> App.toast(t("backup_damaged"))
                        RestoreResult.CSV -> App.toast(t("backup_is_csv"))
                    }
                }
            }
            listRow(t("backup_restore"), context = t("backup_restore_text"), start = leadIcon("file_upload")) { picker.click() }
            listRow(t("export_csv"), start = leadIcon("description")) {
                App.launch { shareOrDownload("fulla-${io.github.sirallap.fulla.core.time.LocalDate.now()}.csv", "text/csv", io.github.sirallap.fulla.client.local.CsvExport.write(view.stored, view.rows.map { it.transaction })) }
            }
        }
        val persisted = Idb.available
        note(if (persisted) t("web_storage_note") else t("web_storage_missing"), if (persisted) "muted" else "problem")
    }

    private fun HTMLElement.about() {
        div("rows") {
            listRow(t("app_name"), context = t("version", "web"))
            listRow(t("licence"), context = t("licence_text"))
            listRow(t("privacy"), context = t("privacy_text"))
            listRow(t("third_party_licences"), context = t("third_party_licences_text"), end = { chevron() }) {
                sheet(t("third_party_licences")) { _ ->
                    for ((name, text) in listOf(
                        "Archivo (SIL Open Font License 1.1)" to "© The Archivo Project Authors. https://github.com/Omnibus-Type/Archivo",
                        "Material Icons (Apache License 2.0)" to "© Google. https://github.com/marella/material-design-icons",
                        "Kotlin, kotlinx (Apache License 2.0)" to "© JetBrains s.r.o. and Kotlin Programming Language contributors.",
                        "Ktor (Apache License 2.0)" to "© JetBrains s.r.o.",
                        "qrcode-generator (MIT)" to "© Kazuhiko Arase. https://github.com/kazuhikoarase/qrcode-generator",
                    )) { child("p", "t-secondary pad") { text("── $name ──") }; child("p", "muted pad") { text(text) } }
                }
            }
            listRow("github.com/SirAllap/fulla", start = leadIcon("link"), end = { chevron() }) { window.open("https://github.com/SirAllap/fulla", "_blank", "noopener") }
        }
    }
}
