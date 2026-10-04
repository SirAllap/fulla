// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.guide.Guide
import io.github.sirallap.fulla.core.guide.GuideCursor
import io.github.sirallap.fulla.core.guide.GuideOrigin
import io.github.sirallap.fulla.core.guide.GuidePlan
import io.github.sirallap.fulla.core.guide.GuideStepState
import io.github.sirallap.fulla.core.guide.MonthStart
import io.github.sirallap.fulla.core.guide.OpeningBalances
import io.github.sirallap.fulla.core.guide.SetupStep
import io.github.sirallap.fulla.core.guide.TourStop
import io.github.sirallap.fulla.core.time.LocalDate
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.HTMLElement

/**
 * The getting-started guide, for the household that is open: the setup steps first (when the month starts, what the
 * accounts hold), then a tour with a hole over each part of the app. The same plan, in the same order, as the Android
 * app's (core's Guide); the phone's biometric lock has no web counterpart, so that step is never offered.
 */
object GuideHost {
    private fun key(id: String) = "fulla.guide.$id"

    private fun stored(id: String): Pair<String, String?>? = runCatching {
        localStorage.getItem(key(id))?.split("|", limit = 2)?.let { it[0] to it.getOrNull(1)?.ifEmpty { null } }
    }.getOrNull()

    private fun store(id: String, origin: String, step: String?) { runCatching { localStorage.setItem(key(id), "$origin|${step ?: ""}") } }
    private fun finish(id: String) { runCatching { localStorage.removeItem(key(id)) } }

    /** Starts the guide for this household, from its first step. */
    fun start(id: String, origin: GuideOrigin) { store(id, origin.name, null); setupOpen = false; App.render() }

    private var setupOpen = false
    private var monthChoice: MonthStart? = null
    private var savedLater = false
    private var texts: MutableMap<String, String> = mutableMapOf()

    private fun tabFor(stop: TourStop): Tab? = when (stop) {
        TourStop.JAR, TourStop.OVERVIEW_TAB -> Tab.OVERVIEW
        TourStop.ADD_AND_KEYPAD -> Tab.ADD
        TourStop.HISTORY_TAB -> Tab.HISTORY
        TourStop.BALANCES_TAB -> Tab.BALANCES
        TourStop.SYNC_CLOUD, TourStop.GEAR -> null
    }

    private fun copyFor(stop: TourStop): String = t(when (stop) {
        TourStop.JAR -> "tour_jar"; TourStop.OVERVIEW_TAB -> "tour_overview"; TourStop.ADD_AND_KEYPAD -> "tour_add"
        TourStop.HISTORY_TAB -> "tour_history"; TourStop.BALANCES_TAB -> "tour_balances"; TourStop.SYNC_CLOUD -> "tour_sync"; TourStop.GEAR -> "tour_gear"
    })

    private fun selectorFor(stop: TourStop): String? = when (stop) {
        TourStop.JAR -> ".jar"
        TourStop.OVERVIEW_TAB -> ".tabbar .tab:nth-child(2)"
        TourStop.ADD_AND_KEYPAD -> ".keypad"
        TourStop.HISTORY_TAB -> ".tabbar .tab:nth-child(3)"
        TourStop.BALANCES_TAB -> ".tabbar .tab:nth-child(4)"
        TourStop.GEAR -> ".header .icon-btn:last-child"
        TourStop.SYNC_CLOUD -> null
    }

