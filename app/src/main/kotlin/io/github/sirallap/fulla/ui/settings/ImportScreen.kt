// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.local.ImportProfiles
import io.github.sirallap.fulla.client.local.SavedProfile
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.importers.AmountColumns
import io.github.sirallap.fulla.core.importers.ProposedTransaction
import androidx.compose.material.icons.outlined.BookmarkBorder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import io.github.sirallap.fulla.core.importers.CsvImporter
import io.github.sirallap.fulla.core.importers.Csv
import io.github.sirallap.fulla.core.importers.ColumnGuess
import io.github.sirallap.fulla.core.importers.ColumnRole
import io.github.sirallap.fulla.core.importers.ImportNames
import io.github.sirallap.fulla.core.importers.ImportPlan
import io.github.sirallap.fulla.core.text.normalizeName
import io.github.sirallap.fulla.core.importers.ImportProfile
import io.github.sirallap.fulla.core.importers.OfxImporter
import io.github.sirallap.fulla.core.importers.QifImporter
import io.github.sirallap.fulla.core.importers.StatementResult
import io.github.sirallap.fulla.core.importers.TextDecoding
import io.github.sirallap.fulla.core.model.AppliesTo
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.core.money.DecimalStyle
import io.github.sirallap.fulla.core.sync.SyncEngine
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.Chip
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

private enum class Format { CSV, OFX, QIF }

