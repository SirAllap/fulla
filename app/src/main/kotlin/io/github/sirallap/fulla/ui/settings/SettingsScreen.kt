// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.material.icons.outlined.Flag
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
import io.github.sirallap.fulla.ui.components.CardGroup
import io.github.sirallap.fulla.ui.components.GroupTitle
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
    GOALS("goals", R.string.settings_goals, Icons.Outlined.Flag),
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

    // A section's own primary action ("Add a fixed cost") is pinned at the bottom of the screen, not a row of its list.
    val primary = remember(section) { io.github.sirallap.fulla.ui.components.PrimaryActionHolder() }
    Column(Modifier.fillMaxSize()) {
        BackHeader(stringResource(section.title), onBack)
        error?.let { Text(it, style = FullaType.secondary, color = FullaTheme.colors.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
        androidx.compose.runtime.CompositionLocalProvider(io.github.sirallap.fulla.ui.components.LocalPrimaryAction provides primary) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = if (primary.action != null) androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp) else listEndPadding()) {
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
                SettingsSection.GOALS -> item { GoalsSettings(view, change) }
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
        io.github.sirallap.fulla.ui.components.PrimaryActionBar(primary)
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
            val ref = projectUrl?.let(::supabaseProjectRef)
            var asking by remember { mutableStateOf(false) }
            if (asking && ref != null) DbUpdateDialog(ref, onDismiss = { asking = false },
                onCopyInstead = { asking = false; scope.launch { updateDatabase(container, context, clipboard, projectUrl) } })
            ListRow(
                stringResource(R.string.db_update_needed),
                context = stringResource(R.string.db_update_action),
                detail = stringResource(R.string.db_update_instruction),
                icon = Icons.Outlined.Storage,
                iconTint = FullaTheme.colors.accent,
                titleColor = FullaTheme.colors.accent,
                // A project on supabase.com updates itself with a token for the moment; any other copies the SQL, as before.
                onClick = { if (ref != null) asking = true else scope.launch { updateDatabase(container, context, clipboard, projectUrl) } },
                end = { Icon(Icons.Outlined.ChevronRight, null, tint = FullaTheme.colors.accent) },
            )
        }
    }
    // As in the board: a search over every section, then four blocks, each row with what it holds on the right.
    item(key = "groups") {
        var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        androidx.compose.material3.OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.settings_search)) }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) })
        val q = query.trim().lowercase()
        @Composable
        fun row(s: SettingsSection, divider: Boolean) {
            val count: String? = when (s) {
                SettingsSection.MEMBERS -> view.config.activeMembers.size.toString()
                SettingsSection.ACCOUNTS -> view.config.accounts.count { !it.archived }.toString()
                SettingsSection.RECURRING -> view.config.recurringRules.count { !it.archived && it.active }.takeIf { it > 0 }?.toString()
                SettingsSection.GOALS -> view.config.goals.count { !it.archived }.takeIf { it > 0 }?.toString()
                SettingsSection.TRIPS -> view.config.trips.count { !it.archived }.takeIf { it > 0 }?.toString()
                else -> null
            }
            ListRow(stringResource(s.title), icon = s.icon, onClick = { onOpen(s) }, divider = divider,
                end = {
                    count?.let { Text(it, style = io.github.sirallap.fulla.ui.theme.FullaType.secondary, color = FullaTheme.colors.inkMuted) }
                    Icon(Icons.Outlined.ChevronRight, null, tint = FullaTheme.colors.inkMuted)
                })
        }
        if (q.isNotEmpty()) {
            val found = SettingsGroup.entries.flatMap { it.sections }.filter { stringResource(it.title).lowercase().contains(q) }
            CardGroup { found.forEachIndexed { i, s -> row(s, i < found.lastIndex) } }
            if (found.isEmpty()) Text(stringResource(R.string.settings_search_none), color = FullaTheme.colors.inkMuted, modifier = Modifier.padding(20.dp))
        } else for (group in SettingsGroup.entries) {
            GroupTitle(stringResource(group.title), stringResource(group.help), top = if (group.ordinal == 0) 12.dp else 28.dp)
            CardGroup {
                val sections = group.sections
                sections.forEachIndexed { i, s -> row(s, i < sections.lastIndex || group == SettingsGroup.APP) }
                if (group == SettingsGroup.APP) {
                    val container = LocalContainer.current
                    val scope = rememberCoroutineScope()
                    ListRow(stringResource(R.string.settings_show_guide), icon = Icons.Outlined.Info, onClick = {
                        scope.launch {
                            container.settings.startGuide(view.id, io.github.sirallap.fulla.core.guide.GuideOrigin.REPLAY)
                            onBack()
                        }
                    })
                    // At the end, where the list ends: no need to go looking for it in About.
                    CheckUpdatesRow(onUpdate, divider = false)
                }
            }
        }
    }
}

