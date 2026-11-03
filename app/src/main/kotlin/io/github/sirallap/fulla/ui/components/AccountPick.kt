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
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.Euro
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Work
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * The icons an account can be given, by their stored name (the same kind of name a category's icon
 * has), so an icon chosen on one phone shows on another. A name this phone does not know is drawn
 * as the icon of the account's kind, never as nothing.
 */
object AccountIcons {
    val all: Map<String, ImageVector> = linkedMapOf(
        "payments" to Icons.Outlined.Payments,
        "account_balance" to Icons.Outlined.AccountBalance,
        "savings" to Icons.Outlined.Savings,
        "credit_card" to Icons.Outlined.CreditCard,
        "wallet" to Icons.Outlined.AccountBalanceWallet,
        "euro" to Icons.Outlined.Euro,
        "home" to Icons.Outlined.Home,
        "shopping_cart" to Icons.Outlined.ShoppingCart,
        "restaurant" to Icons.Outlined.Restaurant,
        "local_cafe" to Icons.Outlined.LocalCafe,
        "directions_car" to Icons.Outlined.DirectionsCar,
        "flight" to Icons.Outlined.Flight,
        "work" to Icons.Outlined.Work,
        "school" to Icons.Outlined.School,
        "child_care" to Icons.Outlined.ChildCare,
        "pets" to Icons.Outlined.Pets,
        "redeem" to Icons.Outlined.CardGiftcard,
        "favorite" to Icons.Outlined.FavoriteBorder,
        "fitness_center" to Icons.Outlined.FitnessCenter,
        "sports_esports" to Icons.Outlined.SportsEsports,
        "smartphone" to Icons.Outlined.Smartphone,
        "bolt" to Icons.Outlined.Bolt,
        "shield" to Icons.Outlined.Shield,
        "lock" to Icons.Outlined.Lock,
        "star" to Icons.Outlined.StarBorder,
        "diamond" to Icons.Outlined.Diamond,
        "public" to Icons.Outlined.Public,
    )
}

/** The colour an account wears is its place in the household's list, so every phone agrees. */
fun accountColorIndex(accounts: List<Account>, id: String?): Int = accounts.indexOfFirst { it.id == id }.coerceAtLeast(0)

/** Every small chip of a movement is this tall, with its content centred, so a row of them lines up. */
private val ChipHeight = 26.dp

/** A chip's fill: a touch of the ink over the card, so it shows on the card in both themes. */
@Composable
private fun ChipFill() = FullaTheme.colors.ink.copy(alpha = 0.10f)

/** Which account, in a few words: its icon and its name inside a dashed outline of its colour. */
@Composable
fun AccountLabel(account: Account, colorIndex: Int, modifier: Modifier = Modifier) {
    val tint = FullaTheme.colors.category(colorIndex)
    Row(
        modifier.heightIn(min = ChipHeight).drawBehind {
            val r = size.height / 2
            drawRoundRect(tint, cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))))
        }.padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(accountIcon(account), null, tint = tint, modifier = Modifier.size(13.dp))
        Text(account.name, style = FullaType.label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
    }
}

/** A small fact about a movement, in a quiet pill, with a symbol before it when it has one. */
@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = FullaTheme.colors
    Row(modifier.heightIn(min = ChipHeight).widthIn(max = 220.dp).clip(RoundedCornerShape(50)).background(ChipFill())
        .padding(start = if (icon != null) 6.dp else 8.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon != null) Icon(icon, null, tint = c.inkMuted, modifier = Modifier.size(12.dp))
        Text(text, style = FullaType.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A person as a small chip: their colour and initial, then their name. */
@Composable
fun PersonChip(name: String, initials: String, colorIndex: Int, modifier: Modifier = Modifier) {
    val c = FullaTheme.colors
    Row(modifier.heightIn(min = ChipHeight).clip(RoundedCornerShape(50)).background(ChipFill()).padding(start = 4.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(c.member(colorIndex)), contentAlignment = Alignment.Center) {
            Text(initials.take(1).uppercase(), style = FullaType.label.copy(fontSize = 10.sp, lineHeight = 12.sp), color = c.paper, maxLines = 1)
        }
        Text(name, style = FullaType.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
    }
}

/** The icon of one account: the one it was given, or else that of its kind. */
fun accountIcon(account: Account?): ImageVector =
    account?.icon?.let { AccountIcons.all[it] } ?: accountIcon(account?.type ?: AccountType.OTHER)

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
                Icon(accountIcon(account), null, tint = c.onHighlight, modifier = Modifier.size(30.dp))
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
