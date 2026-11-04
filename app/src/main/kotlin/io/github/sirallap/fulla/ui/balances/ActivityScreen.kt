// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.balances

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.BackHeader
import io.github.sirallap.fulla.ui.components.CardItem
import io.github.sirallap.fulla.ui.components.CardSlot
import io.github.sirallap.fulla.ui.components.Hairline
import io.github.sirallap.fulla.ui.components.LocalCardSlot
import io.github.sirallap.fulla.ui.components.LocalRowInset
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.listEndPadding
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What was done in the household, newest first: who created, changed, deleted or restored what, when, and for a change,
 * what each field was and became. It is read from the server's change history, so it needs the household connected;
 * [accountId] keeps what touched one account.
 */
@Composable
fun ActivityScreen(view: HouseholdView, accountId: String?, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var events by remember(view.id, accountId) { mutableStateOf<List<Wire.AuditEvent>>(emptyList()) }
    var hasMore by remember(view.id, accountId) { mutableStateOf(false) }
    var loading by remember(view.id, accountId) { mutableStateOf(false) }
    var failed by remember(view.id, accountId) { mutableStateOf(false) }
    var loaded by remember(view.id, accountId) { mutableStateOf(false) }
    fun load(more: Boolean) {
        if (loading) return
        scope.launch {
            loading = true; failed = false
            val api = container.api()
            if (api == null) { failed = true; loading = false; return@launch }
            runCatching { api.auditList(view.id, accountId = accountId, before = if (more) events.lastOrNull()?.id else null) }
                .onSuccess { page -> events = if (more) events + page.events else page.events; hasMore = page.hasMore; loaded = true }
                .onFailure { failed = true }
            loading = false
        }
    }
    LaunchedEffect(view.id, accountId) { if (view.state.connected) load(false) }
    Column(Modifier.fillMaxSize()) {
        BackHeader(stringResource(if (accountId == null) R.string.activity_title else R.string.ledger_changes), onBack)
        ActivityList(view, events, hasMore, loading, failed, loaded, connected = view.state.connected, onMore = { load(true) }, onRetry = { load(false) }, onOpen = onOpen)
    }
}

/** The list itself, from the lines already read. */
@Composable
fun ActivityList(
    view: HouseholdView, events: List<Wire.AuditEvent>, hasMore: Boolean, loading: Boolean, failed: Boolean, loaded: Boolean,
    connected: Boolean, onMore: () -> Unit, onRetry: () -> Unit, onOpen: (String) -> Unit,
) {
    val c = FullaTheme.colors
    val f = view.formats
    val zone = remember { ZoneId.systemDefault() }
    val byDay = events.groupBy { stampOf(it, zone).toLocalDate() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listEndPadding(aboveTabBar = false)) {
        if (!connected) item(key = "offline") { Note(stringResource(R.string.activity_offline)) }
        else if (failed) item(key = "failed") {
            Column {
                Note(stringResource(R.string.activity_error))
                TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.activity_retry)) }
            }
        }
        else if (loaded && events.isEmpty()) item(key = "empty") { Note(stringResource(R.string.activity_empty)) }
        for ((day, lines) in byDay) {
            item(key = "day-$day") { Section(f.day(day), top = 16.dp) }
            items(lines.size, key = { "ev-${lines[it].id}" }) { i ->
                CardItem(CardSlot.of(i, lines.size)) { EventRow(view, lines[i], zone, onOpen) }
            }
        }
        if (hasMore) item(key = "more") {
            TextButton(onClick = onMore, enabled = !loading, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.activity_more))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = FullaType.secondary, color = FullaTheme.colors.inkMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
}

/** When it happened: the phone's own clock for the edit when it is known (an edit made offline arrives later), else when the server heard. */
private fun stampOf(e: Wire.AuditEvent, zone: ZoneId): ZonedDateTime {
    val text = (e.after?.get("client_updated_at") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { !e.backfill } ?: e.at
    return runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone) }.getOrElse { ZonedDateTime.now(zone) }
}