private val DATE_FORMATS = listOf("dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "dd.MM.yyyy", "dd-MM-yyyy", "d/M/yyyy")

/**
 * A bank statement into the household: pick a file, say which account it is
 * and (for CSV) which column is which, check the list, import. Nothing is
 * sent anywhere to be read; the file is parsed here. Importing the same file
 * twice changes nothing, because each line's id comes from its content.
 */
@Composable
fun ImportScreen(view: HouseholdView, change: Change) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    val f = view.formats
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var format by remember { mutableStateOf(Format.CSV) }
    var account by remember { mutableStateOf(view.config.accounts.firstOrNull { !it.archived }?.id) }
    var dateColumn by remember { mutableStateOf(0) }
    var amountColumn by remember { mutableStateOf(1) }
    var descriptionColumn by remember { mutableStateOf(2) }
    var dateFormat by remember { mutableStateOf(DATE_FORMATS.first()) }
    var decimal by remember { mutableStateOf(f.decimalStyle) }
    var done by remember { mutableStateOf<Int?>(null) }
    var skipRows by remember { mutableStateOf(0) }
    var categoryColumn by remember { mutableStateOf<Int?>(null) }
    var kindColumn by remember { mutableStateOf<Int?>(null) }
    var incomeValues by remember { mutableStateOf<List<String>>(emptyList()) }
    var payerColumn by remember { mutableStateOf<Int?>(null) }
    var accountColumn by remember { mutableStateOf<Int?>(null) }
    var fixedColumn by remember { mutableStateOf<Int?>(null) }
    var fixedValues by remember { mutableStateOf<List<String>>(emptyList()) }
    var profileId by remember { mutableStateOf<String?>(null) }
    var overrides by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var choosing by remember { mutableStateOf<ProposedTransaction?>(null) }
    val profiles = remember(view) { Wire.list(view.state.bundle["import_profiles"]).mapNotNull(ImportProfiles::fromJson) }
    fun use(p: SavedProfile) {
        profileId = p.id
        p.accountId?.let { account = it }
        dateColumn = p.profile.dateColumn
        amountColumn = (p.profile.amount as? AmountColumns.Single)?.column ?: amountColumn
        descriptionColumn = p.profile.descriptionColumns.firstOrNull() ?: descriptionColumn
        dateFormat = p.profile.dateFormat
        decimal = p.profile.decimal
        skipRows = p.profile.skipRows
        categoryColumn = p.profile.categoryColumn
        kindColumn = p.profile.kindColumn
        incomeValues = p.profile.incomeValues
        payerColumn = p.profile.payerColumn
        accountColumn = p.profile.accountColumn
        fixedColumn = p.profile.fixedColumn
        fixedValues = p.profile.fixedValues
    }

    /** A new file starts from what its header and first values suggest. */
    fun guess(read: ByteArray) {
        val rows = CsvImporter.preview(read, rows = 30)
        val header = rows.firstOrNull() ?: return
        val body = rows.drop(1)
        val found = ColumnGuess.columns(header)
        fun column(i: Int?) = i?.let { c -> body.mapNotNull { it.getOrNull(c) } }.orEmpty()
        found[ColumnRole.DATE]?.let { dateColumn = it }
        found[ColumnRole.AMOUNT]?.let { amountColumn = it }
        found[ColumnRole.DESCRIPTION]?.let { descriptionColumn = it }
        categoryColumn = found[ColumnRole.CATEGORY]
        kindColumn = found[ColumnRole.KIND]
        payerColumn = found[ColumnRole.PAYER]
        accountColumn = found[ColumnRole.ACCOUNT]
        ColumnGuess.dateFormat(column(dateColumn), DATE_FORMATS)?.let { dateFormat = it }
        ColumnGuess.decimal(column(amountColumn))?.let { decimal = it }
        incomeValues = ColumnGuess.incomeValues(column(kindColumn).distinct())
        fixedColumn = found[ColumnRole.RECURRENCE]
        fixedValues = ColumnGuess.fixedValues(column(fixedColumn).distinct())
        profileId = null
    }

    var unreadable by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            // Failing in silence looked like the button did nothing.
            if (read == null || read.isEmpty()) { unreadable = true; return@launch }
            unreadable = false
            bytes = read
            done = null
            overrides = emptyMap()
            format = when {
                OfxImporter.detect(read) > 0.5 -> Format.OFX
                QifImporter.detect(read) > 0.5 -> Format.QIF
                else -> Format.CSV
            }
            if (format == Format.CSV) guess(read)
        }
    }

    Column {
        if (bytes == null) {
            EmptyState(Icons.Outlined.FileUpload, stringResource(R.string.import_title), stringResource(R.string.import_text)) {
                PrimaryButton(stringResource(R.string.choose_file), { pick.launch(arrayOf("*/*")) })
            }
            done?.let { Text(stringResource(R.string.imported_count, it), style = FullaType.body, color = c.ink, modifier = Modifier.padding(20.dp)) }
            if (unreadable) Text(stringResource(R.string.import_not_readable), style = FullaType.body, color = c.danger, modifier = Modifier.padding(20.dp))
            return@Column
        }
        val data = bytes!!
        Section(stringResource(R.string.into_account), top = 8.dp)
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in view.config.accounts.filter { !it.archived }) Chip(a.name, a.id == account, { account = a.id })
        }

        val preview = remember(data) { if (format == Format.CSV) CsvImporter.preview(data) else emptyList() }
        if (format == Format.CSV && profiles.isNotEmpty()) {
            Section(stringResource(R.string.saved_mappings))
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in profiles) Chip(p.name, p.id == profileId, { use(p) })
            }
        }
        if (format == Format.CSV && preview.isNotEmpty()) {
            val header = preview.first()
            for ((label, selected, set) in listOf(
                Triple(R.string.column_date, dateColumn) { i: Int -> dateColumn = i },
                Triple(R.string.column_amount, amountColumn) { i: Int -> amountColumn = i },
                Triple(R.string.column_description, descriptionColumn) { i: Int -> descriptionColumn = i },
            )) {
                Section(stringResource(label))
                FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    header.forEachIndexed { i, name -> Chip(name.ifBlank { "#${i + 1}" }.take(24), i == selected, { set(i) }) }
                }
            }
            // Columns a file may or may not have: "None" first.
            val optional = buildList {
                add(Triple(R.string.column_kind, kindColumn) { i: Int? -> kindColumn = i; incomeValues = emptyList() })
                add(Triple(R.string.column_category, categoryColumn) { i: Int? -> categoryColumn = i })
                if (view.config.activeMembers.size >= 2) add(Triple(R.string.column_payer, payerColumn) { i: Int? -> payerColumn = i })
                if (view.config.accounts.count { !it.archived } >= 2) add(Triple(R.string.column_account, accountColumn) { i: Int? -> accountColumn = i })
                add(Triple(R.string.column_fixed, fixedColumn) { i: Int? -> fixedColumn = i; fixedValues = emptyList() })
            }
            for ((label, selected, set) in optional) {
                Section(stringResource(label))
                FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.column_none), selected == null, { set(null) })
                    header.forEachIndexed { i, name -> Chip(name.ifBlank { "#${i + 1}" }.take(24), i == selected, { set(i) }) }
                }
                // Which of the column's values mean income, or a fixed amount.
                val choice = when {
                    label == R.string.column_kind && selected != null ->
                        Triple(R.string.income_values, incomeValues) { v: List<String> -> incomeValues = v }
                    label == R.string.column_fixed && selected != null ->
                        Triple(R.string.fixed_values, fixedValues) { v: List<String> -> fixedValues = v }
                    else -> null
                }
                if (choice != null) {
                    val (question, chosen, choose) = choice
                    val values = remember(data, selected, skipRows) {
                        CsvImporter.values(data, ImportProfile(delimiter = Csv.guessDelimiter(TextDecoding.decode(data)), skipRows = skipRows,
                            dateColumn = 0, dateFormat = dateFormat, amount = AmountColumns.Single(0)), selected!!)
                    }
                    Text(stringResource(question), style = FullaType.secondary, color = c.inkMuted,
                        modifier = Modifier.padding(horizontal = 20.dp))
                    FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (v in values) {
                            val on = chosen.any { it.normalizeName() == v.normalizeName() }
                            Chip(v.take(24), on, {
                                choose(if (on) chosen.filter { it.normalizeName() != v.normalizeName() } else chosen + v)
                            })
                        }
                    }
                }
            }
            Section(stringResource(R.string.date_format))
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in DATE_FORMATS) Chip(d, d == dateFormat, { dateFormat = d })
            }
            Section(stringResource(R.string.decimal_separator))
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("1.234,56", decimal == DecimalStyle.COMMA, { decimal = DecimalStyle.COMMA })
                Chip("1,234.56", decimal == DecimalStyle.DOT, { decimal = DecimalStyle.DOT })
            }
        } else if (format == Format.QIF) {
            Section(stringResource(R.string.date_format))
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in DATE_FORMATS) Chip(d, d == dateFormat, { dateFormat = d })
            }
        }

        fun currentProfile(text: String) = ImportProfile(
            delimiter = Csv.guessDelimiter(text), skipRows = skipRows, dateColumn = dateColumn, dateFormat = dateFormat,
            amount = AmountColumns.Single(amountColumn), decimal = decimal, descriptionColumns = listOf(descriptionColumn),
            categoryColumn = categoryColumn, kindColumn = kindColumn, incomeValues = incomeValues,
            payerColumn = payerColumn, accountColumn = accountColumn, fixedColumn = fixedColumn, fixedValues = fixedValues,
        )
        val result: StatementResult? = remember(data, format, dateColumn, amountColumn, descriptionColumn, dateFormat, decimal, skipRows,
            categoryColumn, kindColumn, incomeValues, payerColumn, accountColumn, fixedColumn, fixedValues) {
            runCatching {
                when (format) {
                    Format.OFX -> OfxImporter.read(data, f.currency)
                    Format.QIF -> QifImporter.read(data, f.currency, dateFormat, decimal)
                    Format.CSV -> {
                        val text = TextDecoding.decode(data)
                        CsvImporter.read(data, currentProfile(text), f.currency)
                    }
                }
            }.getOrNull()
        }
        val accountId = account
        val proposals = remember(result, accountId, view) {
            if (result == null || accountId == null) emptyList() else {
                val uncategorized = view.config.categories.firstOrNull { it.appliesTo == AppliesTo.BOTH }
                    ?: view.config.categories.first()
                ImportPlan.propose(
                    householdId = view.id, accountId = accountId, lines = result.lines, rules = view.state.rules,
                    uncategorizedExpenseId = uncategorized.id, uncategorizedIncomeId = uncategorized.id,
                    payerMemberId = view.config.meMemberId ?: view.config.activeMembers.first().id,
                    defaultSplit = view.config.activeMembers.takeIf { it.size >= 2 }?.let { m -> Split.Equal(m.map { it.id }) },
                    existingIds = view.rows.map { it.id }.toSet(), now = SyncEngine.iso(Instant.now()),
                    names = ImportNames(view.config.categories, view.config.members, view.config.accounts),
                )
            }
        }
        val fresh = proposals.filter { !it.alreadyImported }
        choosing?.let { p ->
            CategoryChooser(view, p, onDismiss = { choosing = null }) { categoryId, pattern ->
                overrides = overrides + (p.transaction.id to categoryId)
                if (pattern != null) {
                    val rule = buildJsonObject {
                        put("id", UUID.randomUUID().toString()); put("pattern", pattern)
                        put("action", buildJsonObject { put("category_id", categoryId) })
                        put("sort", view.state.rules.size); put("active", true)
                    }
                    change { api -> container.ledger.upsert(view.id, Structure.RULE, rule, api) }
                }
            }
        }
        Section(stringResource(R.string.lines_found, proposals.size, fresh.size))
        // Written with the import: new categories, and existing ones widened to both kinds.
        val created = remember(proposals) { ImportPlan.newCategories(proposals) }
        val brandNew = created.filter { c -> view.config.categories.none { it.id == c.id } }
        val accountsMade = remember(proposals) { ImportPlan.newAccounts(proposals) }
        if (accountsMade.isNotEmpty()) {
            Text(stringResource(R.string.new_accounts, accountsMade.joinToString(", ") { it.name }), style = FullaType.secondary,
                color = c.inkMuted, modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (brandNew.isNotEmpty()) {
            Text(stringResource(R.string.new_categories, brandNew.joinToString(", ") { it.name }), style = FullaType.secondary,
                color = c.inkMuted, modifier = Modifier.padding(horizontal = 20.dp))
        }
        result?.skipped?.takeIf { it.isNotEmpty() }?.let {
            Text(stringResource(R.string.lines_skipped, it.size), style = FullaType.secondary, color = c.warning, modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (format == Format.CSV && result != null && result.lines.isNotEmpty() && profileId == null) {
            val name = stringResource(R.string.mapping_name, view.accountName(account) ?: "CSV")
            ListRow(stringResource(R.string.save_mapping), context = stringResource(R.string.save_mapping_text), icon = Icons.Outlined.BookmarkBorder, onClick = {
                val saved = SavedProfile(UUID.randomUUID().toString(), name, account, currentProfile(TextDecoding.decode(data)))
                profileId = saved.id
                change { api -> container.ledger.upsert(view.id, Structure.IMPORT_PROFILE, ImportProfiles.toJson(saved), api) }
            })
        }
        for (p in proposals.take(80)) {
            val categoryId = overrides[p.transaction.id] ?: p.transaction.categoryId
            val categoryName = view.categoryName(categoryId) ?: created.firstOrNull { it.id == categoryId }?.name
            ListRow(p.transaction.note.ifBlank { categoryName ?: "" }, context = listOfNotNull(f.day(p.transaction.date),
                categoryName.takeIf { p.transaction.note.isNotBlank() }).joinToString(" · "),
                titleColor = if (p.alreadyImported) c.inkMuted else c.ink,
                onClick = if (p.alreadyImported || !p.transaction.kind.isCategorised) null else ({ choosing = p }),
                detail = when {
                    p.alreadyImported -> stringResource(R.string.already_imported)
                    p.matchedRule != null -> stringResource(R.string.by_rule, p.matchedRule!!.pattern)
                    else -> null
                },
                end = { AmountText(f.money(p.line.amountMinor, signed = true), color = if (p.line.amountMinor > 0) c.moneyIn else c.ink) })
        }
        Column(Modifier.padding(20.dp)) {
            PrimaryButton(stringResource(R.string.import_n, fresh.size), {
                // In one shared pot an imported expense is its payer's alone, like any other new row.
                val rows = fresh.map { p -> overrides[p.transaction.id]?.let { p.transaction.copy(categoryId = it) } ?: p.transaction }
                    .map { SharedPot.forNew(it, view.config.household) }
                val used = rows.mapNotNull { it.categoryId }.toSet()
                // Categories first: a row must never reach the server before the category it names.
                val accountsUsed = rows.flatMap { listOfNotNull(it.accountId, it.toAccountId) }.toSet()
                importing = true
                change { api ->
                    try {
                        for (account in accountsMade.filter { it.id in accountsUsed }) {
                            container.ledger.upsert(view.id, Structure.ACCOUNT, Wire.account(account), api)
                        }
                        for (category in created.filter { it.id in used }) {
                            container.ledger.upsert(view.id, Structure.CATEGORY, Wire.category(category), api)
                        }
                        container.ledger.saveAll(view.id, rows)
                        done = rows.size
                        bytes = null
                    } finally {
                        importing = false
                    }
                }
            }, enabled = fresh.isNotEmpty(), busy = importing)
        }
    }
}

/** Pick the category of one imported line, and optionally remember it as a rule. */
@Composable
private fun CategoryChooser(view: HouseholdView, p: ProposedTransaction, onDismiss: () -> Unit, onPick: (String, String?) -> Unit) {
    val suggested = remember(p) { p.line.description.trim().split(Regex("\\s+")).take(2).joinToString(" ").take(40) }
    var pattern by remember { mutableStateOf(suggested) }
    var keep by remember { mutableStateOf(true) }
    var chosen by remember { mutableStateOf(p.transaction.categoryId) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.transaction.note, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (cat in view.config.categories.filter { !it.archived && it.appliesTo.allows(p.transaction.kind) }) {
                        Chip(cat.name, cat.id == chosen, { chosen = cat.id })
                    }
                }
                io.github.sirallap.fulla.ui.components.SwitchRow(stringResource(R.string.remember_rule), stringResource(R.string.remember_rule_text), keep) { keep = it }
                if (keep) {
                    androidx.compose.material3.OutlinedTextField(pattern, { pattern = it.take(100) }, label = { Text(stringResource(R.string.rule_pattern)) }, singleLine = true)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = chosen != null && (!keep || pattern.isNotBlank()), onClick = {
                onPick(chosen!!, if (keep) pattern.trim() else null); onDismiss()
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
