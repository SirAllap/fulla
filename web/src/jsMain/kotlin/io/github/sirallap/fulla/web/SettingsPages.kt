// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.core.balance.Balances
import io.github.sirallap.fulla.core.balance.SettlementPlanner
import io.github.sirallap.fulla.core.categories.CategoryUse
import io.github.sirallap.fulla.core.guide.MonthStart
import io.github.sirallap.fulla.core.model.AppliesTo
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.MoneyMode
import io.github.sirallap.fulla.core.model.Role
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.roles.Permissions
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.time.LocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.HTMLElement

private fun roleName(role: Role): String = t(when (role) { Role.OWNER -> "role_owner"; Role.ADMIN -> "role_admin"; Role.MEMBER -> "role_member" })

/** Every household this browser holds, the open one ticked, and a way to add another. */
object HouseholdsPage {
    fun build(parent: HTMLElement, view: HouseholdView) = parent.run {
        div("rows") {
            for (h in Ledger.households.sortedBy { it.name.lowercase() }) {
                listRow(h.name, context = t(if (h.mode == "connected") "shared" else "on_this_phone"),
                    end = { if (h.id == view.id) span("check") { attr("aria-label", t("current")); ui("check") } }) {
                    App.launch { Ledger.switchTo(h.id); App.closeSettings() }
                }
            }
            listRow(t("add_household"), context = t("add_household_text"), start = leadIcon("add")) { Onboarding.adding = true; App.render() }
        }
    }
}

/** The name, the currency, when the month starts, and how money works between people. */
object HouseholdPage {
    fun build(parent: HTMLElement, view: HouseholdView, format: Format, canEdit: Boolean) = parent.run {
        val h = view.config.household
        val shared = SharedPot.isShared(h)
        val me = view.config.me()
        div("rows") {
            listRow(t("household_name"), context = h.name, onClick = if (canEdit) ({
                promptSheet(t("edit"), t("household_name"), h.name) { v -> if (v.isNotEmpty()) save(buildJsonObject { put("name", v) }) }
            }) else null)
            listRow(t("currency"), context = h.currency, detail = t("currency_change_note"))
            listRow(t("period_start_day"), context = t("period_start_day_value", h.periodStartDay), detail = t("period_start_day_help"),
                onClick = if (canEdit) ({
                    promptSheet(t("edit"), t("period_start_day"), h.periodStartDay.toString(), "number", "numeric") { v ->
                        val day = v.toIntOrNull()?.coerceIn(1, 28) ?: 1
                        save(patch(if (day <= 1) MonthStart.Calendar else MonthStart.Payday(day)))
                    }
                }) else null)
            listRow(t("income_shift_day"), context = h.incomeShiftDay?.let { t("income_shift_day_value", it) } ?: t("off"), detail = t("income_shift_day_help"),
                onClick = if (canEdit) ({
                    promptSheet(t("edit"), t("income_shift_day"), h.incomeShiftDay?.toString() ?: "", "number", "numeric") { v ->
                        val day = v.toIntOrNull()?.coerceIn(2, 31)
                        save(patch(if (day == null) MonthStart.Calendar else MonthStart.SalaryNextMonth(day)))
                    }
                }) else null)
            if (view.config.activeMembers.size >= 2 || shared) {
                listRow(t("money_between_members"), context = t(if (shared) "shared_pot_card_title" else "money_mode_split_value"),
                    onClick = if (canEdit) ({ chooseMode(view, format) }) else null)
            }
        }
    }

