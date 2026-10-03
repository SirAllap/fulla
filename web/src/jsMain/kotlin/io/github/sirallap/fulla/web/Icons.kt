// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

/** The category icons the app stores by their Material names, drawn here with the emoji that mean the same. */
object Icons {
    private val emoji = mapOf(
        "bolt" to "⚡", "directions_bus" to "🚌", "favorite" to "🩺", "help" to "❔", "home" to "🏠", "more_horiz" to "⋯",
        "payments" to "💶", "redeem" to "🎁", "restaurant" to "🍽️", "savings" to "🐷", "school" to "🎓",
        "shopping_bag" to "🛍️", "shopping_cart" to "🛒", "sports_esports" to "🎮", "subscriptions" to "📺",
        "label" to "🏷️", "flight" to "✈️", "pets" to "🐾", "local_hospital" to "🏥", "child_care" to "🧸",
    )

    fun of(name: String): String = emoji[name] ?: "🏷️"
}
