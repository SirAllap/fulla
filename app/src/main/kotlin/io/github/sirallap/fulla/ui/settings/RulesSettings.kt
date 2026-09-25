// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * "When a statement line says X, it is category Y." Rules are made while
 * importing, one tap per merchant; here they can be paused and resumed.
 * Fulla ships none: guessed merchant patterns would put wrong categories on
 * real money.
 */
@Composable
fun RulesSettings(view: HouseholdView, canEdit: Boolean, change: Change) {
    val ledger = LocalContainer.current.ledger
    val c = FullaTheme.colors
    val rules = view.state.rules.sortedWith(compareBy({ !it.active }, { it.sort }))
    Column {
        Text(stringResource(R.string.rules_text), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(20.dp))
        if (rules.isEmpty()) EmptyState(Icons.Outlined.Rule, stringResource(R.string.no_rules_title), stringResource(R.string.no_rules_text))
        for (r in rules) {
            ListRow("“${r.pattern}”", titleColor = if (r.active) c.ink else c.inkMuted,
                context = "→ " + (view.categoryName(r.categoryId) ?: stringResource(R.string.uncategorized)),
                detail = if (!r.active) stringResource(R.string.paused) else null,
                onClick = if (canEdit) ({
                    val stored = Wire.list(view.state.bundle["categorization_rules"]).firstOrNull { (it["id"] as? JsonPrimitive)?.content == r.id }
                    if (stored != null) change { api -> ledger.upsert(view.id, Structure.RULE, JsonObject(stored + ("active" to JsonPrimitive(!r.active))), api) }
                }) else null)
        }
    }
}
