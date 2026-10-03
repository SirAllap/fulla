// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.core.balance.Balances
import io.github.sirallap.fulla.core.balance.Payment
import io.github.sirallap.fulla.core.balance.SettlementPlanner
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.time.LocalDate
import org.w3c.dom.HTMLElement

/** Who owes whom, how to settle it, and what each account holds. */
object BalancesScreen {
    fun build(view: HouseholdView, format: Format): HTMLElement {
        val config = view.config
        val members = config.activeMembers
        val balances = Balances.of(view.active, config.members.map { it.id })
        val plan = SettlementPlanner.plan(balances)
        val accountBalances = view.analytics.accountBalances(view.active, config.accounts.filter { !it.archived }, LocalDate.now())
        val shared = SharedPot.isShared(config.household)

        return el("main", "screen balances") {
            tabHeader(t("tab_balances"))
            if (shared) {
                emptyState("savings", t("shared_pot_card_title"), t("shared_pot_card_text"))
                div("actions") { quietButton(t("shared_pot_change")) { App.openSettings(SettingsPage.HOUSEHOLD) } }
            } else if (members.size >= 2) {
                section(t("between_you"), first = true)
                for (b in balances.filter { x -> config.member(x.memberId)?.isActive == true || x.balanceMinor != 0L }) {
                    val m = config.member(b.memberId)
                    listRow(
                        title = m?.displayName ?: "?",
                        context = when { b.balanceMinor > 0 -> t("is_owed"); b.balanceMinor < 0 -> t("owes"); else -> t("settled") },
                        start = { badge(m?.initials ?: "?", m?.colorIndex ?: 0) },
                        end = { amountText(format.money(b.balanceMinor, signed = true), when { b.balanceMinor > 0 -> "in"; b.balanceMinor < 0 -> "neg"; else -> "muted" }) },
                    )
                }
                section(t("to_settle"))
                if (plan.isEmpty()) emptyState("check_circle", t("all_settled_title"), t("all_settled_text"))
                else for (p in plan) {
                    listRow(
                        title = t("pays", view.memberName(p.fromMemberId), view.memberName(p.toMemberId)),
                        context = t("tap_to_record"),
                        end = { amountText(format.money(p.amountMinor)) },
                        onClick = { confirm(view, format, p) },
                    )
                }
            }
            section(t("accounts"), first = !shared && members.size < 2)
            for (a in config.accounts.filter { !it.archived }) {
                val amount = accountBalances[a.id] ?: 0L
                listRow(a.name, end = { amountText(format.money(amount), if (amount < 0) "neg" else "") })
            }
            if (shared) {
                val total = config.accounts.filter { !it.archived }.sumOf { accountBalances[it.id] ?: 0L }
                listRow(t("household_total"), divider = false, end = { amountText(format.money(total), if (total < 0) "neg" else "") })
            }
        }
    }

    private fun confirm(view: HouseholdView, format: Format, p: Payment) {
        sheet(t("record_payment")) { close ->
            child("p", "pad") { text(t("record_payment_text", view.memberName(p.fromMemberId), format.money(p.amountMinor), view.memberName(p.toMemberId))) }
            div("actions") {
                primaryButton(t("record")) {
                    close()
                    App.launch {
                        Ledger.save(Transaction(
                            id = randomUuid(), kind = TransactionKind.SETTLEMENT, date = LocalDate.now(), amountMinor = p.amountMinor,
                            paidByMemberId = p.fromMemberId, toMemberId = p.toMemberId, createdAt = "", clientUpdatedAt = "",
                            createdByMemberId = view.config.meMemberId,
                        ))
                    }
                }
                quietButton(t("cancel")) { close() }
            }
        }
    }
}