    private fun patch(m: MonthStart): JsonObject = JsonObject(m.toPatch().mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v as Int) })

    private fun save(patch: JsonObject) = App.launch {
        try { Ledger.updateHousehold(patch); App.toast(t("web_saved")) } catch (e: Throwable) { App.toast(Remote.message(e)) }
    }

    /** The two ways money can work between people: split (who owes whom) or one shared pot. */
    fun chooseMode(view: HouseholdView, format: Format, then: () -> Unit = {}) {
        val shared = SharedPot.isShared(view.config.household)
        sheet(t("money_mode_ask_title")) { close ->
            div("rows") {
                listRow(t("money_mode_split"), context = t("money_mode_split_help"),
                    end = { if (!shared) span("check") { ui("check") } }) { close(); setSplit(shared, then) }
                listRow(t("money_mode_shared"), context = t("money_mode_shared_help"),
                    end = { if (shared) span("check") { ui("check") } }) { close(); setShared(view, format, then) }
            }
            note(t("money_mode_ask_footer"))
            div("actions") { quietButton(t("cancel")) { close() } }
        }
    }

    private fun setSplit(wasShared: Boolean, then: () -> Unit) {
        if (!wasShared) { then(); return }
        confirmSheet(t("money_between_members"), t("split_again_text"), t("money_mode_split_value")) {
            App.launch { Ledger.updateHousehold(buildJsonObject { put("money_mode", MoneyMode.SPLIT.key) }); then() }
        }
    }

    /** One pot from now on. What is owed from before is settled first when the person says so. */
    private fun setShared(view: HouseholdView, format: Format, then: () -> Unit) {
        val balances = Balances.of(view.active, view.config.members.map { it.id })
        val owed = balances.filter { it.balanceMinor > 0 }.maxByOrNull { it.balanceMinor }
        fun go(settle: Boolean) = App.launch {
            try {
                if (settle) Ledger.saveAll(SettlementPlanner.plan(balances).map { p ->
                    Transaction(randomUuid(), TransactionKind.SETTLEMENT, LocalDate.now(), p.amountMinor, paidByMemberId = p.fromMemberId,
                        toMemberId = p.toMemberId, note = t("shared_pot_started_note"), createdAt = "", clientUpdatedAt = "",
                        createdByMemberId = view.config.meMemberId)
                })
                Ledger.updateHousehold(buildJsonObject { put("money_mode", MoneyMode.SHARED.key) })
                then()
            } catch (e: Throwable) { App.toast(Remote.message(e)) }
        }
        if (owed == null) { go(false); return }
        sheet(t("shared_pot_existing_title")) { close ->
            child("p", "pad") { text(t("shared_pot_existing_text", view.memberName(owed.memberId), format.money(owed.balanceMinor))) }
            div("actions") {
                primaryButton(t("settle_now")) { close(); go(true) }
                secondaryButton(t("keep_it")) { close(); go(false) }
            }
        }
    }
}

/** The people of the household. What a role may do with someone is offered, and nothing else. */
object MembersPage {
    private var shown: Triple<String, String, String?>? = null
    private var open: List<io.github.sirallap.fulla.client.remote.Invite> = emptyList()
    private var loadedFor: String? = null
    private var error: String? = null

    fun build(parent: HTMLElement, view: HouseholdView) = parent.run {
        val me = view.config.me()
        val connected = view.connected
        val canInvite = connected && me != null && Permissions.canInvite(me, Role.MEMBER)
        if (canInvite && loadedFor != view.id) {
            loadedFor = view.id
            App.launch { open = runCatching { Ledger.invites() }.getOrDefault(emptyList()); App.render() }
        }
        div("rows") {
            for (m in view.config.members.filter { it.isActive }) {
                listRow(m.displayName + if (m.id == view.config.meMemberId) " · ${t("you")}" else "",
                    start = { badge(m.initials, m.colorIndex) }, context = roleName(m.role),
                    detail = if (!m.hasAccount) t("no_account_member") else null, end = { chevron() }) { memberSheet(view, m) }
            }
            if (me != null && Permissions.canAddMemberWithoutAccount(me)) {
                listRow(t("add_member"), context = t("add_member_text"), start = leadIcon("person_add")) {
                    promptSheet(t("add_member"), t("name"), "") { v -> if (v.isNotEmpty()) askFirst(view) { App.launch { Ledger.addMember(v) } } }
                }
            }
        }
        error?.let { child("p", "problem") { text(it) } }
        section(t("invite"))
        when {
            !connected -> div("rows") { listRow(t("share_first"), context = t("share_first_text"), start = leadIcon("share"), end = { chevron() }) { App.settingsPage = SettingsPage.SYNC; App.render() } }
            !canInvite -> note(t("invite_admins_only"))
            else -> {
                shown?.let { (link, code, forName) -> invitePanel(link, code, forName) }
                note(t("invite_text"))
                div("actions") { primaryButton(t(if (shown == null) "create_invite" else "create_another_invite")) { askFirst(view) { invite(null) } } }
                if (open.isNotEmpty()) {
                    section(t("open_invites"))
                    div("rows") {
                        for (i in open) listRow(i.code, context = i.claimMemberId?.let { view.memberName(it) } ?: roleName(Role.of(i.role)),
                            detail = t("expires", i.expiresAt.take(16).replace('T', ' ')),
                            end = {
                                child("button", "text-btn danger") {
                                    attr("type", "button"); text(t("revoke"))
                                    click { App.launch { runCatching { Ledger.revokeInvite(i.code) }; if (shown?.second == i.code) shown = null; loadedFor = null; App.render() } }
                                }
                            })
                    }
                }
            }
        }
    }

