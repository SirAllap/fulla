// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.balances

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
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
fun BalancesScreen(view: HouseholdView, headerActions: @Composable () -> Unit, onHouseholdSettings: () -> Unit = {}) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    val f = view.formats
    val members = view.config.activeMembers
    val balances = remember(view) { Balances.of(view.active, view.config.members.map { it.id }) }
    val plan = remember(balances) { SettlementPlanner.plan(balances) }
    val accounts = remember(view) {
        view.analytics.accountBalances(view.active, view.config.accounts.filter { !it.archived }, LocalDate.now())
    }
    var confirming by remember { mutableStateOf<Payment?>(null) }
    val shared = SharedPot.isShared(view.config.household)

    Column(Modifier.fillMaxSize()) {
        TabHeader(stringResource(R.string.tab_balances), actions = { headerActions() })
        LazyColumn(Modifier.weight(1f)) {
            if (shared) {
                item {
                    EmptyState(Icons.Outlined.Savings, stringResource(R.string.shared_pot_card_title), stringResource(R.string.shared_pot_card_text),
                        action = { TextButton(onClick = onHouseholdSettings) { Text(stringResource(R.string.shared_pot_change)) } })
                }
            } else if (members.size >= 2) {
                item { Section(stringResource(R.string.between_you), top = 8.dp) }
                items(balances.filter { b -> view.config.member(b.memberId)?.isActive == true || b.balanceMinor != 0L }, key = { it.memberId }) { b ->
                    val m = view.config.member(b.memberId)
                    ListRow(
                        title = m?.displayName ?: "?",
                        start = { MemberBadge(m?.initials ?: "?", m?.colorIndex ?: 0) },
                        context = when {
                            b.balanceMinor > 0 -> stringResource(R.string.is_owed)
                            b.balanceMinor < 0 -> stringResource(R.string.owes)
                            else -> stringResource(R.string.settled)
                        },
                        end = {
                            AmountText(f.money(b.balanceMinor, signed = true), color = when {
                                b.balanceMinor > 0 -> c.moneyIn
                                b.balanceMinor < 0 -> c.moneyOut
                                else -> c.inkMuted
                            })
                        },
                    )
                }
                item { Section(stringResource(R.string.to_settle)) }
                if (plan.isEmpty()) {
                    item { EmptyState(Icons.Outlined.CheckCircle, stringResource(R.string.all_settled_title), stringResource(R.string.all_settled_text)) }
                } else {
                    items(plan, key = { "${it.fromMemberId}-${it.toMemberId}" }) { p ->
                        ListRow(
                            title = stringResource(R.string.pays, view.memberName(p.fromMemberId), view.memberName(p.toMemberId)),
                            context = stringResource(R.string.tap_to_record),
                            clickLabel = stringResource(R.string.record_payment),
                            onClick = { confirming = p },
                            end = { AmountText(f.money(p.amountMinor)) },
                        )
                    }
                }
            }
            item { Section(stringResource(R.string.accounts)) }
            items(view.config.accounts.filter { !it.archived }, key = { it.id }) { a ->
                val amount = accounts[a.id] ?: 0
                ListRow(a.name, end = { AmountText(f.money(amount), color = if (amount < 0) c.moneyOut else c.ink) })
            }
            if (shared) {
                item {
                    val total = view.config.accounts.filter { !it.archived }.sumOf { accounts[it.id] ?: 0L }
                    ListRow(stringResource(R.string.household_total), divider = false,
                        end = { AmountText(f.money(total), color = if (total < 0) c.moneyOut else c.ink) })
                }
            }
        }
    }

    confirming?.let { p ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.record_payment)) },
            text = { Text(stringResource(R.string.record_payment_text, view.memberName(p.fromMemberId), f.money(p.amountMinor), view.memberName(p.toMemberId))) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    scope.launch {
                        container.ledger.save(view.id, Transaction(
                            id = UUID.randomUUID().toString(), kind = TransactionKind.SETTLEMENT, date = LocalDate.now(),
                            amountMinor = p.amountMinor, paidByMemberId = p.fromMemberId, toMemberId = p.toMemberId,
                            createdAt = "", clientUpdatedAt = "", createdByMemberId = view.config.meMemberId,
                        ))
                    }
                }) { Text(stringResource(R.string.record)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