    /** Called after every draw: puts the current step of the guide over the app, or nothing. */
    fun show(view: HouseholdView, format: Format) {
        document.getElementById("tour")?.let { it.parentNode?.removeChild(it) }
        val (originName, step) = stored(view.id) ?: return
        val origin = GuideOrigin.entries.firstOrNull { it.name == originName } ?: GuideOrigin.REPLAY
        val plan = Guide.plan(origin, view.config.me(), view.config.household, lockAvailable = false)
        if (plan.isEmpty) { finish(view.id); return }
        // Only the sync stop depends on this household being shared; the others are always shown.
        val tour = if (view.connected) plan.tour else plan.tour.filter { it != TourStop.SYNC_CLOUD }
        val state = GuideCursor.decode(step, plan)
        fun save(next: GuideStepState) {
            if (next == GuideStepState.Done) finish(view.id) else store(view.id, originName, GuideCursor.encode(next))
            setupOpen = false; savedLater = false; texts = mutableMapOf(); monthChoice = null
            App.render()
        }
        fun firstTour(): GuideStepState = if (tour.isNotEmpty()) GuideStepState.Tour(plan.tour.indexOf(tour.first())) else GuideStepState.Done
        fun forward(from: GuideStepState): GuideStepState = when (from) {
            is GuideStepState.Setup -> {
                val i = plan.setup.indexOf(from.step)
                when { i + 1 < plan.setup.size -> GuideStepState.Setup(plan.setup[i + 1]); tour.isNotEmpty() -> firstTour(); else -> GuideStepState.Done }
            }
            is GuideStepState.Tour -> {
                var i = from.index + 1
                while (i < plan.tour.size && plan.tour[i] !in tour) i++
                if (i < plan.tour.size) GuideStepState.Tour(i) else GuideStepState.Done
            }
            GuideStepState.Done -> GuideStepState.Done
        }
        fun backward(index: Int): GuideStepState {
            var i = index - 1
            while (i >= 0 && plan.tour[i] !in tour) i--
            return if (i >= 0) GuideStepState.Tour(i) else GuideStepState.Done
        }
        when (state) {
            is GuideStepState.Setup -> {
                if (setupOpen) return
                setupOpen = true
                val stepOf = plan.setup.indexOf(state.step) + 1 to plan.setup.size
                when (state.step) {
                    SetupStep.MONTH_START -> monthStartStep(view, format, stepOf, { save(forward(state)) })
                    SetupStep.OPENING_BALANCES -> openingBalancesStep(view, format, stepOf, { save(forward(state)) })
                    SetupStep.LOCK -> save(forward(state))
                }
            }
            is GuideStepState.Tour -> {
                val stop = plan.tour.getOrNull(state.index)
                if (stop == null) { save(GuideStepState.Done); return }
                if (stop !in tour) { save(forward(state)); return }
                // Switch to the tab the stop needs, then draw the card over it.
                val needed = tabFor(stop)
                if (App.settingsOpen || App.tripId != null || App.insightsOpen || (needed != null && App.tab != needed) || (needed == null && false)) {
                    App.settingsOpen = false; App.tripId = null; App.insightsOpen = false
                    if (needed != null) App.tab = needed
                    App.render(); return
                }
                val position = tour.indexOf(stop) + 1
                tourCard(stop, position to tour.size, stop == tour.last(), { save(forward(state)) }, { save(backward(state.index)) }, { save(GuideStepState.Done) })
            }
            GuideStepState.Done -> finish(view.id)
        }
    }

    // ── setup ────────────────────────────────────────────────────────────────

    private fun HTMLElement.stepHeader(title: String, stepOf: Pair<Int, Int>) {
        child("p", "muted pad") { text(t("guide_step_of", stepOf.first, stepOf.second)) }
    }

    private fun monthStartStep(view: HouseholdView, format: Format, stepOf: Pair<Int, Int>, next: () -> Unit) {
        val choice0 = monthChoice ?: MonthStart.of(view.config.household)
        monthChoice = choice0
        sheet(t("guide_month_title"), dismissable = false) { close ->
            val body = div("")
            body.run {
                fun paint() {
                    body.clear()
                    body.run {
                        val choice = monthChoice!!
                        stepHeader(t("guide_month_title"), stepOf)
                        div("rows") {
                            listRow(t("guide_month_first"), end = { if (choice is MonthStart.Calendar) span("check") { ui("check") } }) { monthChoice = MonthStart.Calendar; paint() }
                            listRow(t("guide_month_payday"), divider = choice !is MonthStart.Payday, end = { if (choice is MonthStart.Payday) span("check") { ui("check") } }) { monthChoice = MonthStart.Payday((choice as? MonthStart.Payday)?.day ?: 25); paint() }
                            if (choice is MonthStart.Payday) stepper(t("period_start_day_value", choice.day), { monthChoice = MonthStart.Payday((choice.day - 1).coerceIn(2, 28)); paint() }, { monthChoice = MonthStart.Payday((choice.day + 1).coerceIn(2, 28)); paint() })
                            listRow(t("guide_month_salary_next"), context = t("guide_month_salary_help"), divider = choice !is MonthStart.SalaryNextMonth,
                                end = { if (choice is MonthStart.SalaryNextMonth) span("check") { ui("check") } }) { monthChoice = MonthStart.SalaryNextMonth((choice as? MonthStart.SalaryNextMonth)?.day ?: 25); paint() }
                            if (choice is MonthStart.SalaryNextMonth) stepper(t("income_shift_day_value", choice.day), { monthChoice = MonthStart.SalaryNextMonth((choice.day - 1).coerceIn(2, 31)); paint() }, { monthChoice = MonthStart.SalaryNextMonth((choice.day + 1).coerceIn(2, 31)); paint() })
                        }
                        val preview = choice.preview(LocalDate.now())
                        note(t("guide_month_preview", format.period(preview.label), "${format.day(preview.days.start)} – ${format.day(preview.days.endInclusive)}"))
                        note(t("guide_month_pay_example", format.day(preview.salaryExample.first), format.period(preview.salaryExample.second)))
                        if (savedLater) note(t("guide_save_later"))
                        div("actions") {
                            primaryButton(t("guide_next")) {
                                if (savedLater) { close(); next(); return@primaryButton }
                                App.launch {
                                    val patch = buildJsonObject { for ((k, v) in choice.toPatch()) if (v == null) put(k, JsonNull) else put(k, v as Int) }
                                    val ok = runCatching { Ledger.updateHousehold(patch) }.isSuccess
                                    if (ok) { close(); next() } else { savedLater = true; paint() }
                                }
                            }
                            quietButton(t("guide_skip")) { close(); next() }
                        }
                    }
                }
                paint()
            }
        }
    }

