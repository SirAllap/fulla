// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.core.analytics.FixedItem
import io.github.sirallap.fulla.core.analytics.FixedStatus
import io.github.sirallap.fulla.core.analytics.PeriodForecast
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth
import org.w3c.dom.HTMLElement

/** This period at a glance: what came in, what went out, where it went, what is still to be charged. */
object OverviewScreen {
    /** null: the period that contains today. */
    private var chosen: YearMonth? = null
    private var chargedOpen = false

    fun build(view: HouseholdView, format: Format): HTMLElement {
        val today = LocalDate.now()
        val current = view.currentPeriod(today)
        val period = chosen ?: current
        val summary = view.analytics.summary(view.active, period)
        val hero = view.analytics.hero(view.active, period)
        val rows = view.analytics.byCategory(view.active, period)
        val forecast = if (period == current) runCatching { view.analytics.forecast(view.active, period, today, view.deletedIds) }.getOrNull() else null

        return el("main", "screen overview") {
            tabHeader(t("tab_overview"))
            InstallHint.card()?.let { appendChild(it) }
            div("period") {
                appendChild(iconButton("chevron_left", t("previous_period"), "accent") { chosen = period.minusMonths(1); App.render() })
                span("t-amount label") { text(format.period(period)) }
                appendChild(iconButton("chevron_right", t("next_period"), "accent") {
                    chosen = if (period.plusMonths(1) == current) null else period.plusMonths(1); App.render()
                }.also { if (period == current) it.setAttribute("disabled", "") })
            }
            if (summary.incomeMinor == 0L && summary.expenseMinor == 0L && rows.isEmpty()) {
                div("empty") {
                    child("h2", "t-title") { text(t("empty_period_title")) }
                    child("p", "muted") { text(t("empty_period_text")) }
                }
            } else {
                appendChild(Jar.build(hero.incomeFraction, hero.expenseFraction))
                div("figures") {
                    figure(t("money_in"), format.money(summary.incomeMinor), "in")
                    figure(t("money_out"), format.money(summary.expenseMinor), "out")
                    figure(t("savings"), format.money(hero.savingsMinor, signed = true), "")
                }
            }
            if (forecast != null && !forecast.waiting) forecastRows(forecast, format)
            if (forecast != null && forecast.fixed.isNotEmpty()) fixedCosts(forecast, format, today)
            if (rows.isNotEmpty()) {
                section(t("where_it_went"))
                for (row in rows.sortedByDescending { it.amountMinor }) {
                    val category = view.config.category(row.categoryId)
                    listRow(
                        title = category?.name ?: t("uncategorized"),
                        start = { span("glyph") { style.setProperty("--tile", "var(--cat-${(category?.colorIndex ?: 0).mod(12)})"); categoryIcon(category?.icon ?: "label", 22) } },
                        end = { amountText(format.money(row.amountMinor)) },
                    ).also { r ->
                        r.querySelector(".text")?.let { text ->
                            val m = el("div", "meter") { style.setProperty("--tile", "var(--cat-${(category?.colorIndex ?: 0).mod(12)})"); div("") { style.width = "${(row.share * 100).coerceIn(1.0, 100.0)}%" } }
                            text.appendChild(m)
                        }
                    }
                }
            }
        }
    }

    private fun HTMLElement.figure(label: String, value: String, tone: String) = div("figure $tone") {
        span("t-label") { text(label) }
        div("v") { text(value) }
    }

    private fun HTMLElement.forecastRows(f: PeriodForecast, format: Format) {
        val left = f.leftToSpendMinor ?: return
        if (f.early) return
        section(t("left_to_spend"))
        listRow(format.money(left), context = t("left_to_spend_text", format.money(f.totalIncomeMinor), format.money(f.spentMinor), format.money(f.fixedToComeMinor)),
            detail = f.perDayMinor?.takeIf { it > 0 }?.let { t("per_day_text", f.length - f.day) + " · " + format.money(it) })
    }

    private fun HTMLElement.fixedCosts(f: PeriodForecast, format: Format, today: LocalDate) {
        val pending = f.fixed.filter { it.status == FixedStatus.PENDING }
        val charged = f.fixed.filter { it.status != FixedStatus.PENDING }
        section(t("fixed_costs"))
        child("p", "muted t-secondary pad") { text(t("fixed_progress", format.money(f.fixedPaidMinor), format.money(f.fixedToComeMinor))) }
        for (item in pending) fixedRow(item, format, today, f.length)
        if (charged.isNotEmpty()) {
            listRow(t("fixed_charged_group", charged.size), end = { span("chev") { ui(if (chargedOpen) "expand_less" else "expand_more") } }) { chargedOpen = !chargedOpen; App.render() }
            if (chargedOpen) for (item in charged) fixedRow(item, format, today, f.length)
        }
    }

    private fun HTMLElement.fixedRow(item: FixedItem, format: Format, today: LocalDate, length: Int) {
        val paid = item.status == FixedStatus.PAID
        val skipped = item.status == FixedStatus.SKIPPED
        val r = listRow(
            title = item.name + if (item.installment != null && item.installments != null) " · " + t("installment_of", item.installment, item.installments) else "",
            detail = when {
                skipped -> t("fixed_skipped")
                paid -> t("fixed_paid_on", format.day(item.date))
                item.overdue -> t("fixed_overdue", format.day(item.date))
                else -> t("fixed_due_on", format.day(item.date))
            },
            end = {
                amountText(format.money(item.amountMinor))
                if (item.overdue && !paid) button(t("fixed_apply"), "chip") { App.launch { Ledger.applyRecurring(item.ruleId, item.date) } }
            },
        )
        r.querySelector(".text")?.let { text ->
            val bar = el("div", if (paid) "meter paid" else "meter") { div("") { style.width = "${(item.countdown(today, length) * 100).toInt()}%" } }
            text.insertBefore(bar, text.querySelector(".sub"))
        }
    }
}
