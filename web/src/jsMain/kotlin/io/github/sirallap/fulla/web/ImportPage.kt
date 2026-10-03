// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.local.ImportProfiles
import io.github.sirallap.fulla.client.local.SavedProfile
import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.importers.AmountColumns
import io.github.sirallap.fulla.core.importers.ColumnGuess
import io.github.sirallap.fulla.core.importers.ColumnRole
import io.github.sirallap.fulla.core.importers.Csv
import io.github.sirallap.fulla.core.importers.CsvImporter
import io.github.sirallap.fulla.core.importers.ImportNames
import io.github.sirallap.fulla.core.importers.ImportPlan
import io.github.sirallap.fulla.core.importers.ImportProfile
import io.github.sirallap.fulla.core.importers.OfxImporter
import io.github.sirallap.fulla.core.importers.ProposedTransaction
import io.github.sirallap.fulla.core.importers.QifImporter
import io.github.sirallap.fulla.core.importers.StatementResult
import io.github.sirallap.fulla.core.importers.TextDecoding
import io.github.sirallap.fulla.core.model.AppliesTo
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.money.DecimalStyle
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.sync.SyncEngine
import io.github.sirallap.fulla.core.text.normalizeName
import io.github.sirallap.fulla.core.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * A bank statement into the household: pick a file, say which account it is and (for CSV) which column is which,
 * check the list, import. The file is parsed here and goes nowhere. Importing the same file twice changes nothing,
 * because each line's id comes from its content.
 */
object ImportPage {
    private enum class Kind { CSV, OFX, QIF }

