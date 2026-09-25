// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.theme.FullaTheme
import kotlinx.coroutines.launch

/** Every household on this phone, the active one ticked; and a way to add another. */
@Composable
fun HouseholdsSettings(view: HouseholdView, onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val all by container.ledger.all.collectAsStateWithLifecycle(initialValue = emptyList())
    Column {
        for (h in all.sortedBy { it.config.household.name.lowercase() }) {
            ListRow(
                h.config.household.name,
                context = stringResource(if (h.connected) R.string.shared else R.string.on_this_phone) + " · " + h.config.household.currency,
                onClick = { scope.launch { container.settings.setActiveHousehold(h.id); onBack() } },
                end = { if (h.id == view.id) Icon(Icons.Outlined.Check, stringResource(R.string.current), tint = FullaTheme.colors.accent) },
            )
        }
        ListRow(stringResource(R.string.add_household), context = stringResource(R.string.add_household_text), icon = Icons.Outlined.Add,
            onClick = { container.addingHousehold.value = true })
    }
}
