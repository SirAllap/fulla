// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.core.analytics.FixedItem
import io.github.sirallap.fulla.core.analytics.FixedStatus
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
            div("header") {
                button("‹", "btn icon") { chosen = period.minusMonths(1); App.render() }.attr("aria-label", t("previous_period"))
                div("period") {
                    child("h1", "title") { text(format.period(period)) }
                    if (period != current) button(t("today"), "btn quiet small") { chosen = null; App.render() }
                }
                button("›", "btn icon") { chosen = if (period.plusMonths(1) == current) null else period.plusMonths(1); App.render() }.attr("aria-label", t("next_period"))
            }
            if (summary.incomeMinor == 0L && summary.expenseMinor == 0L && rows.isEmpty()) {
                div("empty") {
                    child("h2") { text(t("empty_period_title")) }
                    child("p", "muted") { text(t("empty_period_text")) }
                }
            } else {
                div("card hero") {
                    div("figures") {
                        figure(t("money_in"), format.money(summary.incomeMinor), "in")
                        figure(t("money_out"), format.money(summary.expenseMinor), "out")
                        figure(t("savings"), format.money(hero.savingsMinor, signed = true), if (hero.savingsMinor < 0) "warn" else "")
                    }
                    div("bars") {
                        bar("in", hero.incomeFraction)
                        bar("out", hero.expenseFraction)
                    }
                }
            }
            if (forecast != null && forecast.waiting.not()) forecastCard(forecast, format)
            if (forecast != null && forecast.fixed.isNotEmpty()) fixedCosts(forecast, format, today)
            if (rows.isNotEmpty()) div("card") {
                child("h2", "card-title") { text(t("where_it_went")) }
                for (row in rows.sortedByDescending { it.amountMinor }) {
                    val category = view.config.category(row.categoryId)
                    div("cat-row") {
                        style.setProperty("--tile", "var(--cat-${(category?.colorIndex ?: 0) % 12})")
                        span("tile-icon small") { text(Icons.of(category?.icon ?: "label")) }
                        div("cat-main") {
                            div("cat-line") {
                                span("name") { text(category?.name ?: t("uncategorized")) }
                                span("amount") { text(format.money(row.amountMinor)) }
                            }
                            div("meter") { div("fill") { style.width = "${(row.share * 100).coerceIn(1.0, 100.0)}%" } }
                        }
                    }
                }
            }
        }
    }

    private fun HTMLElement.figure(label: String, value: String, tone: String) = div("figure $tone") {
        span("figure-label") { text(label) }
        span("figure-value") { text(value) }
    }

    private fun HTMLElement.bar(tone: String, fraction: Double) = div("hero-bar") {
        div("hero-fill $tone") { style.width = "${(fraction * 100).coerceIn(0.0, 100.0)}%" }
    }

    private fun HTMLElement.forecastCard(f: io.github.sirallap.fulla.core.analytics.PeriodForecast, format: Format) {
        val left = f.leftToSpendMinor ?: return
        if (f.early) return
        div("card") {
            child("h2", "card-title") { text(t("left_to_spend")) }
            div("big") { text(format.money(left)) }
            f.perDayMinor?.let { perDay ->
                if (perDay > 0) child("p", "muted") { text(t("per_day_text", f.length - f.day) + " — " + format.money(perDay)) }
            }
            child("p", "muted small") { text(t("left_to_spend_text", format.money(f.totalIncomeMinor), format.money(f.spentMinor), format.money(f.fixedToComeMinor))) }
        }
    }

    private fun HTMLElement.fixedCosts(f: io.github.sirallap.fulla.core.analytics.PeriodForecast, format: Format, today: LocalDate) {
        val pending = f.fixed.filter { it.status == FixedStatus.PENDING }
        val charged = f.fixed.filter { it.status != FixedStatus.PENDING }
        div("card") {
            child("h2", "card-title") { text(t("fixed_costs")) }
            child("p", "muted small") { text(t("fixed_progress", format.money(f.fixedPaidMinor), format.money(f.fixedToComeMinor))) }
            for (item in pending) fixedRow(item, format, today, f.length)
            if (charged.isNotEmpty()) {
                button(t("fixed_charged_group", charged.size) + if (chargedOpen) " ▴" else " ▾", "btn quiet small") { chargedOpen = !chargedOpen; App.render() }
                if (chargedOpen) for (item in charged) fixedRow(item, format, today, f.length)
            }
        }
    }

    private fun HTMLElement.fixedRow(item: FixedItem, format: Format, today: LocalDate, length: Int) {
        val paid = item.status == FixedStatus.PAID
        val skipped = item.status == FixedStatus.SKIPPED
        div("fixed-row") {
            div("cat-line") {
                span("name") {
                    text(item.name)
                    if (item.installment != null && item.installments != null) text(" · " + t("installment_of", item.installment, item.installments))
                }
                span("amount") { text(format.money(item.amountMinor)) }
            }
            div("meter ${if (paid) "paid" else ""}") { div("fill") { style.width = "${(item.countdown(today, length) * 100).toInt()}%" } }
            child("small", "muted") {
                text(when {
                    skipped -> t("fixed_skipped")
                    paid -> t("fixed_paid_on", format.day(item.date))
                    item.overdue -> t("fixed_overdue", format.day(item.date))
                    else -> t("fixed_due_on", format.day(item.date))
                })
            }
            if (item.overdue && !paid) button(t("fixed_apply"), "btn small secondary") {
                App.launch { Ledger.applyRecurring(item.ruleId, item.date) }
            }
        }
    }
}
