// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.ReceiptLong
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.core.text.normalizeName
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.ChipRow
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.MemberBadge
import io.github.sirallap.fulla.ui.components.MenuItem
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.TabHeader
import io.github.sirallap.fulla.ui.entry.CategoryIcons
import io.github.sirallap.fulla.ui.theme.FullaTheme
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
    var refreshing by remember { mutableStateOf(false) }

    val shown = remember(view, filter, query) {
        val q = query.trim().normalizeName()
        view.rows.filter { filter.keeps(it) }.filter { row ->
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
    val days = remember(shown) { shown.groupBy { it.transaction.date }.toSortedMap(compareByDescending { it }) }

    Column(Modifier.fillMaxSize()) {
        TabHeader(stringResource(R.string.tab_history), actions = { headerActions() },
            menu = listOf(
                MenuItem(stringResource(R.string.import_statement), Icons.Outlined.FileUpload, onClick = onImport),
                MenuItem(stringResource(R.string.export_csv), Icons.Outlined.FileDownload) {
                    export.launch("fulla-${java.time.LocalDate.now()}.csv")
                },
            ))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
        ChipRow(HistoryFilter.entries.map { it to stringResource(it.label) }, filter, { filter = it })
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; container.syncAll(); refreshing = false } },
            modifier = Modifier.weight(1f),
        ) {
            if (shown.isEmpty()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { EmptyState(Icons.Outlined.ReceiptLong, stringResource(R.string.empty_history_title), stringResource(R.string.empty_history_text)) }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    for ((day, rows) in days) {
                        item(key = "day-$day") { Section(f.day(day), top = 16.dp) }
                        items(rows, key = { it.id }) { row -> HistoryRow(view, row) { onOpen(row.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(view: HouseholdView, row: LocalTransaction, onClick: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val t = row.transaction
    val category = view.config.category(t.categoryId)
    val title = when (t.kind) {
        TransactionKind.TRANSFER -> stringResource(R.string.transfer_between, view.accountName(t.accountId) ?: "?", view.accountName(t.toAccountId) ?: "?")
        TransactionKind.SETTLEMENT -> stringResource(R.string.settlement_between, view.memberName(t.paidByMemberId), view.memberName(t.toMemberId))
        else -> t.note.ifBlank { category?.name ?: stringResource(R.string.uncategorized) }
    }
    val context = listOfNotNull(
        category?.name?.takeIf { t.note.isNotBlank() },
        view.accountName(t.accountId)?.takeIf { t.kind != TransactionKind.TRANSFER },
        stringResource(R.string.kind_refund).takeIf { t.kind == TransactionKind.REFUND },
    ).plus(io.github.sirallap.fulla.core.schema.SchemaEngine.fieldsForList(view.config.fields).mapNotNull { f ->
        io.github.sirallap.fulla.ui.entry.fieldText(view, f, t.extras[f.key])
    }).joinToString(" · ")
    val detail = when (row.state) {
        SyncState.REJECTED -> stringResource(R.string.rejected_reason, row.rejectMessage ?: row.rejectCode ?: "")
        SyncState.PENDING -> stringResource(R.string.not_sent_yet)
        else -> null
    }
    val payer = view.config.member(t.paidByMemberId)
    val deleted = t.status == Status.DELETED
    ListRow(
        title = title,
        titleColor = if (deleted) c.inkMuted else c.ink,
        context = context.ifBlank { null },
        detail = detail,
        detailColor = if (row.state == SyncState.REJECTED) c.warning else c.inkMuted,
        icon = if (payer == null || view.config.activeMembers.size < 2) CategoryIcons.of(category?.icon ?: "label") else null,
        iconTint = c.category(category?.colorIndex ?: 0),
        start = if (payer != null && view.config.activeMembers.size >= 2) ({
            MemberBadge(payer.initials, payer.colorIndex, description = stringResource(R.string.paid_by, payer.displayName))
        }) else null,
        onClick = onClick,
        end = {
            val amount = when (t.kind) {
                TransactionKind.INCOME -> f.money(t.amountMinor, signed = true)
                TransactionKind.REFUND -> f.money(t.amountMinor, signed = true)
                else -> f.money(t.amountMinor)
            }
            AmountText(amount, color = when {
                deleted -> c.inkMuted
                t.kind == TransactionKind.INCOME || t.kind == TransactionKind.REFUND -> c.moneyIn
                t.kind == TransactionKind.TRANSFER || t.kind == TransactionKind.SETTLEMENT -> c.inkMuted
                else -> c.ink
            })
        },
    )
}