    /** Before somebody else joins, an admin whose household has not chosen yet is asked how it handles money. */
    private fun askFirst(view: HouseholdView, then: () -> Unit) {
        if (SharedPot.shouldAsk(view.config, view.config.me())) HouseholdPage.chooseMode(view, Format(view.config), then) else then()
    }

    private fun invite(claim: io.github.sirallap.fulla.core.model.Member?) = App.launch {
        error = null
        try {
            val created = Ledger.invite("member", claim?.id)
            val endpoint = Remote.endpoint!!
            shown = Triple(io.github.sirallap.fulla.client.remote.InviteLink(endpoint, created.code).toUri(), created.code, claim?.displayName)
            loadedFor = null
        } catch (e: Throwable) { error = Remote.message(e) }
        App.render()
    }

    private fun HTMLElement.invitePanel(link: String, code: String, forName: String?) {
        forName?.let { child("p", "pad") { text(t("invite_for", it)) } }
        qrCode(link, t("invite_qr_description"))
        note(t("invite_scan_hint"))
        child("p", "t-title pad code") { text(code) }
        div("actions") {
            secondaryButton(t("share_link")) {
                App.launch {
                    val nav = js("navigator")
                    try {
                        if (nav.share != undefined) { nav.share(js("({ text: link })")) }
                        else { nav.clipboard.writeText(link); App.toast(t("copied")) }
                    } catch (e: Throwable) { }
                }
            }
        }
    }

    private fun memberSheet(view: HouseholdView, target: io.github.sirallap.fulla.core.model.Member) {
        val me = view.config.me() ?: return
        val connected = view.connected
        val self = me.id == target.id
        sheet(null) { close ->
            div("rows") {
                listRow(target.displayName, start = { badge(target.initials, target.colorIndex, 40) }, context = roleName(target.role), divider = false)
                if (Permissions.canEditMember(me, target)) listRow(t("rename"), start = leadIcon("edit")) {
                    close(); promptSheet(t("rename"), t("name"), target.displayName) { v -> if (v.isNotEmpty()) App.launch { Ledger.renameMember(target.id, v) } }
                }
                if (connected && !target.hasAccount && Permissions.canInvite(me, Role.MEMBER)) listRow(t("invite_to_claim"), context = t("invite_to_claim_text", target.displayName), start = leadIcon("qr_code")) {
                    close(); askFirst(view) { invite(target) }
                }
                if (connected && !self && target.hasAccount && target.role != Role.OWNER && Permissions.canChangeRoles(me)) {
                    val toAdmin = target.role == Role.MEMBER
                    listRow(t(if (toAdmin) "make_admin" else "make_member"), context = t(if (toAdmin) "make_admin_text" else "make_member_text"), start = leadIcon("lock")) {
                        close(); attempt { Ledger.setRole(target.id, if (toAdmin) "admin" else "member") }
                    }
                }
                if (connected && !self && target.hasAccount && Permissions.canTransferOwnership(me)) listRow(t("transfer_ownership"), start = leadIcon("swap_horiz")) {
                    close(); confirmSheet(null, t("transfer_ownership_confirm", target.displayName), t("confirm")) { attempt { Ledger.transferOwnership(target.id) } }
                }
                if (Permissions.canRemove(me, target)) listRow(t("remove_member"), start = leadIcon("delete_outline")) {
                    close(); confirmSheet(null, t("remove_confirm", target.displayName), t("confirm"), danger = true) { attempt { Ledger.removeMember(target.id) } }
                }
                if (connected && self && Permissions.canLeave(me)) listRow(t("leave_household"), start = leadIcon("logout")) {
                    close(); confirmSheet(null, t("leave_confirm"), t("confirm"), danger = true) { attempt { Ledger.leave() } }
                }
            }
        }
    }