    private val DATE_FORMATS = listOf("dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "dd.MM.yyyy", "dd-MM-yyyy", "d/M/yyyy")

    private var bytes: ByteArray? = null
    private var kind = Kind.CSV
    private var account: String? = null
    private var dateColumn = 0
    private var amountColumn = 1
    private var descriptionColumn = 2
    private var dateFormat = DATE_FORMATS.first()
    private var decimal: DecimalStyle? = null
    private var done: Int? = null
    private var skipRows = 0
    private var categoryColumn: Int? = null
    private var kindColumn: Int? = null
    private var incomeValues: List<String> = emptyList()
    private var payerColumn: Int? = null
    private var accountColumn: Int? = null
    private var fixedColumn: Int? = null
    private var fixedValues: List<String> = emptyList()
    private var profileId: String? = null
    private var overrides: Map<String, String> = emptyMap()
    private var unreadable = false
    private var importing = false

    private fun use(p: SavedProfile) {
        profileId = p.id
        p.accountId?.let { account = it }
        dateColumn = p.profile.dateColumn
        amountColumn = (p.profile.amount as? AmountColumns.Single)?.column ?: amountColumn
        descriptionColumn = p.profile.descriptionColumns.firstOrNull() ?: descriptionColumn
        dateFormat = p.profile.dateFormat; decimal = p.profile.decimal; skipRows = p.profile.skipRows
        categoryColumn = p.profile.categoryColumn; kindColumn = p.profile.kindColumn; incomeValues = p.profile.incomeValues
        payerColumn = p.profile.payerColumn; accountColumn = p.profile.accountColumn
        fixedColumn = p.profile.fixedColumn; fixedValues = p.profile.fixedValues
    }

    /** A new file starts from what its header and first values suggest. */
    private fun guess(read: ByteArray) {
        val rows = CsvImporter.preview(read, rows = 30)
        val header = rows.firstOrNull() ?: return
        val body = rows.drop(1)
        val found = ColumnGuess.columns(header)
        fun column(i: Int?) = i?.let { c -> body.mapNotNull { it.getOrNull(c) } }.orEmpty()
        found[ColumnRole.DATE]?.let { dateColumn = it }
        found[ColumnRole.AMOUNT]?.let { amountColumn = it }
        found[ColumnRole.DESCRIPTION]?.let { descriptionColumn = it }
        categoryColumn = found[ColumnRole.CATEGORY]; kindColumn = found[ColumnRole.KIND]
        payerColumn = found[ColumnRole.PAYER]; accountColumn = found[ColumnRole.ACCOUNT]
        ColumnGuess.dateFormat(column(dateColumn), DATE_FORMATS)?.let { dateFormat = it }
        ColumnGuess.decimal(column(amountColumn))?.let { decimal = it }
        incomeValues = ColumnGuess.incomeValues(column(kindColumn).distinct())
        fixedColumn = found[ColumnRole.RECURRENCE]
        fixedValues = ColumnGuess.fixedValues(column(fixedColumn).distinct())
        profileId = null
    }

    fun build(parent: HTMLElement, view: HouseholdView, format: Format) = parent.run {
        val config = view.config
        if (account == null || config.account(account)?.archived != false) account = config.accounts.firstOrNull { !it.archived }?.id
        val style = decimal ?: format.decimalStyle
        val data = bytes
        if (data == null) {
            emptyState("file_upload", t("import_title"), t("import_text"))
            val picker = input("file", cls = "hidden") { accept = ".csv,.ofx,.qfx,.qif,.txt,text/*,application/*" }
            picker.on("change") { pick(picker) }
            div("actions") { primaryButton(t("choose_file")) { picker.click() } }
            done?.let { child("p", "pad") { text(t("imported_count", it)) } }
            if (unreadable) child("p", "problem") { text(t("import_not_readable")) }
            return@run
        }
        section(t("into_account"), first = true)
        chipRow(wrap = true) { for (a in config.accounts.filter { !it.archived }) chip(a.name, a.id == account) { account = a.id; App.render() } }

        val profiles = io.github.sirallap.fulla.client.wire.Wire.list(view.bundle["import_profiles"]).mapNotNull(ImportProfiles::fromJson)
        val preview = if (kind == Kind.CSV) CsvImporter.preview(data) else emptyList()
        if (kind == Kind.CSV && profiles.isNotEmpty()) {
            section(t("saved_mappings"))
            chipRow(wrap = true) { for (p in profiles) chip(p.name, p.id == profileId) { use(p); App.render() } }
        }
        if (kind == Kind.CSV && preview.isNotEmpty()) {
            val header = preview.first()
            fun columns(label: String, selected: Int?, optional: Boolean, set: (Int?) -> Unit) {
                section(t(label))
                chipRow(wrap = true) {
                    if (optional) chip(t("column_none"), selected == null) { set(null); App.render() }
                    header.forEachIndexed { i, name -> chip(name.ifBlank { "#${i + 1}" }.take(24), i == selected) { set(i); App.render() } }
                }
            }
            columns("column_date", dateColumn, false) { dateColumn = it ?: dateColumn }
            columns("column_amount", amountColumn, false) { amountColumn = it ?: amountColumn }
            columns("column_description", descriptionColumn, false) { descriptionColumn = it ?: descriptionColumn }
            columns("column_kind", kindColumn, true) { kindColumn = it; incomeValues = emptyList() }
            if (kindColumn != null) valueChoice("income_values", data, kindColumn!!, incomeValues) { incomeValues = it }
            columns("column_category", categoryColumn, true) { categoryColumn = it }
            if (config.activeMembers.size >= 2) columns("column_payer", payerColumn, true) { payerColumn = it }
            if (config.accounts.count { !it.archived } >= 2) columns("column_account", accountColumn, true) { accountColumn = it }
            columns("column_fixed", fixedColumn, true) { fixedColumn = it; fixedValues = emptyList() }
            if (fixedColumn != null) valueChoice("fixed_values", data, fixedColumn!!, fixedValues) { fixedValues = it }
            section(t("date_format"))
            chipRow(wrap = true) { for (d in DATE_FORMATS) chip(d, d == dateFormat) { dateFormat = d; App.render() } }
            section(t("decimal_separator"))
            chipRow(wrap = true) {
                chip("1.234,56", style == DecimalStyle.COMMA) { decimal = DecimalStyle.COMMA; App.render() }
                chip("1,234.56", style == DecimalStyle.DOT) { decimal = DecimalStyle.DOT; App.render() }
            }
        } else if (kind == Kind.QIF) {
            section(t("date_format"))
            chipRow(wrap = true) { for (d in DATE_FORMATS) chip(d, d == dateFormat) { dateFormat = d; App.render() } }
        }

        fun currentProfile(text: String) = ImportProfile(
            delimiter = Csv.guessDelimiter(text), skipRows = skipRows, dateColumn = dateColumn, dateFormat = dateFormat,
            amount = AmountColumns.Single(amountColumn), decimal = style, descriptionColumns = listOf(descriptionColumn),
            categoryColumn = categoryColumn, kindColumn = kindColumn, incomeValues = incomeValues,
            payerColumn = payerColumn, accountColumn = accountColumn, fixedColumn = fixedColumn, fixedValues = fixedValues,
        )
        val result: StatementResult? = runCatching {
            when (kind) {
                Kind.OFX -> OfxImporter.read(data, format.currency)
                Kind.QIF -> QifImporter.read(data, format.currency, dateFormat, style)
                Kind.CSV -> CsvImporter.read(data, currentProfile(TextDecoding.decode(data)), format.currency)
            }
        }.getOrNull()
        val accountId = account
        val rules = io.github.sirallap.fulla.client.wire.Wire.rules(view.bundle)
        val proposals = if (result == null || accountId == null) emptyList() else {
            val uncategorized = config.categories.firstOrNull { it.appliesTo == AppliesTo.BOTH } ?: config.categories.first()
            ImportPlan.propose(
                householdId = view.id, accountId = accountId, lines = result.lines, rules = rules,
                uncategorizedExpenseId = uncategorized.id, uncategorizedIncomeId = uncategorized.id,
                payerMemberId = config.meMemberId ?: config.activeMembers.first().id,
                defaultSplit = config.activeMembers.takeIf { it.size >= 2 }?.let { m -> Split.Equal(m.map { it.id }) },
                existingIds = view.rows.map { it.id }.toSet(), now = SyncEngine.iso(Instant.now()),
                names = ImportNames(config.categories, config.members, config.accounts),
            )
        }
        val fresh = proposals.filter { !it.alreadyImported }
        section(t("lines_found", proposals.size, fresh.size))
        // Written with the import: new categories, and existing ones widened to both kinds.
        val created = ImportPlan.newCategories(proposals)
        val brandNew = created.filter { c -> config.categories.none { it.id == c.id } }
        val accountsMade = ImportPlan.newAccounts(proposals)
        if (accountsMade.isNotEmpty()) note(t("new_accounts", accountsMade.joinToString(", ") { it.name }))
        if (brandNew.isNotEmpty()) note(t("new_categories", brandNew.joinToString(", ") { it.name }))
        result?.skipped?.takeIf { it.isNotEmpty() }?.let { note(t("lines_skipped", it.size), "warn") }
        if (kind == Kind.CSV && result != null && result.lines.isNotEmpty() && profileId == null) div("rows") {
            val name = t("mapping_name", config.account(account)?.name ?: "CSV")
            listRow(t("save_mapping"), context = t("save_mapping_text"), start = leadIcon("check")) {
                val saved = SavedProfile(randomUuid(), name, account, currentProfile(TextDecoding.decode(data)))
                profileId = saved.id
                App.launch { Ledger.upsert(Structure.IMPORT_PROFILE, ImportProfiles.toJson(saved)) }
            }
        }
        div("rows") {
            for (p in proposals.take(80)) {
                val categoryId = overrides[p.transaction.id] ?: p.transaction.categoryId
                val categoryName = config.category(categoryId)?.name ?: created.firstOrNull { it.id == categoryId }?.name
                listRow(p.transaction.note.ifBlank { categoryName ?: "" }, dim = p.alreadyImported,
                    context = listOfNotNull(format.day(p.transaction.date), categoryName.takeIf { p.transaction.note.isNotBlank() }).joinToString(" · "),
                    detail = when { p.alreadyImported -> t("already_imported"); p.matchedRule != null -> t("by_rule", p.matchedRule!!.pattern); else -> null },
                    end = { amountText(format.money(p.line.amountMinor, signed = true), if (p.line.amountMinor > 0) "in" else "") },
                    onClick = if (p.alreadyImported || !p.transaction.kind.isCategorised) null else ({ choose(view, p) }))
            }
        }
        div("actions") {
            primaryButton(t("import_n", fresh.size), enabled = fresh.isNotEmpty() && !importing) {
                // In one shared pot an imported expense is its payer's alone, like any other new row.
                val rows = fresh.map { p -> overrides[p.transaction.id]?.let { p.transaction.copy(categoryId = it) } ?: p.transaction }
                    .map { SharedPot.forNew(it, config.household) }
                val used = rows.mapNotNull { it.categoryId }.toSet()
                val accountsUsed = rows.flatMap { listOfNotNull(it.accountId, it.toAccountId) }.toSet()
                importing = true; App.render()
                App.launch {
                    try {
                        // Categories first: a row must never reach the server before the category it names.
                        for (a in accountsMade.filter { it.id in accountsUsed }) Ledger.upsert(Structure.ACCOUNT, Wire.account(a))
                        for (c in created.filter { it.id in used }) Ledger.upsert(Structure.CATEGORY, Wire.category(c))
                        Ledger.saveAll(rows)
                        done = rows.size; bytes = null
                    } catch (e: Throwable) { App.toast(Remote.message(e)) }
                    importing = false
                    App.render()
                }
            }
            quietButton(t("cancel")) { bytes = null; App.render() }
        }
    }

    private fun HTMLElement.valueChoice(question: String, data: ByteArray, column: Int, chosen: List<String>, choose: (List<String>) -> Unit) {
        val values = CsvImporter.values(data, ImportProfile(delimiter = Csv.guessDelimiter(TextDecoding.decode(data)), skipRows = skipRows,
            dateColumn = 0, dateFormat = dateFormat, amount = AmountColumns.Single(0)), column)
        note(t(question))
        chipRow(wrap = true) {
            for (v in values) {
                val on = chosen.any { it.normalizeName() == v.normalizeName() }
                chip(v.take(24), on) { choose(if (on) chosen.filter { it.normalizeName() != v.normalizeName() } else chosen + v); App.render() }
            }
        }
    }

    private fun pick(input: HTMLInputElement) {
        val file = input.files?.item(0) ?: return
        App.launch {
            val read = runCatching { readBytes(file) }.getOrNull()
            if (read == null || read.isEmpty()) { unreadable = true; App.render(); return@launch }
            unreadable = false; bytes = read; done = null; overrides = emptyMap()
            kind = when { OfxImporter.detect(read) > 0.5 -> Kind.OFX; QifImporter.detect(read) > 0.5 -> Kind.QIF; else -> Kind.CSV }
            if (kind == Kind.CSV) guess(read)
            App.render()
        }
    }

    /** Pick the category of one imported line, and optionally remember it as a rule. */
    private fun choose(view: HouseholdView, p: ProposedTransaction) {
        var pattern = p.line.description.trim().split(Regex("\\s+")).take(2).joinToString(" ").take(40)
        var keep = true
        var chosen = overrides[p.transaction.id] ?: p.transaction.categoryId
        sheet(p.transaction.note) { close ->
            val body = div("")
            body.run {
                fun paint() {
                    body.clear()
                    body.run {
                        chipRow(wrap = true) {
                            for (cat in view.config.categories.filter { !it.archived && it.appliesTo.allows(p.transaction.kind) }) chip(cat.name, cat.id == chosen) { chosen = cat.id; paint() }
                        }
                        div("rows") { switchRow(t("remember_rule"), t("remember_rule_text"), keep) { keep = it; paint() } }
                        if (keep) { val f = field(t("rule_pattern"), pattern) { attr("maxlength", "100") }; f.on("input") { pattern = f.value } }
                        div("actions") {
                            primaryButton(t("done"), enabled = chosen != null && (!keep || pattern.isNotBlank())) {
                                close()
                                val categoryId = chosen!!
                                overrides = overrides + (p.transaction.id to categoryId)
                                if (keep) {
                                    val rule = buildJsonObject {
                                        put("id", randomUuid()); put("pattern", pattern.trim())
                                        put("action", buildJsonObject { put("category_id", categoryId) })
                                        put("sort", io.github.sirallap.fulla.client.wire.Wire.rules(view.bundle).size); put("active", true)
                                    }
                                    App.launch { Ledger.upsert(Structure.RULE, rule) }
                                } else App.render()
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