private fun JsonObject?.text(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonObject?.number(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

@Composable
private fun EventRow(view: HouseholdView, e: Wire.AuditEvent, zone: ZoneId, onOpen: (String) -> Unit) {
    val c = FullaTheme.colors
    val f = view.formats
    val snapshot = e.after ?: e.before
    val current = view.rows.firstOrNull { it.transaction.id == e.entityId }?.transaction
    val isTx = e.entity == "transaction"
    val what = if (isTx) {
        val note = snapshot.text("note")?.takeIf { it.isNotBlank() } ?: current?.note?.takeIf { it.isNotBlank() }
        val category = view.categoryName(snapshot.text("category_id") ?: current?.categoryId)
        "«" + (note ?: category ?: stringResource(R.string.uncategorized)) + "»"
    } else stringResource(R.string.activity_account_named, snapshot.text("name") ?: view.accountName(e.entityId) ?: "?")
    val who = view.config.member(e.actorMemberId)?.displayName ?: stringResource(R.string.activity_someone)
    val sentence = stringResource(when (e.action) {
        "create" -> R.string.activity_created
        "delete" -> R.string.activity_deleted
        "restore" -> R.string.activity_restored
        else -> R.string.activity_updated
    }, who, what)
    val (icon, tint) = when (e.action) {
        "create" -> Icons.Outlined.Add to c.moneyIn
        "delete" -> Icons.Outlined.DeleteOutline to c.danger
        "restore" -> Icons.Outlined.RestoreFromTrash to c.accent
        else -> Icons.Outlined.Edit to c.inkMuted
    }
    val time = stampOf(e, zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val amount = (snapshot.number("amount_minor") ?: current?.amountMinor)?.let { f.money(it) }
    val accountName = view.accountName(snapshot.text("account_id") ?: current?.accountId)
    val where = listOfNotNull(time, amount.takeIf { isTx }, accountName.takeIf { isTx }).joinToString(" · ")
    val diffs = if (e.action == "update") diffLines(view, e) else emptyList()
    val inset = LocalRowInset.current
    val divider = LocalCardSlot.current?.divider ?: true
    val openable = isTx && current != null
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().then(if (openable) Modifier.clickable { onOpen(e.entityId) } else Modifier).padding(horizontal = inset, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(sentence, style = FullaType.body, color = c.ink)
                Text(where, style = FullaType.label, color = c.inkMuted)
                for (line in diffs) Text(line, style = FullaType.secondary, color = c.ink)
                if (e.backfill) Text(stringResource(R.string.activity_before_log), style = FullaType.label, color = c.inkMuted)
            }
        }
        if (divider) Hairline(inset = inset)
    }
}

/** For a change: each field that moved, as «Amount: 12,50 € → 14,50 €». A few fields have no value worth showing and only say they changed. */
@Composable
private fun diffLines(view: HouseholdView, e: Wire.AuditEvent): List<String> {
    val f = view.formats
    val dash = "—"
    fun money(o: JsonObject?, k: String) = o.number(k)?.let { f.money(it) } ?: dash
    fun day(o: JsonObject?, k: String) = o.text(k)?.let { runCatching { f.day(LocalDate.parse(it)) }.getOrNull() } ?: dash
    fun note(o: JsonObject?) = o.text("note")?.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: dash
    val labels = mapOf(
        "amount_minor" to stringResource(R.string.activity_f_amount), "date" to stringResource(R.string.activity_f_date),
        "note" to stringResource(R.string.activity_f_note), "category_id" to stringResource(R.string.activity_f_category),
        "account_id" to stringResource(R.string.activity_f_account), "to_account_id" to stringResource(R.string.activity_f_to_account),
        "kind" to stringResource(R.string.activity_f_kind), "paid_by_member_id" to stringResource(R.string.activity_f_payer),
        "name" to stringResource(R.string.activity_f_name), "opening_balance_minor" to stringResource(R.string.activity_f_opening),
    )
    val ignored = setOf("status", "sort", "created_at", "created_by_member_id", "import_fingerprint", "occurrence_date")
    val b = e.before
    val a = e.after
    val lines = mutableListOf<String>()
    var other = false
    for (k in e.changed) {
        val label = labels[k]
        val values: String? = when (k) {
            "amount_minor" -> money(b, k) + " → " + money(a, k)
            "opening_balance_minor" -> money(b, k) + " → " + money(a, k)
            "date" -> day(b, k) + " → " + day(a, k)
            "note" -> note(b) + " → " + note(a)
            "category_id" -> (view.categoryName(b.text(k)) ?: dash) + " → " + (view.categoryName(a.text(k)) ?: dash)
            "account_id", "to_account_id" -> (view.accountName(b.text(k)) ?: dash) + " → " + (view.accountName(a.text(k)) ?: dash)
            "paid_by_member_id" -> (view.config.member(b.text(k))?.displayName ?: dash) + " → " + (view.config.member(a.text(k))?.displayName ?: dash)
            "name" -> (b.text(k) ?: dash) + " → " + (a.text(k) ?: dash)
            else -> null
        }
        when {
            label != null && values != null -> lines += "$label: $values"
            label != null -> lines += label
            k !in ignored -> other = true
        }
    }
    if (other) lines += stringResource(R.string.activity_f_other)
    return lines
}