    /** Does something with the server and, when it is refused, says why in the person's words. */
    private fun attempt(block: suspend () -> Unit) = App.launch {
        try { block() } catch (e: Throwable) { App.toast(Remote.message(e)) }
    }
}

/** Categories and their subcategories, spending first and then income. */
object CategoriesPage {
    private val order = compareBy<Category>({ it.archived }, { it.sort })

    fun build(parent: HTMLElement, view: HouseholdView, canEdit: Boolean) = parent.run {
        val referenced = runCatching { CategoryUse.referenced(view.config, view.active) }.getOrNull()
        for ((title, applies) in listOf("spending" to AppliesTo.EXPENSE, "income" to AppliesTo.INCOME)) {
            section(t(title), first = applies == AppliesTo.EXPENSE)
            val list = view.config.categories.filter { it.appliesTo == applies || (applies == AppliesTo.EXPENSE && it.appliesTo == AppliesTo.BOTH) }
                .filter { referenced == null || !CategoryUse.isGone(view.config, referenced, it.id) }
            val tops = list.filter { c -> c.parentId == null || list.none { it.id == c.parentId } }.sortedWith(order)
            div("rows") {
                for (top in tops) for (c in listOf(top) + list.filter { it.parentId == top.id }.sortedWith(order)) {
                    listRow(c.name, context = if (c.archived) t("archived") else null, dim = c.archived,
                        start = {
                            style.setProperty("--tile", "var(--cat-${c.colorIndex % 12})")
                            span(if (c.parentId != null) "glyph sub" else "glyph") { categoryIcon(c.icon) }
                        },
                        onClick = if (canEdit) ({ edit(view, c.id, c) }) else null)
                }
                if (canEdit) {
                    listRow(t("add_category"), start = leadIcon("add")) { edit(view, randomUuid(), null, applies = applies) }
                    if (tops.any { it.parentId == null && !it.archived }) listRow(t("add_subcategory"), start = leadIcon("add")) { pickParent(view, applies, tops) }
                }
            }
        }
    }

    private fun pickParent(view: HouseholdView, applies: AppliesTo, tops: List<Category>) {
        sheet(t("subcategory_of")) { close ->
            div("rows") {
                for (p in tops.filter { it.parentId == null && !it.archived }) listRow(p.name,
                    start = {
                        style.setProperty("--tile", "var(--cat-${p.colorIndex % 12})")
                        span("glyph") { categoryIcon(p.icon) }
                    }) { close(); edit(view, randomUuid(), null, applies = applies, parent = p) }
            }
            div("actions") { quietButton(t("cancel")) { close() } }
        }
    }

