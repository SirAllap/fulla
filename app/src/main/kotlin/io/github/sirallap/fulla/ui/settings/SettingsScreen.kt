// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import io.github.sirallap.fulla.ui.components.listEndPadding
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Storage
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sirallap.fulla.AppContainer
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.remote.Update
import io.github.sirallap.fulla.client.remote.supabaseProjectRef
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
    TRIPS("trips", R.string.settings_trips, Icons.Outlined.Luggage),
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
    val dbNeedsUpdate = settings?.dbNeedsUpdate == true
    val projectUrl = settings?.endpoint?.url

    val change = rememberChange(view) { error = it }

    Column(Modifier.fillMaxSize()) {
        BackHeader(stringResource(section.title), onBack)
        error?.let { Text(it, style = FullaType.secondary, color = FullaTheme.colors.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = listEndPadding()) {
            when (section) {
                SettingsSection.INDEX -> index(view, onBack, onOpen, pendingUpdate, onUpdate, dbNeedsUpdate, projectUrl)
                SettingsSection.HOUSEHOLDS -> item { HouseholdsSettings(view, onBack) }
                SettingsSection.HOUSEHOLD -> item { HouseholdSettings(view, canEdit, change) }
                SettingsSection.MEMBERS -> item { MembersSettings(view, change, onShare = { onOpen(SettingsSection.SYNC) }) }
                SettingsSection.CATEGORIES -> item { CategoriesSettings(view, canEdit, change) }
                SettingsSection.FIELDS -> item { FieldsSettings(view, canEdit, change) }
                SettingsSection.ACCOUNTS -> item { AccountsSettings(view, canEdit, change) }
                SettingsSection.BUDGETS -> item { BudgetsSettings(view, canEdit, change) }
                SettingsSection.TRIPS -> item { TripsSettings(view, change) }
                SettingsSection.RECURRING -> item { RecurringSettings(view, canEdit, change) }
                SettingsSection.IMPORT -> item { ImportSettings(view, change) }
                SettingsSection.RULES -> item { RulesSettings(view, canEdit, change) }
                SettingsSection.BACKUP -> item { BackupSettings(view) }
                SettingsSection.APPEARANCE -> item { AppearanceSettings() }
                SettingsSection.SYNC -> item { SyncSettings(view, onBack, onInvite = { onOpen(SettingsSection.MEMBERS) }) }
                SettingsSection.ABOUT -> item { AboutSettings(onUpdate) }
            }
        }
    }
}

private fun LazyListScope.index(
    view: HouseholdView, onBack: () -> Unit, onOpen: (SettingsSection) -> Unit,
    pendingUpdate: Update?, onUpdate: () -> Unit,
    dbNeedsUpdate: Boolean, projectUrl: String?,
) {
    // A new version stays here, at the top, until it is installed: it is never dismissed away.
    if (pendingUpdate != null) item { UpdateBanner(pendingUpdate, onUpdate) }
    if (dbNeedsUpdate) {
        item {
            val container = LocalContainer.current
            val context = LocalContext.current
            val clipboard = LocalClipboardManager.current
            val scope = rememberCoroutineScope()
            ListRow(
                stringResource(R.string.db_update_needed),
                context = stringResource(R.string.db_update_action),
                detail = stringResource(R.string.db_update_instruction),
                icon = Icons.Outlined.Storage,
                iconTint = FullaTheme.colors.accent,
                titleColor = FullaTheme.colors.accent,
                onClick = { scope.launch { updateDatabase(container, context, clipboard, projectUrl) } },
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
    // At the bottom, where the list ends: no need to go looking for it in About.
    item { CheckUpdatesRow(onUpdate) }
}

/** A new version, impossible to miss: what it is for and the button, at the top of Settings. */
@Composable
private fun UpdateBanner(update: Update, onUpdate: () -> Unit) {
    val c = FullaTheme.colors
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().padding(16.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(c.paperHigh).padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.SystemUpdate, null, tint = c.accent)
            Text(stringResource(R.string.update_available, update.version), style = FullaType.title, color = c.ink)
        }
        Text(stringResource(R.string.update_why), style = FullaType.secondary, color = c.inkMuted)
        io.github.sirallap.fulla.ui.components.PrimaryButton(stringResource(R.string.update_now), onUpdate)
    }
}

/**
 * Asks right away whether there is a newer version (here, at the bottom of
 * Settings, and in About). When there is, the update sheet opens on the spot:
 * the person never has to go back and look for it.
 */
@Composable
fun CheckUpdatesRow(onFound: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<AppContainer.UpdateCheckResult?>(null) }
    val result = when (checked) {
        AppContainer.UpdateCheckResult.FOUND -> stringResource(R.string.update_found_text)
        AppContainer.UpdateCheckResult.UP_TO_DATE -> stringResource(R.string.update_up_to_date)
        AppContainer.UpdateCheckResult.FAILED -> stringResource(R.string.update_check_failed)
        AppContainer.UpdateCheckResult.SKIPPED -> stringResource(R.string.update_test_build)
        null -> null
    }
    ListRow(
        stringResource(if (checking) R.string.update_checking else R.string.update_check_now),
        context = result ?: stringResource(R.string.version, io.github.sirallap.fulla.BuildConfig.VERSION_NAME),
        icon = Icons.Outlined.SystemUpdate,
        end = if (checking) ({
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }) else null,
        onClick = if (checking) null else ({
            checking = true
            scope.launch {
                checked = container.checkForUpdates(now = true)
                checking = false
                if (checked == AppContainer.UpdateCheckResult.FOUND) onFound()
            }
        }),
    )
}

/**
 * Copies only the migrations the backend is missing (`AppContainer.dbUpdateSql`,
 * a few KB) rather than the whole `setup.sql` (227 KB) -- pasting that much
 * into Supabase's SQL Editor in a phone browser froze it. Opens the
 * project's own SQL editor, ready to paste. Silently does nothing to the
 * browser step when the configured URL's ref cannot be parsed (a local-only
 * household with no project yet) -- the clipboard copy still happens, so a
 * person who navigates there by hand is not left empty-handed.
 */
private suspend fun updateDatabase(container: AppContainer, context: android.content.Context, clipboard: androidx.compose.ui.platform.ClipboardManager, projectUrl: String?) {
    clipboard.setText(AnnotatedString(container.dbUpdateSql()))
    // Older Androids say nothing when something is copied.
    android.widget.Toast.makeText(context, R.string.db_update_copied, android.widget.Toast.LENGTH_LONG).show()
    val ref = projectUrl?.let(::supabaseProjectRef) ?: return
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://supabase.com/dashboard/project/$ref/sql/new")))
}