    private fun HTMLElement.stepper(label: String, previous: () -> Unit, nextDay: () -> Unit) {
        div("period") {
            appendChild(iconButton("chevron_left", t("previous_period"), "accent") { previous() })
            span("t-amount label") { text(label) }
            appendChild(iconButton("chevron_right", t("next_period"), "accent") { nextDay() })
        }
    }

    private fun openingBalancesStep(view: HouseholdView, format: Format, stepOf: Pair<Int, Int>, next: () -> Unit) {
        val accounts = view.config.accounts.filter { !it.archived }.sortedBy { it.sort }
        sheet(t("guide_accounts_title"), dismissable = false) { close ->
            stepHeader(t("guide_accounts_title"), stepOf)
            var nextButton: HTMLElement? = null
            val problems = HashMap<String, HTMLElement>()
            // Text that is not an amount is said so and holds Next back, rather than being skipped as if it had been saved.
            fun invalid(id: String) = texts[id].orEmpty().let { it.isNotBlank() && OpeningBalances.parse(it, format.currency, format.decimalStyle) == null }
            fun check() {
                for ((id, p) in problems) p.style.display = if (invalid(id)) "" else "none"
                nextButton?.let { b -> if (accounts.none { invalid(it.id) }) b.removeAttribute("disabled") else b.setAttribute("disabled", "") }
            }
            for (a in accounts) {
                val f = field(a.name, texts[a.id] ?: "", help = t("opening_balance_help")) { attr("inputmode", "decimal"); attr("placeholder", format.plain(0L)) }
                problems[a.id] = child("p", "problem") { text(t("opening_balance_invalid")) }
                f.on("input") { texts[a.id] = f.value; check() }
            }
            if (savedLater) note(t("guide_save_later"))
            div("actions") {
                nextButton = primaryButton(t("guide_next")) {
                    if (savedLater) { close(); next(); return@primaryButton }
                    App.launch {
                        var failed = false
                        for (a in accounts) {
                            val text = texts[a.id].orEmpty()
                            if (text.isBlank()) continue
                            val minor = OpeningBalances.parse(text, format.currency, format.decimalStyle) ?: continue
                            val item = (view.bundle["accounts"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { it as JsonObject }.firstOrNull { (it["id"] as? JsonPrimitive)?.content == a.id } ?: continue
                            // Typing a balance here means "this is what the account holds today".
                            val ok = runCatching { Ledger.upsert(Structure.ACCOUNT, JsonObject(item + mapOf("opening_balance_minor" to JsonPrimitive(minor), "opening_balance_date" to JsonPrimitive(LocalDate.now().toString())))) }.isSuccess
                            if (!ok) failed = true
                        }
                        if (failed) { savedLater = true; App.render() } else { close(); next() }
                    }
                }
                quietButton(t("guide_skip")) { close(); next() }
            }
            check()
        }
    }

    // ── the tour ─────────────────────────────────────────────────────────────

    /** A dimmed screen with a hole over the stop's element, a card naming it, and Back / Next (Done on the last). */
    private fun tourCard(stop: TourStop, stepOf: Pair<Int, Int>, last: Boolean, next: () -> Unit, back: () -> Unit, skip: () -> Unit) {
        val overlay = el("div", "tour") {
            id = "tour"
            attr("role", "dialog"); attr("aria-label", copyFor(stop))
        }
        document.body?.appendChild(overlay)
        window.requestAnimationFrame {
            val target = selectorFor(stop)?.let { document.querySelector(it) as? HTMLElement }
            val r = target?.getBoundingClientRect()
            val screenH = window.innerHeight.toDouble()
            val below = r == null || (screenH - r.bottom) >= r.top
            if (r != null) overlay.div("tour-hole") {
                style.left = "${r.left - 8}px"; style.top = "${r.top - 8}px"; style.width = "${r.width + 16}px"; style.height = "${r.height + 16}px"
                click { next() }
            } else overlay.div("tour-dim")
            overlay.div("tour-card " + if (r == null) "mid" else if (below) "bottom" else "top") {
                child("p", "muted") { text(t("guide_step_of", stepOf.first, stepOf.second)) }
                child("p", "tour-text") { text(copyFor(stop)) }
                div("tour-buttons") {
                    secondaryButton(t("back")) { back() }
                    primaryButton(t(if (last) "guide_done" else "guide_next")) { next() }
                }
                child("button", "text-btn tour-skip") { attr("type", "button"); text(t("guide_skip")); click { skip() } }
            }
        }
    }
}