    /** Deleting archives it, once its entries have somewhere to go; what nothing refers to any more leaves the list. */
    private fun delete(view: HouseholdView, category: Category) {
        val use = CategoryUse.of(view.config, view.active, category.id)
        val targets = CategoryUse.targetsFor(view.config, view.active, category.id)
        val others = use.budgets + use.recurring + use.fields
        var target: String? = if (use.rows > 0) targets.firstOrNull { it.id == category.parentId }?.id else null
        sheet(t(if (others > 0) "archive_item_title" else "delete_item_title", category.name)) { close ->
            lateinit var body: HTMLElement
            body = div("") {
                fun paint() {
                    body.clear()
                    body.run {
                        if (use.rows == 0 && others == 0) note(t("delete_category_unused"))
                        if (use.rows > 0) {
                            note(t("delete_category_move", use.rows))
                            chipRow(wrap = true) { for (tg in targets) chip(tg.name, tg.id == target) { target = tg.id; paint() } }
                            if (targets.isEmpty()) note(t("delete_category_no_target"), "warn")
                        }
                        if (others > 0) note(t("delete_category_kept"))
                        div("actions") {
                            val label = when { use.rows > 0 -> "delete_and_move"; others > 0 -> "archive"; else -> "delete" }
                            val go: () -> Unit = {
                                close()
                                val moveTo = target
                                App.launch {
                                    try {
                                        // Its entries go first, then it is archived: one without entries, budgets, fixed costs or fields leaves the list.
                                        if (moveTo != null) Ledger.saveAll(CategoryUse.move(view.active, category.id, moveTo))
                                        Ledger.upsert(Structure.CATEGORY, io.github.sirallap.fulla.client.wire.Wire.category(category.copy(archived = true)))
                                    } catch (e: Throwable) { App.toast(Remote.message(e)) }
                                }
                            }
                            if (others > 0 && use.rows == 0) primaryButton(t(label), true, go) else if (use.rows == 0 || target != null) dangerButton(t(label), go)
                            quietButton(t("cancel")) { close() }
                        }
                    }
                }
                paint()
            }
        }
    }

    private fun edit(view: HouseholdView, id: String, existing: Category?, applies: AppliesTo = AppliesTo.EXPENSE, parent: Category? = null) {
        var icon = existing?.icon ?: parent?.icon ?: "label"
        var archived = existing?.archived ?: false
        sheet(t("edit")) { close ->
            val name = field(t("name"), existing?.name ?: "") { attr("maxlength", "40") }
            div("icon-grid") {
                lateinit var grid: HTMLElement
                grid = this
                fun paint() {
                    grid.clear()
                    for (key in MaterialPaths.category.keys) grid.child("button", if (key == icon) "pick-icon selected" else "pick-icon") {
                        attr("type", "button"); attr("aria-label", key); attr("aria-pressed", (key == icon).toString())
                        categoryIcon(key)
                        click { icon = key; paint() }
                    }
                }
                paint()
            }
            switchRow(t("archive"), t("archive_help"), archived) { archived = it }
            val use = existing?.let { runCatching { CategoryUse.of(view.config, view.active, it.id) }.getOrNull() }
            if (use != null && use.children > 0) note(t("delete_category_children"))
            div("actions") {
                if (existing != null && use != null && use.children == 0) dangerButton(t("delete")) { close(); delete(view, existing) }
                primaryButton(t("save")) {
                    val title = name.value.trim()
                    if (title.isEmpty()) return@primaryButton
                    close()
                    val count = view.config.categories.size
                    val item = Category(id, title, existing?.appliesTo ?: parent?.appliesTo ?: applies, existing?.parentId ?: parent?.id, icon,
                        existing?.colorIndex ?: parent?.colorIndex ?: (count % 12), existing?.sort ?: count, archived)
                    App.launch { Ledger.upsert(Structure.CATEGORY, io.github.sirallap.fulla.client.wire.Wire.category(item)) }
                }
                quietButton(t("cancel")) { close() }
            }
        }
    }
}

/** The accounts, and the balance each one started with. */
object AccountsPage {
    fun build(parent: HTMLElement, view: HouseholdView, format: Format, canEdit: Boolean) = parent.run {
        div("rows") {
            for (a in view.config.accounts.sortedWith(compareBy({ it.archived }, { it.sort }))) {
                listRow(a.name, context = if (a.archived) t("archived") else null, dim = a.archived,
                    onClick = if (canEdit) ({ edit(view, format, a) }) else null)
            }
            if (canEdit) listRow(t("add_account"), start = leadIcon("add")) { edit(view, format, null) }
        }
    }

