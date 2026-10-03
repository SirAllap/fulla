// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.roles.Permissions
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.trips.Trip
import io.github.sirallap.fulla.core.trips.TripKind
import io.github.sirallap.fulla.core.trips.TripPhase
import io.github.sirallap.fulla.core.trips.Trips
import kotlinx.browser.window
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.HTMLElement

internal fun tripKindIcon(kind: TripKind): String = when (kind) {
    TripKind.HOLIDAY -> "beach_access"; TripKind.WORK -> "work"; TripKind.EVENT -> "celebration"
    TripKind.FAMILY -> "family_restroom"; TripKind.OTHER -> "luggage"
}

internal fun tripKindLabel(kind: TripKind): String = t(when (kind) {
    TripKind.HOLIDAY -> "trip_kind_holiday"; TripKind.WORK -> "trip_kind_work"; TripKind.EVENT -> "trip_kind_event"
    TripKind.FAMILY -> "trip_kind_family"; TripKind.OTHER -> "trip_kind_other"
})

/** Settings › Trips: upcoming, active and finished trips, archived ones folded under them. */
object TripsPage {
    private var archivedOpen = false

    fun build(parent: HTMLElement, view: HouseholdView, format: Format) = parent.run {
        val today = LocalDate.now()
        val canEdit = view.config.me()?.let { Permissions.canEditTrips(it) } == true
        val trips = view.config.trips
        val live = trips.filterNot { it.archived }
        fun rows(title: String, list: List<Trip>) {
            if (list.isEmpty()) return
            section(t(title))
            div("rows") { for (tr in list) tripRow(tr, format) }
        }
        if (trips.isEmpty()) emptyState("luggage", t("settings_trips"), t("trip_name"))
        rows("trip_upcoming", live.filter { Trips.phase(it, today) == TripPhase.UPCOMING })
        rows("trip_active", live.filter { Trips.phase(it, today) == TripPhase.ACTIVE })
        rows("trip_finished", live.filter { Trips.phase(it, today) == TripPhase.FINISHED })
        val archived = trips.filter { it.archived }
        if (archived.isNotEmpty()) div("rows") {
            listRow(t("archived"), end = { span("chev") { ui(if (archivedOpen) "expand_less" else "expand_more") } }) { archivedOpen = !archivedOpen; App.render() }
            if (archivedOpen) for (tr in archived) tripRow(tr, format)
        }
        if (canEdit) div("actions") { primaryButton(t("trip_new")) { edit(view, format, null) } }
    }

    private fun HTMLElement.tripRow(tr: Trip, format: Format) {
        listRow(tr.name, context = tr.budgetMinor?.let { t("of_budget", format.money(it)) }, start = leadIcon(tripKindIcon(tr.kind)),
            end = { chevron() }) { App.openTrip(tr.id) }
    }

    fun edit(view: HouseholdView, format: Format, existing: Trip?) {
        var name = existing?.name ?: ""
        var start = existing?.startDate ?: LocalDate.now()
        var end = existing?.endDate ?: existing?.startDate ?: LocalDate.now()
        var budget = existing?.budgetMinor?.let { format.plain(it) } ?: ""
        var inBudgets = existing?.inCategoryBudgets ?: false
        // A brand new trip starts with just its creator on it: who else is going is an explicit choice.
        var members = existing?.memberIds?.toSet() ?: setOfNotNull(view.config.meMemberId)
        var kind = existing?.kind ?: TripKind.HOLIDAY
        var saveButton: HTMLElement? = null
        sheet(existing?.name ?: t("trip_new")) { close ->
            val body = div("")
            body.run {
                fun paint() {
                    body.clear()
                    body.run {
                        val n = field(t("trip_name"), name) { attr("maxlength", "40") }
                        n.on("input") { name = n.value; saveButton?.let { b -> if (name.isNotBlank()) b.removeAttribute("disabled") else b.setAttribute("disabled", "") } }
                        section(t("trip_dates"))
                        val s = field(t("trip_dates"), start.toString(), "date"); s.on("change") { runCatching { LocalDate.parse(s.value) }.getOrNull()?.let { start = it; paint() } }
                        val e = field("→", end.toString(), "date"); e.on("change") { runCatching { LocalDate.parse(e.value) }.getOrNull()?.let { end = it; paint() } }
                        val b = field(t("trip_budget"), budget, help = format.currency.code) { attr("inputmode", "decimal") }
                        b.on("input") { budget = b.value }
                        div("rows") { switchRow(t("trip_in_budgets"), t("trip_in_budgets_help"), inBudgets) { inBudgets = it } }
                        section(t("trip_kind"))
                        chipRow(wrap = true) { for (k in TripKind.entries) chip(tripKindLabel(k), kind == k) { kind = k; paint() } }
                        section(t("trip_who_is_going"))
                        chipRow(wrap = true) {
                            for (m in view.config.activeMembers) chip(m.displayName, m.id in members) { members = if (m.id in members) members - m.id else members + m.id; paint() }
                        }
                        val (from, to) = if (end < start) end to start else start to end
                        val overlap = view.config.trips.firstOrNull { it.id != existing?.id && !it.archived && start <= it.endDate && end >= it.startDate }
                        val parsed = budget.takeIf { it.isNotBlank() }?.let { MoneyParser.parseTyped(it, format.currency, format.decimalStyle) }
                        val problem = when {
                            to.toEpochDay() - from.toEpochDay() >= 366 -> t("trip_span_too_long")
                            budget.isNotBlank() && (parsed == null || parsed <= 0) -> t("trip_budget_invalid")
                            else -> null
                        }
                        overlap?.let { child("p", "warn pad") { text(t("trip_overlaps", it.name)) } }
                        problem?.let { child("p", "problem") { text(it) } }
                        div("actions") {
                            saveButton = primaryButton(t("save"), enabled = name.isNotBlank() && problem == null) {
                                close()
                                val item = buildJsonObject {
                                    put("id", existing?.id ?: randomUuid()); put("name", name.trim())
                                    put("start_date", from.toString()); put("end_date", to.toString())
                                    put("budget_minor", parsed); put("in_category_budgets", inBudgets)
                                    put("archived", existing?.archived ?: false)
                                    put("member_ids", JsonArray(members.map { JsonPrimitive(it) })); put("trip_kind", kind.wire)
                                }
                                App.launch { try { Ledger.upsert(Structure.TRIP, item) } catch (x: Throwable) { App.toast(Remote.message(x)) } }
                            }
                            quietButton(t("cancel")) { close() }
                        }
                    }
                }
                paint()
            }
        }
    }
}

