// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.goals.Goal
import io.github.sirallap.fulla.core.goals.Goals
import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.time.LocalDate
import org.w3c.dom.HTMLElement

/** Settings › Savings goals, and a goal's row wherever it is shown (Balances too). */
object GoalsPage {
    fun build(parent: HTMLElement, view: HouseholdView, format: Format) = parent.run {
        val canEdit = view.config.me()?.let { io.github.sirallap.fulla.core.roles.Permissions.canEditTrips(it) } ?: true
        val live = view.config.goals.filter { !it.archived }
        if (live.isEmpty()) emptyState("flag", t("goals_empty_title"), t("goals_empty_text"))
        else div("rows card") { for (g in live) goalRow(view, format, g, onClick = if (canEdit) ({ edit(view, format, g) }) else null) }
        val archived = view.config.goals.filter { it.archived }
        if (archived.isNotEmpty()) {
            section(t("archived"))
            div("rows card") { for (g in archived) listRow(g.name, dim = true, onClick = if (canEdit) ({ edit(view, format, g) }) else null) }
        }
        if (canEdit) div("actions") { primaryButton(t("goal_new")) { edit(view, format, null) } }
    }

    /** A goal and how far it has got: saved of target, what to put aside a month, and a line that fills up. */
    fun HTMLElement.goalRow(view: HouseholdView, format: Format, goal: Goal, onClick: (() -> Unit)?) {
        val today = LocalDate.now()
        val balance = view.analytics.accountBalances(view.active, view.config.accounts, today)[goal.accountId] ?: 0L
        val p = Goals.progress(goal, balance, today)
        val account = view.config.account(goal.accountId)?.name ?: "?"
        val detail = when {
            p.reached -> t("goal_reached")
            p.late -> t("goal_late", format.money(p.leftMinor))
            p.perMonthMinor != null -> t("goal_per_month", format.money(p.perMonthMinor!!), format.day(goal.targetDate!!))
            else -> t("goal_left", format.money(p.leftMinor))
        }
        listRow(goal.name, context = t("goal_saved_of", format.money(p.savedMinor), format.money(goal.targetMinor), account), detail = detail,
            below = { div(if (p.reached) "progress paid" else "progress") { div("") { style.width = "${(p.fraction * 100).coerceIn(0.0, 100.0)}%" } } },
            onClick = onClick)
    }

    private fun edit(view: HouseholdView, format: Format, existing: Goal?) {
        val accounts = view.config.accounts.filter { !it.archived || it.id == existing?.accountId }
        var name = existing?.name ?: ""
        var target = existing?.targetMinor?.let { format.plain(it) } ?: ""
        // A new goal starts in the household's savings account, if it has one.
        var accountId = existing?.accountId ?: (accounts.firstOrNull { it.type == AccountType.SAVINGS } ?: accounts.firstOrNull())?.id
        var date: LocalDate? = existing?.targetDate
        var archived = existing?.archived ?: false
        sheet(existing?.name ?: t("goal_new")) { close ->
            val body = div("")
            fun paint() {
                body.clear()
                body.run {
                    // What a person types is read as typed, either decimal key; text that is not an amount says so.
                    val parsed = target.takeIf { it.isNotBlank() }?.let { MoneyParser.parseTyped(it, format.currency, format.decimalStyle) }
                    val problem = if (target.isNotBlank() && (parsed == null || parsed <= 0)) t("goal_target_invalid") else null
                    var save: HTMLElement? = null
                    fun ready() = name.isNotBlank() && name.length <= 40 && parsed != null && parsed > 0 && accountId != null
                    val n = field(t("goal_name"), name) { attr("maxlength", "40") }
                    n.on("input") { name = n.value; save?.let { if (ready()) it.removeAttribute("disabled") else it.setAttribute("disabled", "") } }
                    val amount = field(t("goal_target"), target, help = format.currency.code) { attr("inputmode", "decimal") }
                    amount.on("change") { target = amount.value; paint() }
                    problem?.let { child("p", "problem") { text(it) } }
                    section(t("goal_account"))
                    chipRow(wrap = true) { for (a in accounts) chip(a.name, a.id == accountId) { accountId = a.id; paint() } }
                    section(t("goal_date"))
                    chipRow {
                        chip(t("goal_no_date"), date == null) { date = null; paint() }
                    }
                    val d = field(t("goal_pick_date"), date?.toString() ?: "", "date")
                    d.on("change") { date = runCatching { LocalDate.parse(d.value) }.getOrNull(); paint() }
                    note(t("goal_help"))
                    if (existing != null) div("rows") { switchRow(t("archive"), t("goal_archive_help"), archived) { archived = it } }
                    div("actions") {
                        save = primaryButton(t("save"), enabled = ready()) {
                            val minor = parsed ?: return@primaryButton
                            val account = accountId ?: return@primaryButton
                            close()
                            val goal = Goal(existing?.id ?: randomUuid(), name.trim(), minor, account, date, archived)
                            App.launch { try { Ledger.upsert(Structure.GOAL, Wire.goal(goal)) } catch (x: Throwable) { App.toast(Remote.message(x)) } }
                        }
                        quietButton(t("cancel")) { close() }
                    }
                }
            }
            paint()
        }
    }
}
