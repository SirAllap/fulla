// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.local.CsvExport
import io.github.sirallap.fulla.client.local.RecurringPlanner
import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.model.AppliesTo
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import io.github.sirallap.fulla.core.time.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

enum class SettingsPage(val title: String) {
    HOUSEHOLD("settings_household"), FIXED("settings_recurring"), CATEGORIES("settings_categories"),
    ACCOUNTS("settings_accounts"), BACKUP("settings_backup"), ABOUT("settings_about"),
}

/** Everything that is not writing something down. */
object SettingsScreen {
    private const val WEEK_IN_MS = 7 * 24 * 60 * 60 * 1000L

    fun build(view: HouseholdView, format: Format): HTMLElement = el("main", "screen settings") {
        val page = App.settingsPage
        div("header") {
            if (page != null) button("‹", "btn icon") { App.settingsPage = null; FixedForm.close(); App.render() }.attr("aria-label", t("back"))
            child("h1", "title") { text(if (page == null) t("settings") else t(page.title)) }
        }
        when (page) {
            null -> list(view, format)
            SettingsPage.HOUSEHOLD -> household(view, format)
            SettingsPage.FIXED -> FixedForm.page(this, view, format)
            SettingsPage.CATEGORIES -> categories(view)
            SettingsPage.ACCOUNTS -> accounts(view, format)
            SettingsPage.BACKUP -> backup(view, format)
            SettingsPage.ABOUT -> about()
        }
    }

    private fun backupDue(): Boolean {
        val last = Ledger.lastBackupAt ?: return true
        return io.github.sirallap.fulla.core.time.Instant.now().toEpochMilli() - last > 4 * WEEK_IN_MS
    }

    private fun HTMLElement.row(title: String, detail: String? = null, badge: Boolean = false, onClick: () -> Unit) {
        child("button", "list-row") {
            attr("type", "button")
            div("row-text") {
                span("name") { text(title) }
                if (detail != null) span("muted small") { text(detail) }
            }
            if (badge) span("dot") { attr("aria-hidden", "true") }
            span("chevron") { text("›") }
            click(onClick)
        }
    }

    private fun HTMLElement.list(view: HouseholdView, format: Format) {
        val config = view.config
        div("card flush") {
            row(config.household.name, format.currency.code) { App.settingsPage = SettingsPage.HOUSEHOLD; App.render() }
            row(t("settings_recurring"), t("fixed_help")) { App.settingsPage = SettingsPage.FIXED; App.render() }
            row(t("settings_categories")) { App.settingsPage = SettingsPage.CATEGORIES; App.render() }
            row(t("settings_accounts")) { App.settingsPage = SettingsPage.ACCOUNTS; App.render() }
            row(t("settings_backup"), if (backupDue()) t("backup_due") else null, badge = backupDue()) { App.settingsPage = SettingsPage.BACKUP; App.render() }
        }
        div("card") {
            label(t("language"))
            val select = child("select", "select") {
                for ((code, name) in I18n.languages) child("option") {
                    attr("value", code); text(name)
                    if (code == I18n.language) attr("selected", "selected")
                }
            } as HTMLSelectElement
            select.on("change") { App.setLanguage(select.value) }
        }
        div("card flush") {
            row(t("settings_about")) { App.settingsPage = SettingsPage.ABOUT; App.render() }
        }
        if (!Idb.available) child("p", "problem") { text(t("web_storage_missing")) }
    }

    private fun HTMLElement.household(view: HouseholdView, format: Format) {
        div("card") {
            val name = field(t("household_name"), view.config.household.name)
            div("field") { label(t("currency")); span("static") { text(format.currency.code) }; child("small", "muted") { text(t("currency_change_note")) } }
            button(t("save"), "btn primary wide") {
                val next = name.value.trim()
                if (next.isNotEmpty()) App.launch {
                    Ledger.updateHousehold(buildJsonObject { put("name", next) })
                    App.toast(t("web_saved"))
                }
            }
        }
        div("card") {
            child("p", "muted") { text(t("web_forget_text")) }
            button(t("forget_household"), "btn danger wide") {
                if (js("confirm")(t("web_forget_confirm")) as Boolean) App.launch {
                    Ledger.forget()
                    App.settingsPage = null
                    App.tab = Tab.ADD
                }
            }
        }
    }

    // ── categories ───────────────────────────────────────────────────────────