enum class SettingsGroup(val title: Int, val help: Int, val sections: List<SettingsSection>) {
    // As in the Ajustes board: the household, planning, its data, and what is rarely touched.
    HOME(R.string.settings_group_home, R.string.settings_group_home_help,
        listOf(SettingsSection.HOUSEHOLD, SettingsSection.MEMBERS, SettingsSection.ACCOUNTS, SettingsSection.CATEGORIES)),
    MONEY(R.string.settings_group_money, R.string.settings_group_money_help,
        listOf(SettingsSection.RECURRING, SettingsSection.BUDGETS, SettingsSection.GOALS, SettingsSection.TRIPS)),
    DATA(R.string.settings_group_data, R.string.settings_group_data_help,
        listOf(SettingsSection.IMPORT, SettingsSection.BACKUP, SettingsSection.SYNC)),
    APP(R.string.settings_group_app, R.string.settings_group_app_help,
        listOf(SettingsSection.RULES, SettingsSection.FIELDS, SettingsSection.APPEARANCE, SettingsSection.HOUSEHOLDS, SettingsSection.ABOUT)),
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
fun CheckUpdatesRow(onFound: () -> Unit, divider: Boolean = true) {
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
        divider = divider,
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

/**
 * "Update database" without the SQL editor: the person pastes a Supabase
 * access token and the app runs the missing migrations itself
 * (`AppContainer.updateDatabase`). The token lives in this dialog only and is
 * dropped with it, as at setup: it opens the whole Supabase account. Copying
 * the SQL stays, for whoever would rather paste it.
 */
@Composable
private fun DbUpdateDialog(ref: String, onDismiss: () -> Unit, onCopyInstead: () -> Unit) {
    val container = LocalContainer.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val refused = stringResource(R.string.setup_token_refused)
    val unreachable = stringResource(R.string.project_unreachable)
    val failed = stringResource(R.string.db_update_failed)
    io.github.sirallap.fulla.ui.components.FullaDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (done) R.string.db_update_done else R.string.db_update_action)) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                if (done) {
                    Text(stringResource(R.string.setup_token_delete), style = FullaType.body, color = c.ink)
                    androidx.compose.material3.TextButton(onClick = { uri.openUri(io.github.sirallap.fulla.client.remote.ProjectSetup.TOKEN_PAGE) }) {
                        Text(stringResource(R.string.setup_token_delete_link), style = FullaType.body, color = c.accent)
                    }
                } else {
                    Text(stringResource(R.string.db_update_token_text), style = FullaType.body, color = c.ink)
                    androidx.compose.material3.TextButton(onClick = { uri.openUri(io.github.sirallap.fulla.client.remote.ProjectSetup.TOKEN_PAGE) }) {
                        Text(stringResource(R.string.db_update_get_token), style = FullaType.body, color = c.accent)
                    }
                    androidx.compose.material3.OutlinedTextField(token, { token = it.trim() }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setup_token)) }, placeholder = { Text("sbp_…") },
                        singleLine = true, enabled = !busy,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrect = false),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    if (busy) Text(stringResource(R.string.db_update_running), style = FullaType.secondary, color = c.inkMuted)
                    error?.let { Text(it, style = FullaType.secondary, color = c.danger) }
                }
            }
        },
        confirmButton = {
            if (done) androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            else androidx.compose.material3.TextButton(enabled = token.length >= 20 && !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { container.updateDatabase(token, ref) }.fold({ token = ""; done = true }, { e ->
                        error = when ((e as? io.github.sirallap.fulla.client.remote.FullaError)?.code) {
                            io.github.sirallap.fulla.client.remote.ProjectSetup.TOKEN_REFUSED -> refused
                            io.github.sirallap.fulla.client.remote.FullaError.NETWORK -> unreachable
                            else -> failed
                        }
                    })
                    busy = false
                }
            }) { Text(stringResource(R.string.db_update_run)) }
        },
        dismissButton = if (done) null else ({ androidx.compose.material3.TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }),
        neutralButton = if (done) null else ({ androidx.compose.material3.TextButton(enabled = !busy, onClick = onCopyInstead) { Text(stringResource(R.string.db_update_copy)) } }),
    )
}
