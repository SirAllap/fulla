// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.history

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.sirallap.fulla.ui.components.listEndPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.analytics.DayTotals
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.core.text.normalizeName
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.ChipRow
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.CardItem
import io.github.sirallap.fulla.ui.components.CardSlot
import io.github.sirallap.fulla.ui.components.MenuItem
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.TabHeader
import io.github.sirallap.fulla.ui.entry.CategoryIcons
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch

enum class HistoryFilter(val label: Int) {
    ALL(R.string.filter_all), SPENDING(R.string.filter_spending), INCOME(R.string.filter_income),
    MOVES(R.string.filter_moves), UNSENT(R.string.filter_unsent), DELETED(R.string.filter_deleted);

    fun keeps(row: LocalTransaction): Boolean {
        val t = row.transaction
        if (this == DELETED) return t.status == Status.DELETED
        if (t.status == Status.DELETED) return false
        return when (this) {
            ALL -> true
            SPENDING -> t.kind == TransactionKind.EXPENSE || t.kind == TransactionKind.REFUND
            INCOME -> t.kind == TransactionKind.INCOME
            MOVES -> t.kind == TransactionKind.TRANSFER || t.kind == TransactionKind.SETTLEMENT
            UNSENT -> row.state == SyncState.PENDING || row.state == SyncState.REJECTED
            DELETED -> true
        }
    }
}

/** Every row, newest first, grouped by day. */
@Composable
fun HistoryScreen(view: HouseholdView, headerActions: @Composable () -> Unit, onOpen: (String) -> Unit, onImport: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    val f = view.formats
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    // One account, or all of them: a transfer shows under both its accounts.
    var account by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    // Swiping a row asks first; once deleted, an undo bar stays a few seconds.
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var undoId by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(undoId) { if (undoId != null) { kotlinx.coroutines.delay(6000); undoId = null } }

    val shown = remember(view, filter, query, account) {
        val q = query.trim().normalizeName()
        view.rows.filter { filter.keeps(it) }
            .filter { account == null || it.transaction.accountId == account || it.transaction.toAccountId == account }
            .filter { row ->
            if (q.isEmpty()) return@filter true
            val t = row.transaction
            listOfNotNull(t.note, view.categoryName(t.categoryId), view.accountName(t.accountId), view.memberName(t.paidByMemberId))
                .plus(t.tags).any { it.normalizeName().contains(q) } || f.plain(t.amountMinor).contains(query.trim())
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val export = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val csv = io.github.sirallap.fulla.client.local.CsvExport.write(view.config, view.rows.map { it.transaction })
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            }
        }
    }
    // A round-up is shown under its purchase, not as a row of its own; it stands alone only when its purchase is not in the list.
    val byId = remember(view) { view.rows.associate { it.transaction.id to it.transaction } }
    val folded = remember(shown) { io.github.sirallap.fulla.core.roundup.RoundUps.foldedInto(shown.map { it.transaction }) }
    val days = remember(shown) { shown.filter { it.transaction.id !in folded }.groupBy { it.transaction.date }.toSortedMap(compareByDescending { it }) }

    Column(Modifier.fillMaxSize()) {
        TabHeader(stringResource(R.string.tab_history), actions = { headerActions() },
            menu = listOf(
                MenuItem(stringResource(R.string.import_statement), Icons.Outlined.FileUpload, onClick = onImport),
                MenuItem(stringResource(R.string.export_csv), Icons.Outlined.FileDownload) {
                    export.launch("fulla-${java.time.LocalDate.now()}.csv")
                },
            ))
        // A pill of glass: a liquid rises in it while it is typed in.
        io.github.sirallap.fulla.ui.components.SearchPill(query, { query = it }, stringResource(R.string.search))
        ChipRow(HistoryFilter.entries.map { it to stringResource(it.label) }, filter, { filter = it })
        val accounts = view.config.accounts.filter { !it.archived }
        if (accounts.size >= 2) {
            ChipRow(listOf<Pair<String?, String>>(null to stringResource(R.string.all_accounts)) + accounts.map { it.id to it.name },
                account, { account = it })
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; container.syncAll(); refreshing = false } },
            modifier = Modifier.weight(1f),
        ) {
            if (shown.isEmpty()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = listEndPadding(aboveTabBar = true)) {
                    item { EmptyState(Icons.Outlined.ReceiptLong, stringResource(R.string.empty_history_title), stringResource(R.string.empty_history_text)) }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = listEndPadding(aboveTabBar = true)) {
                    for ((day, rows) in days) {
                        item(key = "day-$day") {
                            // What was spent and what was received that day, worked out in core; nothing is said of a day that moved no money.
                            val totals = DayTotals.of(rows)
                            // Short, as the day's line has little room: −12,50 € · +20,00 €.
                            val spent = f.money(-totals.spentMinor, signed = true)
                            val received = f.money(totals.receivedMinor, signed = true)
                            val summary = listOfNotNull(spent.takeIf { totals.spentMinor > 0 }, received.takeIf { totals.receivedMinor > 0 }).joinToString(" · ")
                            val described = listOfNotNull(
                                stringResource(R.string.day_spent, f.money(totals.spentMinor)).takeIf { totals.spentMinor > 0 },
                                stringResource(R.string.day_received, f.money(totals.receivedMinor)).takeIf { totals.receivedMinor > 0 },
                            ).joinToString(". ")
                            val trailing: (@Composable RowScope.() -> Unit)? = if (totals.isEmpty) null else {
                                { Text(summary, style = FullaType.label, color = c.inkMuted,
                                    modifier = Modifier.semantics { contentDescription = described }) }
                            }
                            Section(f.day(day), top = 16.dp, trailing = trailing)
                        }
                        // A day's rows sit in one card under the day's name.
                        itemsIndexed(rows, key = { _, row -> row.id }) { i, row ->
                            CardItem(CardSlot.of(i, rows.size)) {
                                val deletable = row.transaction.status != Status.DELETED
                                val dismiss = androidx.compose.material3.rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                                    // The row springs back; the dialog decides.
                                    if (v == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) pendingDelete = row.id
                                    false
                                })
                                androidx.compose.material3.SwipeToDismissBox(
                                    state = dismiss,
                                    enableDismissFromStartToEnd = false,
                                    enableDismissFromEndToStart = deletable,
                                    backgroundContent = {
                                        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.danger),
                                            contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                                            Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete), tint = c.paper, modifier = Modifier.padding(end = 24.dp))
                                        }
                                    },
                                ) {
                                    // Opaque, so the red is only seen once the row is pulled aside.
                                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().background(c.paperHigh)) {
                                        HistoryRow(view, row, io.github.sirallap.fulla.core.roundup.RoundUps.underPurchase(row.transaction, byId)) { onOpen(row.id) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Asking before deleting; the delete can still be undone for a few seconds.
            pendingDelete?.let { id ->
                io.github.sirallap.fulla.ui.components.ConfirmDialog(
                    title = stringResource(R.string.delete_entry_title),
                    text = stringResource(R.string.delete_entry_text),
                    confirmLabel = stringResource(R.string.delete),
                    danger = false,
                    onConfirm = { pendingDelete = null; scope.launch { container.ledger.delete(view.id, id); undoId = id } },
                    onDismiss = { pendingDelete = null },
                )
            }
            undoId?.let { id ->
                androidx.compose.foundation.layout.Row(
                    Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = 96.dp).fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(c.paperHigh)
                        .border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.entry_deleted), style = FullaType.body, color = c.ink)
                    androidx.compose.material3.TextButton(onClick = { undoId = null; scope.launch { container.ledger.restore(view.id, id) } }) {
                        Text(stringResource(R.string.undo), color = c.accent)
                    }
                }
            }
        }
    }
}

