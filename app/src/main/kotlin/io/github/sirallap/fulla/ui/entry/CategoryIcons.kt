// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.entry

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Category icons by their stored name (Material Symbols names), so a
 * category chosen on one phone shows the same icon on another. An unknown
 * name falls back to a label, never to nothing.
 */
object CategoryIcons {
    val all: Map<String, ImageVector> = linkedMapOf(
        "home" to Icons.Outlined.Home,
        "shopping_cart" to Icons.Outlined.ShoppingCart,
        "restaurant" to Icons.Outlined.Restaurant,
        "local_cafe" to Icons.Outlined.LocalCafe,
        "directions_bus" to Icons.Outlined.DirectionsBus,
        "directions_car" to Icons.Outlined.DirectionsCar,
        "local_gas_station" to Icons.Outlined.LocalGasStation,
        "bolt" to Icons.Outlined.Bolt,
        "smartphone" to Icons.Outlined.Smartphone,
        "subscriptions" to Icons.Outlined.Subscriptions,
        "favorite" to Icons.Outlined.FavoriteBorder,
        "local_hospital" to Icons.Outlined.LocalHospital,
        "fitness_center" to Icons.Outlined.FitnessCenter,
        "shopping_bag" to Icons.Outlined.ShoppingBag,
        "sports_esports" to Icons.Outlined.SportsEsports,
        "theater_comedy" to Icons.Outlined.TheaterComedy,
        "flight" to Icons.Outlined.Flight,
        "school" to Icons.Outlined.School,
        "child_care" to Icons.Outlined.ChildCare,
        "pets" to Icons.Outlined.Pets,
        "redeem" to Icons.Outlined.CardGiftcard,
        "work" to Icons.Outlined.Work,
        "payments" to Icons.Outlined.Payments,
        "savings" to Icons.Outlined.Savings,
        "more_horiz" to Icons.Outlined.MoreHoriz,
        "shield" to Icons.Outlined.Shield,
        "content_cut" to Icons.Outlined.ContentCut,
        "confirmation_number" to Icons.Outlined.ConfirmationNumber,
        "account_balance" to Icons.Outlined.AccountBalance,
        "checkroom" to Icons.Outlined.Checkroom,
        "help" to Icons.Outlined.HelpOutline,
        "label" to Icons.Outlined.Label,
    )

    fun of(name: String): ImageVector = all[name] ?: Icons.Outlined.Label
}