/** A trip: the money side of a place or event. The jar (with a budget) is the only loud element, as on the Overview. */
object TripScreen {
    fun build(view: HouseholdView, format: Format, tripId: String): HTMLElement = el("main", "screen no-tabs") {
        val trip = view.config.trip(tripId)
        if (trip == null) { backHeader(t("settings_trips")) { App.closeTrip() }; return@el }
        backHeader(trip.name) { App.closeTrip() }
        val header = children.item(0) as HTMLElement
        header.appendChild(iconButton("edit", t("trip_name")) { TripsPage.edit(view, format, trip) })
        header.appendChild(iconButton(if (trip.archived) "unarchive" else "archive", t(if (trip.archived) "trip_unarchive" else "trip_archive")) {
            val item = buildJsonObject {
                put("id", trip.id); put("name", trip.name); put("start_date", trip.startDate.toString()); put("end_date", trip.endDate.toString())
                put("budget_minor", trip.budgetMinor); put("in_category_budgets", trip.inCategoryBudgets); put("archived", !trip.archived)
            }
            App.launch { Ledger.upsert(Structure.TRIP, item) }
        })
        header.appendChild(iconButton("delete_outline", t("trip_delete")) {
            confirmSheet(null, t("trip_delete_confirm", trip.name), t("trip_delete"), danger = true) {
                App.launch { try { Ledger.deleteTrip(trip.id); App.closeTrip() } catch (e: Throwable) { App.toast(Remote.message(e)) } }
            }
        })
        val today = LocalDate.now()
        val budget = trip.budgetMinor
        val rows = view.active.filter { it.tripId == trip.id }
        val totals = Trips.totals(trip, rows)
        val perDay = Trips.perDay(trip, totals, today)
        val left = totals.leftMinor ?: 0
        if (budget != null) {
            val hero = io.github.sirallap.fulla.core.analytics.Hero(budget, totals.spentMinor)
            appendChild(Jar.build(hero.incomeFraction, hero.expenseFraction, format.money(left), left,
                t("trip_left", format.money(left), format.money(budget))))
        }
        div("figures trip") {
            div("figure out") { span("t-label") { text(t("money_out")) }; div("v") { text(format.money(totals.spentMinor)) } }
            if (budget != null) div("figure") { span("t-label") { text(if (totals.overMinor > 0) t("trip_over", format.money(totals.overMinor)) else t("budget_left", format.money(left))) } }
            perDay?.let { p -> div("figure") { span("t-label") { text(tp("trip_per_day", p.days, format.money(p.amountMinor), p.days)) } } }
        }
        if (!SharedPot.isShared(view.config.household) && view.config.activeMembers.size >= 2) {
            val paid = LinkedHashMap<String, Long>()
            for (r in rows) { val sign = if (r.kind == TransactionKind.REFUND) -1 else 1; r.paidByMemberId?.let { paid[it] = (paid[it] ?: 0) + sign * r.amountMinor } }
            section(t("settings_members"))
            div("rows") { for ((id, amount) in paid) listRow(view.memberName(id), end = { amountText(format.money(amount)) }) }
        }
        if (rows.isEmpty()) emptyState("edit", trip.name, t("empty_period_text"))
        else {
            section(t("where_it_went"))
            div("rows") {
                for (r in rows.sortedByDescending { it.date }) listRow(r.note.ifBlank { view.config.category(r.categoryId)?.name ?: "" }, context = format.day(r.date),
                    end = { amountText(format.money(if (r.kind == TransactionKind.REFUND) -r.amountMinor else r.amountMinor)) }) { App.closeTrip(); App.editing = r.id; App.go(Tab.ADD) }
            }
        }
    }
}