/**
 * One movement as a small card of its own: its category (icon and name, in the category's colour)
 * above the title and amount, and under them only what tells this row apart from the others: the
 * account it used, who wrote it down, its own fields. A fact that every row would repeat is left
 * out: the account when there is only one, the person when the household is one person.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(view: HouseholdView, row: LocalTransaction, roundUp: Transaction?, onClick: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val t = row.transaction
    val category = view.config.category(t.categoryId)
    val deleted = t.status == Status.DELETED
    val moves = t.kind == TransactionKind.TRANSFER || t.kind == TransactionKind.SETTLEMENT
    val title = when (t.kind) {
        // A transfer says what it is; the two accounts go below as labels, from one to the other.
        TransactionKind.TRANSFER ->
            if (io.github.sirallap.fulla.core.roundup.RoundUps.isRoundUp(t)) stringResource(R.string.round_up_title)
            else stringResource(R.string.kind_transfer)
        TransactionKind.SETTLEMENT -> stringResource(R.string.settlement_between, view.memberName(t.paidByMemberId), view.memberName(t.toMemberId))
        else -> t.note.ifBlank { category?.name ?: stringResource(R.string.uncategorized) }
    }
    // The category goes above the title when the title is the note; otherwise the title already is the category.
    val hasNote = !moves && t.note.isNotBlank()
    val tint = if (deleted) c.inkMuted else category?.let { c.category(it.colorIndex) } ?: c.inkMuted
    val icon = if (moves) androidx.compose.material.icons.Icons.Outlined.SwapHoriz else CategoryIcons.of(category?.icon ?: "label")
    val account = view.config.account(t.accountId)
    val showAccount = account != null && t.kind != TransactionKind.TRANSFER && view.config.accounts.count { !it.archived } >= 2
    val fromAccount = account?.takeIf { t.kind == TransactionKind.TRANSFER }
    val toAccount = view.config.account(t.toAccountId)?.takeIf { t.kind == TransactionKind.TRANSFER }
    val payer = view.config.member(t.paidByMemberId)
        ?.takeIf { view.config.activeMembers.size >= 2 && t.kind != TransactionKind.SETTLEMENT }
    val fields = io.github.sirallap.fulla.core.schema.SchemaEngine.fieldsForList(view.config.fields).mapNotNull { fd ->
        io.github.sirallap.fulla.ui.entry.fieldText(view, fd, t.extras[fd.key])
    }
    val refund = stringResource(R.string.kind_refund).takeIf { t.kind == TransactionKind.REFUND }
    // What went to savings with this purchase, as a line of its own under it.
    val rounded = roundUp?.let { stringResource(R.string.round_up_saved, f.money(it.amountMinor), view.accountName(it.toAccountId) ?: "") }
    val detail = listOfNotNull(when (row.state) {
        SyncState.REJECTED -> stringResource(R.string.rejected_reason, row.rejectMessage ?: row.rejectCode ?: "")
        SyncState.PENDING -> stringResource(R.string.not_sent_yet)
        else -> null
    }, rounded).joinToString(" · ").ifBlank { null }
    val repeats = t.recurrence == io.github.sirallap.fulla.core.model.Recurrence.FIXED
    val inset = io.github.sirallap.fulla.ui.components.LocalRowInset.current
    val divider = io.github.sirallap.fulla.ui.components.LocalCardSlot.current?.divider ?: true
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        // Every row has the same shape: the icon in its own column, then the title (always the same size) with the amount
        // on its line, the category under it when the title is a note, and below that only what tells rows apart.
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = inset, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.Top,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Top,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    androidx.compose.foundation.layout.Row(Modifier.weight(1f), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        Text(title, style = FullaType.body, color = if (deleted) c.inkMuted else c.ink, maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (repeats) Icon(androidx.compose.material.icons.Icons.Outlined.Repeat, null, tint = c.inkMuted, modifier = Modifier.size(14.dp))
                    }
                    val amount = when (t.kind) {
                        TransactionKind.INCOME, TransactionKind.REFUND -> f.money(t.amountMinor, signed = true)
                        else -> f.money(t.amountMinor)
                    }
                    AmountText(amount, color = when {
                        deleted -> c.inkMuted
                        t.kind == TransactionKind.INCOME || t.kind == TransactionKind.REFUND -> c.moneyIn
                        t.kind == TransactionKind.TRANSFER || t.kind == TransactionKind.SETTLEMENT -> c.inkMuted
                        else -> c.ink
                    })
                }
                if (hasNote) {
                    Text((category?.name ?: stringResource(R.string.uncategorized)).uppercase(), style = FullaType.label, color = tint,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (showAccount || fromAccount != null || payer != null || refund != null || fields.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        if (showAccount && account != null) {
                            io.github.sirallap.fulla.ui.components.AccountLabel(account,
                                io.github.sirallap.fulla.ui.components.accountColorIndex(view.config.accounts, account.id))
                        }
                        if (fromAccount != null && toAccount != null) {
                            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                io.github.sirallap.fulla.ui.components.AccountLabel(fromAccount,
                                    io.github.sirallap.fulla.ui.components.accountColorIndex(view.config.accounts, fromAccount.id))
                                Text("→", style = FullaType.body, color = c.inkMuted)
                                io.github.sirallap.fulla.ui.components.AccountLabel(toAccount,
                                    io.github.sirallap.fulla.ui.components.accountColorIndex(view.config.accounts, toAccount.id))
                            }
                        }
                        if (payer != null) io.github.sirallap.fulla.ui.components.PersonChip(payer.displayName, payer.initials, payer.colorIndex)
                        if (refund != null) io.github.sirallap.fulla.ui.components.InfoChip(refund)
                        for (text in fields) io.github.sirallap.fulla.ui.components.InfoChip(text)
                    }
                }
                if (detail != null) {
                    Text(detail, style = FullaType.secondary, modifier = Modifier.padding(top = 6.dp),
                        color = if (row.state == SyncState.REJECTED) c.warning else c.inkMuted)
                }
            }
        }
        if (divider) io.github.sirallap.fulla.ui.components.Hairline(inset = inset)
    }
}
