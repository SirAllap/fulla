// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.balances

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.analytics.AccountLedger
import io.github.sirallap.fulla.core.analytics.LedgerLine
import io.github.sirallap.fulla.core.analytics.LedgerNote
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.BackHeader
import io.github.sirallap.fulla.ui.components.CardItem
import io.github.sirallap.fulla.ui.components.CardSlot
import io.github.sirallap.fulla.ui.components.ChipRow
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.Hairline
import io.github.sirallap.fulla.ui.components.InfoChip
import io.github.sirallap.fulla.ui.components.LocalCardSlot
import io.github.sirallap.fulla.ui.components.LocalRowInset
import io.github.sirallap.fulla.ui.components.PersonChip
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.accountIcon
import io.github.sirallap.fulla.ui.components.listEndPadding
import io.github.sirallap.fulla.ui.entry.CategoryIcons
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType

private enum class LedgerFilter(val label: Int) {
    ALL(R.string.filter_all), IN(R.string.ledger_filter_in), OUT(R.string.ledger_filter_out),
    DELETED(R.string.filter_deleted), UNSENT(R.string.filter_unsent);

    fun keeps(line: LedgerLine): Boolean = when (this) {
        ALL -> true
        IN -> line.deltaMinor > 0
        OUT -> line.deltaMinor < 0
        DELETED -> line.note == LedgerNote.DELETED
        UNSENT -> line.unsent
    }
}

