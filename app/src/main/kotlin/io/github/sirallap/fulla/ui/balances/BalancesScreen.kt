// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.balances

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import io.github.sirallap.fulla.ui.components.listEndPadding
import io.github.sirallap.fulla.ui.components.FullaDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.ui.components.glass
import io.github.sirallap.fulla.ui.components.liquid
import io.github.sirallap.fulla.core.balance.Balances
import io.github.sirallap.fulla.core.balance.Payment
import io.github.sirallap.fulla.core.balance.SettlementPlanner
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.AmountText
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.CardItem
import io.github.sirallap.fulla.ui.components.CardSlot
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.MemberBadge
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.TabHeader
import io.github.sirallap.fulla.ui.theme.FullaTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/**
 * Who owes whom, and the fewest payments that would settle it; then what is
 * in each account. Recording a payment writes a settlement, which moves the
 * balances and no total.
 *
 * In one shared pot nobody owes anybody: the screen says so, and shows the
 * accounts and what they hold together. Debts kept from before the switch
 * stay hidden, and come back if the household splits again.
 */
@Composable
fun BalancesScreen(view: HouseholdView, headerActions: @Composable () -> Unit, onHouseholdSettings: () -> Unit = {}, onGoals: () -> Unit = {}, onAccounts: () -> Unit = {}) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    val f = view.formats
    val members = view.config.activeMembers
    val balances = remember(view) { Balances.of(view.active, view.config.members.map { it.id }) }
    val plan = remember(balances) { SettlementPlanner.plan(balances) }
    val today = io.github.sirallap.fulla.ui.components.rememberToday()
    val accounts = remember(view, today) {
        view.analytics.accountBalances(view.active, view.config.accounts.filter { !it.archived }, today)
    }
    var confirming by remember { mutableStateOf<Payment?>(null) }
    val shared = SharedPot.isShared(view.config.household)

    Column(Modifier.fillMaxSize()) {
        TabHeader(stringResource(R.string.tab_balances), actions = { headerActions() })
        LazyColumn(Modifier.weight(1f), contentPadding = listEndPadding(aboveTabBar = true)) {
            if (shared) {
                item {
                    EmptyState(Icons.Outlined.Savings, stringResource(R.string.shared_pot_card_title), stringResource(R.string.shared_pot_card_text),
                        action = { io.github.sirallap.fulla.ui.components.SecondaryButton(stringResource(R.string.shared_pot_change), onHouseholdSettings) })
                }
            } else if (members.size >= 2) {
                // As in the Saldos board: what matters to me in a dark card with its button, then who paid what this month.
                val me = view.config.meMemberId
                val mine = plan.firstOrNull { it.fromMemberId == me || it.toMemberId == me }
                if (mine != null) item(key = "mine") {
                    val other = if (mine.toMemberId == me) mine.fromMemberId else mine.toMemberId
                    val m = view.config.member(other)
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(c.ink)
                            .glass().padding(18.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                    ) {
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                            MemberBadge(m?.initials ?: "?", m?.colorIndex ?: 0, size = 40.dp)
                            Column {
                                Text(if (mine.toMemberId == me) stringResource(R.string.owes_you, view.memberName(other)) else stringResource(R.string.you_owe, view.memberName(other)),
                                    style = io.github.sirallap.fulla.ui.theme.FullaType.secondary, color = c.paper.copy(alpha = 0.75f))
                                Text(f.money(mine.amountMinor), style = io.github.sirallap.fulla.ui.theme.FullaType.screenTitle, color = c.paper)
                            }
                        }
                        // Ink on the ink card would vanish: the button is the highlight, a glass pill.
                        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                            .background(c.highlight).glass(0.9f)
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) { confirming = mine },
                            contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text(stringResource(R.string.settle_with, view.memberName(other)),
                                style = io.github.sirallap.fulla.ui.theme.FullaType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                color = c.onHighlight, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
                        }
                    }
                }
                val others = plan.filter { it !== mine }
                if (mine == null && plan.isEmpty()) {
                    item { EmptyState(Icons.Outlined.CheckCircle, stringResource(R.string.all_settled_title), stringResource(R.string.all_settled_text)) }
                }
                if (others.isNotEmpty()) {
                    item { Section(stringResource(R.string.to_settle)) }
                    itemsIndexed(others, key = { _, p -> "${p.fromMemberId}-${p.toMemberId}" }) { i, p ->
                        CardItem(CardSlot.of(i, others.size)) { ListRow(
                            title = stringResource(R.string.pays, view.memberName(p.fromMemberId), view.memberName(p.toMemberId)),
                            clickLabel = stringResource(R.string.record_payment),
                            onClick = { confirming = p },
                            below = {
                                androidx.compose.material3.TextButton(onClick = { confirming = p },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                                    Text(stringResource(R.string.record_payment))
                                }
                            },
                            end = { AmountText(f.money(p.amountMinor)) },
                        ) }
                    }
                }
                // Who paid how much of this month's spending, each against the most.
                val period = f.currentPeriod(today)
                val paid = view.active.filter { it.kind == io.github.sirallap.fulla.core.model.TransactionKind.EXPENSE && it.paidByMemberId != null &&
                    f.periodRule.periodOf(it.date, it.kind, it.recurrence) == period }
                    .groupBy { it.paidByMemberId!! }.mapValues { (_, ts) -> ts.sumOf { it.amountMinor } }
                if (paid.isNotEmpty()) {
                    item { Section(stringResource(R.string.paid_this_period)) }
                    val most = paid.values.max().coerceAtLeast(1)
                    items(members.filter { it.id in paid }, key = { "paid-" + it.id }) { m ->
                        val amount = paid[m.id] ?: 0
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                            io.github.sirallap.fulla.ui.components.Spread(start = {
                                Text(if (m.id == me) stringResource(R.string.you).replaceFirstChar { it.titlecase() } else m.displayName,
                                    style = io.github.sirallap.fulla.ui.theme.FullaType.body, color = c.ink)
                            }, end = { AmountText(f.money(amount)) })
                            io.github.sirallap.fulla.ui.components.ProgressLine(amount.toFloat() / most, c.member(m.colorIndex))
                        }
                    }
                }
            }
            // The accounts as tiles, two by two, with the total in the title and a tile to add one.
            val open = view.config.accounts.filter { !it.archived }
            item {
                val total = view.analytics.accountsTotal(view.active, view.config.accounts, today)
                Section(stringResource(R.string.accounts), trailing = {
                    Text(stringResource(R.string.total_short, f.money(total)), style = io.github.sirallap.fulla.ui.theme.FullaType.secondary, color = c.inkMuted)
                })
            }
            val tiles: List<io.github.sirallap.fulla.core.model.Account?> = open + listOf(null)
            items(tiles.chunked(2), key = { row -> row.joinToString { it?.id ?: "new" } }) { row ->
                io.github.sirallap.fulla.ui.components.TileRow {
                    for (a in row) {
                        val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                        if (a == null) {
                            Box(Modifier.weight(1f).heightIn(min = 84.dp).clip(shape).border(1.dp, c.line, shape)
                                .clickable(onClick = onAccounts).padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text("+ " + stringResource(R.string.add_account), style = io.github.sirallap.fulla.ui.theme.FullaType.body, color = c.ink)
                            }
                        } else {
                            val amount = accounts[a.id] ?: 0
                            // Each account is a glass tile filled as far as its share of what is held.
                            val held = accounts.values.filter { it > 0 }.sum().coerceAtLeast(1)
                            val share = if (amount > 0) (amount.toFloat() / held).coerceIn(0f, 1f) else 0f
                            Column(Modifier.weight(1f).heightIn(min = 84.dp).clip(shape).background(c.paperHigh)
                                .liquid(share, vertical = true).glass().border(1.dp, c.line, shape).padding(12.dp),
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                                Text(a.name, style = io.github.sirallap.fulla.ui.theme.FullaType.secondary, color = c.inkMuted)
                                Text(f.money(amount), style = io.github.sirallap.fulla.ui.theme.FullaType.title, color = if (amount < 0) c.moneyOut else c.ink,
                                    maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
            // Savings goals, each with how far its account has got; tapping one opens them in Settings.
            val goals = view.config.goals.filter { !it.archived }
            item(key = "goals-title") { Section(stringResource(R.string.settings_goals)) }
            if (goals.isEmpty()) {
                item(key = "goals-empty") {
                    // An empty list says what goes here and carries the button that makes the first (ux.md, piece 9), never a row that looks like one more goal.
                    EmptyState(Icons.Outlined.Flag, stringResource(R.string.goals_empty_title), stringResource(R.string.goals_empty_text),
                        action = { io.github.sirallap.fulla.ui.components.SecondaryButton(stringResource(R.string.goal_new), onGoals) })
                }
            } else {
                itemsIndexed(goals, key = { _, g -> "goal-" + g.id }) { i, g ->
                    io.github.sirallap.fulla.ui.settings.GoalRow(view, g, onClick = onGoals)
                }
            }
        }
    }

    // Settling up as a screen, as in the Saldar board: who pays whom, an amount that can be less, the account it lands in.
    confirming?.let { p ->
        var text by remember(p) { mutableStateOf(f.plain(p.amountMinor)) }
        var account by remember(p) { mutableStateOf(view.config.accounts.firstOrNull { !it.archived }?.id) }
        val amount = io.github.sirallap.fulla.core.money.MoneyParser.parseTyped(text, f.currency, f.decimalStyle)?.takeIf { it > 0 }
        val from = view.config.member(p.fromMemberId)
        val to = view.config.member(p.toMemberId)
        io.github.sirallap.fulla.ui.components.FormScreen(
            title = stringResource(R.string.record_payment),
            onClose = { confirming = null },
            primaryLabel = amount?.let { stringResource(R.string.settle_record, f.money(it)) } ?: stringResource(R.string.record_payment),
            primaryEnabled = amount != null,
            onPrimary = {
                val minor = amount
                if (minor != null) scope.launch {
                    confirming = null
                    container.ledger.save(view.id, Transaction(
                        id = UUID.randomUUID().toString(), kind = TransactionKind.SETTLEMENT, date = LocalDate.now(),
                        amountMinor = minor, paidByMemberId = p.fromMemberId, toMemberId = p.toMemberId, accountId = account,
                        createdAt = "", clientUpdatedAt = "", createdByMemberId = view.config.meMemberId,
                    ))
                }
            },
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                from?.let { io.github.sirallap.fulla.ui.components.MemberBadge(it.initials, it.colorIndex) }
                Text("→", style = io.github.sirallap.fulla.ui.theme.FullaType.title, color = FullaTheme.colors.inkMuted)
                to?.let { io.github.sirallap.fulla.ui.components.MemberBadge(it.initials, it.colorIndex) }
                Text(stringResource(R.string.settle_pays, view.memberName(p.fromMemberId), view.memberName(p.toMemberId)),
                    style = io.github.sirallap.fulla.ui.theme.FullaType.body, color = FullaTheme.colors.ink)
            }
            androidx.compose.material3.OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settle_amount)) }, singleLine = true, suffix = { Text(f.currency.code) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                supportingText = { Text(stringResource(R.string.settle_help)) })
            io.github.sirallap.fulla.ui.components.FormLabel(stringResource(R.string.settle_account))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                for (a in view.config.accounts.filter { !it.archived }) {
                    io.github.sirallap.fulla.ui.components.Chip(a.name, a.id == account, { account = a.id })
                }
            }
        }
    }
}