    private fun edit(view: HouseholdView, format: Format, existing: io.github.sirallap.fulla.core.model.Account?) {
        var archived = existing?.archived ?: false
        sheet(t("edit")) { close ->
            val name = field(t("name"), existing?.name ?: "") { attr("maxlength", "40") }
            val balance = field(t("opening_balance"), existing?.let { format.plain(it.openingBalanceMinor) } ?: "", help = t("opening_balance_help")) { attr("inputmode", "decimal") }
            switchRow(t("archive"), t("archive_help"), archived) { archived = it }
            div("actions") {
                primaryButton(t("save")) {
                    val title = name.value.trim()
                    if (title.isEmpty()) return@primaryButton
                    val minor = if (balance.value.isBlank()) 0L else MoneyParser.parseTyped(balance.value, format.currency, format.decimalStyle)?.takeIf { it >= 0 } ?: return@primaryButton
                    close()
                    val item = io.github.sirallap.fulla.core.model.Account(
                        existing?.id ?: randomUuid(), title, existing?.type ?: io.github.sirallap.fulla.core.model.AccountType.CHECKING,
                        minor, existing?.openingBalanceDate ?: LocalDate.now(), existing?.sort ?: view.config.accounts.size, archived)
                    App.launch { Ledger.upsert(Structure.ACCOUNT, io.github.sirallap.fulla.client.wire.Wire.account(item)) }
                }
                quietButton(t("cancel")) { close() }
            }
        }
    }
}

/** A monthly amount per category: for every month, or for one in particular. */
object BudgetsPage {
    /** null: the amount that applies to every month; otherwise "YYYY-MM". */
    private var month: String? = null

    fun build(parent: HTMLElement, view: HouseholdView, format: Format, canEdit: Boolean) = parent.run {
        val current = view.currentPeriod()
        val next = current.plusMonths(1)
        val defaults = view.config.budgets.filter { it.period == null }.associateBy { it.categoryId }
        val overrides = month?.let { m -> view.config.budgets.filter { it.period == m }.associateBy { it.categoryId } }.orEmpty()
        note(t("budgets_text"))
        chipRow {
            chip(t("every_month"), month == null) { month = null; App.render() }
            chip(format.period(current), month == current.toString()) { month = current.toString(); App.render() }
            chip(format.period(next), month == next.toString()) { month = next.toString(); App.render() }
        }
        month?.let { m ->
            note(t("month_budget_help"))
            if (canEdit) {
                val from = io.github.sirallap.fulla.core.time.YearMonth.parse(m).minusMonths(1)
                div("rows") { listRow(t("copy_budgets_from", format.period(from)), start = leadIcon("content_copy")) { App.launch { Ledger.copyBudgets(from.toString(), m) } } }
            }
        }
        div("rows") {
            for (c in view.config.categories.filter { !it.archived && it.appliesTo != AppliesTo.INCOME && it.parentId == null }.sortedBy { it.sort }) {
                val own = if (month == null) defaults[c.id] else overrides[c.id]
                val inherited = if (month != null && own == null) defaults[c.id] else null
                listRow(c.name, context = inherited?.let { t("from_default") },
                    start = {
                        style.setProperty("--tile", "var(--cat-${c.colorIndex % 12})")
                        span("glyph") { categoryIcon(c.icon) }
                    },
                    end = { amountText((own ?: inherited)?.let { format.money(it.amountMinor) } ?: "—", if (own != null) "" else "muted") },
                    onClick = if (canEdit) ({
                        promptSheet(c.name, t(if (month == null) "monthly_budget" else "budget_for_month"), own?.let { format.plain(it.amountMinor) } ?: "", inputMode = "decimal") { v ->
                            val minor = MoneyParser.parseTyped(v, format.currency, format.decimalStyle) ?: return@promptSheet
                            val item = buildJsonObject {
                                put("id", own?.id ?: randomUuid()); put("category_id", c.id); put("period", month); put("amount_minor", minor)
                            }
                            App.launch { Ledger.upsert(Structure.BUDGET, item) }
                        }
                    }) else null)
            }
        }
    }
}
