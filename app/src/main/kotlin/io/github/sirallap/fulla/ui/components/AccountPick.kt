// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.sirallap.fulla.ui.theme.FullaMotion
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType

/** The icon of a kind of account: banknotes for cash, a bank for a current account, a piggy bank, a card, a wallet for the rest. */
fun accountIcon(type: AccountType): ImageVector = when (type) {
    AccountType.CASH -> Icons.Outlined.Payments
    AccountType.CHECKING -> Icons.Outlined.AccountBalance
    AccountType.SAVINGS -> Icons.Outlined.Savings
    AccountType.CREDIT_CARD -> Icons.Outlined.CreditCard
    AccountType.OTHER -> Icons.Outlined.AccountBalanceWallet
}

/**
 * "Which account?": every account as a big tile, two across, with its icon, its name (emoji
 * and all) and what it holds, so the right one is found at a glance and nobody saves a card
 * payment in cash by leaving the default. Scrolls with the sheet when there are many.
 */
@Composable
fun AccountGrid(accounts: List<Account>, balances: Map<String, Long>, money: (Long) -> String, onPick: (Account) -> Unit, modifier: Modifier = Modifier) {
    // The tile that is touched fills with liquid and wobbles for a moment, then the account is chosen.
    var chosen by remember { mutableStateOf<String?>(null) }
    val reduced = FullaMotion.reduced()
    LaunchedEffect(chosen) {
        val id = chosen ?: return@LaunchedEffect
        if (!reduced) delay(420)
        accounts.firstOrNull { it.id == id }?.let(onPick)
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in accounts.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (a in row) AccountTile(a, balances[a.id] ?: 0L, money, chosen == a.id, Modifier.weight(1f)) { if (chosen == null) chosen = a.id }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AccountTile(account: Account, balance: Long, money: (Long) -> String, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = FullaTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 132.dp).yields(source).jelly(chosen).clip(shape).background(c.paperHigh).glass().border(1.dp, c.line, shape)
            .clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick),
    ) {
        LiquidSurface(if (chosen) 1.05f else 0f, c.highlight.copy(alpha = if (c.isDark) 0.45f else 0.55f), Modifier.matchParentSize(), maxAmplitude = 5.dp, gloss = false)
        Column(
            Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.highlight).glass(), contentAlignment = Alignment.Center) {
                Icon(accountIcon(account.type), null, tint = c.onHighlight, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.size(10.dp))
            Text(account.name, style = FullaType.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(money(balance), style = FullaType.secondary, color = if (balance < 0) c.danger else c.inkMuted, maxLines = 1, softWrap = false)
        }
    }
}

/** Asked when an entry is saved: which account it goes to. Choosing one saves it. */
@Composable
fun AccountPickSheet(accounts: List<Account>, balances: Map<String, Long>, money: (Long) -> String, onPick: (Account) -> Unit, onDismiss: () -> Unit) {
    PickSheet(stringResource(R.string.account_pick_title), onDismiss) {
        AccountGrid(accounts, balances, money, onPick)
    }
}
