// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.remote.Update
import io.github.sirallap.fulla.core.roles.Permissions
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.BackHeader
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.rememberChange
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch

enum class SettingsSection(val route: String, val title: Int, val icon: ImageVector) {
    INDEX("index", R.string.settings, Icons.Outlined.Info),
    HOUSEHOLDS("households", R.string.settings_households, Icons.Outlined.SwapHoriz),
    HOUSEHOLD("household", R.string.settings_household, Icons.Outlined.Home),
    MEMBERS("members", R.string.settings_members, Icons.Outlined.Group),
    CATEGORIES("categories", R.string.settings_categories, Icons.Outlined.Category),
    FIELDS("fields", R.string.settings_fields, Icons.Outlined.Tune),
    ACCOUNTS("accounts", R.string.settings_accounts, Icons.Outlined.AccountBalance),
    BUDGETS("budgets", R.string.settings_budgets, Icons.Outlined.PieChart),
    RECURRING("recurring", R.string.settings_recurring, Icons.Outlined.Event),
    IMPORT("import", R.string.import_statement, Icons.Outlined.FileUpload),
    RULES("rules", R.string.settings_rules, Icons.Outlined.Rule),
    BACKUP("backup", R.string.settings_backup, Icons.Outlined.SaveAlt),
    APPEARANCE("appearance", R.string.settings_appearance, Icons.Outlined.Palette),
    SYNC("sync", R.string.settings_sync, Icons.Outlined.Cloud),
    ABOUT("about", R.string.settings_about, Icons.Outlined.Info),
}

/**
 * Settings, and nothing but settings. Each section is its own screen;
 * sections a person's role cannot change are shown read-only, never hidden,
 * so everyone sees the same household.
 */
@Composable
fun SettingsScreen(view: HouseholdView, section: SettingsSection, onBack: () -> Unit, onOpen: (SettingsSection) -> Unit, onUpdate: () -> Unit = {}) {
    val container = LocalContainer.current
    var error by remember { mutableStateOf<String?>(null) }
    val me = view.me
    val canEdit = me != null && Permissions.canEditStructure(me)
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val pendingUpdate = settings?.pendingUpdate

    val change = rememberChange(view) { error = it }

    Column(Modifier.fillMaxSize()) {
        BackHeader(stringResource(section.title), onBack)
        error?.let { Text(it, style = FullaType.secondary, color = FullaTheme.colors.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                SettingsSection.INDEX -> index(view, onBack, onOpen, pendingUpdate, onUpdate)
                SettingsSection.HOUSEHOLDS -> item { HouseholdsSettings(view, onBack) }
                SettingsSection.HOUSEHOLD -> item { HouseholdSettings(view, canEdit, change) }
                SettingsSection.MEMBERS -> item { MembersSettings(view, change, onShare = { onOpen(SettingsSection.SYNC) }) }
                SettingsSection.CATEGORIES -> item { CategoriesSettings(view, canEdit, change) }
                SettingsSection.FIELDS -> item { FieldsSettings(view, canEdit, change) }
                SettingsSection.ACCOUNTS -> item { AccountsSettings(view, canEdit, change) }
                SettingsSection.BUDGETS -> item { BudgetsSettings(view, canEdit, change) }
                SettingsSection.RECURRING -> item { RecurringSettings(view, canEdit, change) }
                SettingsSection.IMPORT -> item { ImportSettings(view, change) }
                SettingsSection.RULES -> item { RulesSettings(view, canEdit, change) }
                SettingsSection.BACKUP -> item { BackupSettings(view) }
                SettingsSection.APPEARANCE -> item { AppearanceSettings() }
                SettingsSection.SYNC -> item { SyncSettings(view, onBack, onInvite = { onOpen(SettingsSection.MEMBERS) }) }
                SettingsSection.ABOUT -> item { AboutSettings() }
            }
        }
    }
}

private fun LazyListScope.index(view: HouseholdView, onBack: () -> Unit, onOpen: (SettingsSection) -> Unit, pendingUpdate: Update?, onUpdate: () -> Unit) {
    if (pendingUpdate != null) {
        item {
            ListRow(
                stringResource(R.string.update_available, pendingUpdate.version),
                icon = Icons.Outlined.SystemUpdate,
                iconTint = FullaTheme.colors.accent,
                titleColor = FullaTheme.colors.accent,
                onClick = onUpdate,
                end = { Icon(Icons.Outlined.ChevronRight, null, tint = FullaTheme.colors.accent) },
            )
        }
    }
    items(SettingsSection.entries.filter { it != SettingsSection.INDEX }, key = { it.route }) { s ->
        ListRow(stringResource(s.title), icon = s.icon, onClick = { onOpen(s) },
            end = { Icon(Icons.Outlined.ChevronRight, null, tint = FullaTheme.colors.inkMuted) })
    }
    item {
        val container = LocalContainer.current
        val scope = rememberCoroutineScope()
        ListRow(stringResource(R.string.settings_show_guide), icon = Icons.Outlined.Info, onClick = {
            scope.launch {
                container.settings.startGuide(view.id, io.github.sirallap.fulla.core.guide.GuideOrigin.REPLAY)
                onBack()
            }
        })
    }
}