    private fun HTMLElement.categories(view: HouseholdView) {
        val stored = Ledger.view?.stored?.categories.orEmpty()
        div("card flush") {
            for (c in view.config.categories.sortedBy { it.sort }) {
                val original = stored.firstOrNull { it.id == c.id } ?: c
                div(if (c.archived) "list-row static archived" else "list-row static") {
                    style.setProperty("--tile", "var(--cat-${c.colorIndex % 12})")
                    span("tile-icon small") { text(Icons.of(c.icon)) }
                    div("row-text") {
                        span("name") { text((if (c.parentId != null) "↳ " else "") + c.name) }
                        span("muted small") { text(when (c.appliesTo) { AppliesTo.INCOME -> t("income"); AppliesTo.EXPENSE -> t("spending"); else -> "" }) }
                    }
                    button(if (c.archived) t("restore") else t("archive"), "btn small secondary") {
                        App.launch { Ledger.upsert(Structure.CATEGORY, Wire.category(original.copy(archived = !c.archived))) }
                    }
                }
            }
        }
        div("card") {
            child("h2", "card-title") { text(t("add_category")) }
            val name = field(t("name"))
            val applies = el("select", "select") {
                child("option") { attr("value", "expense"); text(t("spending")); attr("selected", "selected") }
                child("option") { attr("value", "income"); text(t("income")) }
            } as HTMLSelectElement
            div("field") { label(t("category")); appendChild(applies) }
            button(t("add_category"), "btn primary wide") {
                val title = name.value.trim()
                if (title.isNotEmpty()) App.launch {
                    val next = (view.stored.categories.maxOfOrNull { it.sort } ?: 0) + 1
                    Ledger.upsert(Structure.CATEGORY, Wire.category(Category(randomUuid(), title,
                        if (applies.value == "income") AppliesTo.INCOME else AppliesTo.EXPENSE, null, "label", next % 12, next, false)))
                    App.toast(t("web_saved"))
                }
            }
        }
    }

    // ── accounts ─────────────────────────────────────────────────────────────

    private fun HTMLElement.accounts(view: HouseholdView, format: Format) {
        val stored = view.stored.accounts
        div("card flush") {
            for (a in view.config.accounts.sortedBy { it.sort }) {
                val original = stored.firstOrNull { it.id == a.id } ?: a
                div(if (a.archived) "list-row static archived" else "list-row static") {
                    div("row-text") { span("name") { text(a.name) } }
                    button(if (a.archived) t("restore") else t("archive"), "btn small secondary") {
                        App.launch { Ledger.upsert(Structure.ACCOUNT, Wire.account(original.copy(archived = !a.archived))) }
                    }
                }
            }
        }
        div("card") {
            child("h2", "card-title") { text(t("add_account")) }
            val name = field(t("name"))
            button(t("add_account"), "btn primary wide") {
                val title = name.value.trim()
                if (title.isNotEmpty()) App.launch {
                    val next = (stored.maxOfOrNull { it.sort } ?: 0) + 1
                    Ledger.upsert(Structure.ACCOUNT, Wire.account(io.github.sirallap.fulla.core.model.Account(randomUuid(), title, io.github.sirallap.fulla.core.model.AccountType.OTHER, 0, null, next, false)))
                    App.toast(t("web_saved"))
                }
            }
        }
    }

    // ── backup ───────────────────────────────────────────────────────────────

    private fun HTMLElement.backup(view: HouseholdView, format: Format) {
        div("card") {
            child("p", "muted") { text(t("web_backup_text")) }
            if (backupDue()) child("p", "warn") { text(t("web_backup_due_text")) }
            button(t("backup_save"), "btn primary wide") {
                App.launch {
                    val name = "fulla-${view.config.household.name.filter { it.isLetterOrDigit() }.lowercase()}-${LocalDate.now()}.json"
                    if (shareOrDownload(name, "application/json", Ledger.backupText())) {
                        Ledger.noteBackupSaved()
                        App.toast(t("backup_saved"))
                    }
                }
            }
            child("p", "muted small") { text(t("backup_save_text")) }
        }
        div("card") {
            button(t("export_csv"), "btn secondary wide") {
                App.launch {
                    val name = "fulla-${LocalDate.now()}.csv"
                    shareOrDownload(name, "text/csv", CsvExport.write(view.stored, view.rows.map { it.transaction }))
                }
            }
        }
        div("card") {
            val persisted = Idb.available
            child("p", if (persisted) "muted small" else "problem") {
                text(if (persisted) t("web_storage_note") else t("web_storage_missing"))
            }
        }
    }

    private fun HTMLElement.about() {
        div("card") {
            child("p") { text(t("privacy_text")) }
            child("p", "muted") { text(t("licence_text")) }
            child("p", "muted small") { text(t("third_party_licences_text")) }
            child("p", "small") {
                val link = child("a") {
                    attr("href", "https://github.com/SirAllap/fulla"); attr("rel", "noopener"); text("github.com/SirAllap/fulla")
                }
                link.attr("target", "_blank")
            }
        }
    }
}