/**
 * The book of one account: every movement that touches it, newest first, with the balance after each, so it can be
 * read line by line against the bank's statement. What is left out of the balance (deleted, refused, scheduled,
 * from before the opening balance) is listed too, struck through, and says why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountLedgerScreen(view: HouseholdView, accountId: String, onBack: () -> Unit, onOpen: (String) -> Unit, onChanges: () -> Unit = {}, movedLines: List<Wire.AuditEvent>? = null) {
    val c = FullaTheme.colors
    val f = view.formats
    val account = view.config.account(accountId)
    if (account == null) { BackHeader("", onBack); return }
    val today = io.github.sirallap.fulla.ui.components.rememberToday()
    val book = remember(view, accountId, today) { AccountLedger.of(view.rows, account, today) }
    var filter by rememberSaveable { mutableStateOf(LedgerFilter.ALL) }
    // What was booked here and then moved to another account leaves this book, so the server's history says where it went.
    val container = io.github.sirallap.fulla.ui.LocalContainer.current
    var read by remember(view.id, accountId) { mutableStateOf<List<Wire.AuditEvent>>(emptyList()) }
    if (movedLines == null) {
        androidx.compose.runtime.LaunchedEffect(view.id, accountId, view.state.connected) {
            if (!view.state.connected) return@LaunchedEffect
            val api = container.api() ?: return@LaunchedEffect
            runCatching { api.auditList(view.id, accountId = accountId) }.onSuccess { read = it.events }
        }
    }
    val movedOut = remember(movedLines, read, view.rows, accountId) {
        (movedLines ?: read).filter { isMove(it) && it.before.text("account_id") == accountId && it.after.text("account_id") != accountId }
            .distinctBy { it.entityId }
            .filter { e -> view.rows.firstOrNull { it.transaction.id == e.entityId }?.transaction?.let { it.isActive && it.accountId != accountId } == true }
    }
    val shown = book.lines.filter { filter.keeps(it) }
    Column(Modifier.fillMaxSize()) {
        BackHeader(account.name, onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = listEndPadding(aboveTabBar = false)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(accountIcon(account), null, tint = c.inkMuted, modifier = Modifier.size(22.dp))
                        Text(stringResource(R.string.ledger_balance_today), style = FullaType.body, color = c.inkMuted)
                    }
                    AmountText(f.money(book.balanceMinor), color = if (book.balanceMinor < 0) c.moneyOut else c.ink)
                }
            }
            item(key = "changes") {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    io.github.sirallap.fulla.ui.components.SecondaryButton(stringResource(R.string.ledger_changes), onChanges)
                }
            }
            item(key = "filters") {
                ChipRow(LedgerFilter.entries.map { it to stringResource(it.label) }, filter, { filter = it })
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.ledger_empty), style = FullaType.secondary, color = c.inkMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
                }
            }
            if (filter == LedgerFilter.ALL && movedOut.isNotEmpty()) {
                item(key = "moved-title") { Section(stringResource(R.string.ledger_moved_title), top = 16.dp) }
                items(movedOut.size, key = { "moved-${movedOut[it].entityId}" }) { i ->
                    CardItem(CardSlot.of(i, movedOut.size)) { MovedRow(view, movedOut[i]) { onOpen(movedOut[i].entityId) } }
                }
            }
            val days = shown.groupBy { it.transaction.date }
            for ((day, lines) in days) {
                item(key = "day-$day") { Section(f.day(day), top = 16.dp) }
                items(lines.size, key = { "line-${lines[it].transaction.id}" }) { i ->
                    CardItem(CardSlot.of(i, lines.size)) { LedgerRow(view, account.id, lines[i]) { onOpen(lines[i].transaction.id) } }
                }
            }
            if (filter == LedgerFilter.ALL) {
                item(key = "opening") {
                    Section(stringResource(R.string.ledger_opening), top = 16.dp)
                    CardItem(CardSlot.ONLY) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = LocalRowInset.current, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(book.openingDate?.let { stringResource(R.string.ledger_opening_on, f.day(it)) } ?: stringResource(R.string.ledger_opening_all),
                                style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.weight(1f))
                            AmountText(f.money(book.openingMinor), color = if (book.openingMinor < 0) c.moneyOut else c.ink)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LedgerRow(view: HouseholdView, accountId: String, line: LedgerLine, onClick: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val t = line.transaction
    val category = view.config.category(t.categoryId)
    val title = when (t.kind) {
        TransactionKind.TRANSFER ->
            if (t.accountId == accountId) stringResource(R.string.ledger_transfer_to, view.accountName(t.toAccountId) ?: "?")
            else stringResource(R.string.ledger_transfer_from, view.accountName(t.accountId) ?: "?")
        else -> t.note.ifBlank { category?.name ?: stringResource(R.string.uncategorized) }
    }
    val tint = if (!line.counts) c.inkMuted else category?.takeIf { t.kind != TransactionKind.TRANSFER }?.let { c.category(it.colorIndex) } ?: c.inkMuted
    val payer = view.config.member(t.paidByMemberId)?.takeIf { view.config.activeMembers.size >= 2 }
    val why = when (line.note) {
        LedgerNote.COUNTS -> null
        LedgerNote.DELETED -> stringResource(R.string.ledger_note_deleted)
        LedgerNote.REFUSED -> stringResource(R.string.ledger_note_refused)
        LedgerNote.FUTURE -> stringResource(R.string.ledger_note_future)
        LedgerNote.BEFORE_OPENING -> stringResource(R.string.ledger_note_before)
    }
    val struck = if (line.counts) TextDecoration.None else TextDecoration.LineThrough
    val inset = LocalRowInset.current
    val divider = LocalCardSlot.current?.divider ?: true
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = inset, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (t.kind == TransactionKind.TRANSFER) Icons.Outlined.Repeat else CategoryIcons.of(category?.icon ?: "label"), null,
                tint = tint, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = FullaType.body, color = if (line.counts) c.ink else c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = struck)
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (payer != null) PersonChip(payer.displayName, payer.initials, payer.colorIndex)
                    if (why != null) InfoChip(why)
                    if (line.unsent && line.counts) InfoChip(stringResource(R.string.not_sent_yet))
                    if (t.recurrence == Recurrence.FIXED) InfoChip(stringResource(R.string.fixed))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                AmountText(f.money(line.deltaMinor, signed = true), color = when {
                    !line.counts -> c.inkMuted
                    line.deltaMinor > 0 -> c.moneyIn
                    else -> c.ink
                })
                Text(if (line.counts) stringResource(R.string.ledger_balance_after, f.money(line.balanceAfterMinor)) else stringResource(R.string.ledger_not_counted),
                    style = FullaType.label, color = c.inkMuted, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (divider) Hairline(inset = inset)
    }
}

/** A movement that was booked on this account and later moved to another: it no longer counts here, and says where it went. */
@Composable
private fun MovedRow(view: HouseholdView, e: Wire.AuditEvent, onClick: () -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val t = view.rows.firstOrNull { it.transaction.id == e.entityId }?.transaction ?: return
    val category = view.config.category(t.categoryId)
    val title = t.note.ifBlank { category?.name ?: stringResource(R.string.uncategorized) }
    val to = view.accountName(e.after.text("account_id")) ?: "?"
    val stamp = runCatching { java.time.OffsetDateTime.parse(e.at).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull()
    val inset = LocalRowInset.current
    val divider = LocalCardSlot.current?.divider ?: true
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = inset, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Repeat, null, tint = c.inkMuted, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = FullaType.body, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.ledger_moved_to, to, stamp?.let { f.day(it) } ?: ""), style = FullaType.label, color = c.inkMuted,
                    modifier = Modifier.padding(top = 4.dp))
            }
            AmountText(f.money(t.amountMinor), color = c.inkMuted)
        }
        if (divider) Hairline(inset = inset)
    }
}
