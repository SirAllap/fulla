// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.local.Backup
import io.github.sirallap.fulla.data.repo.Ledger
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Reads a backup file and restores it; the message to show, or null on success. */
/** The project a shared household's backup lives in, or null (a phone-only household, an older file, not a backup). */
suspend fun backupProject(context: Context, uri: Uri): io.github.sirallap.fulla.client.remote.Endpoint? {
    val text = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    } ?: return null
    return runCatching { Backup.read(text).project }.getOrNull()
}

suspend fun restoreBackup(context: Context, ledger: Ledger, uri: Uri): Int? {
    val text = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    } ?: return R.string.backup_not_readable
    val contents = try {
        Backup.read(text)
    } catch (e: Backup.Unreadable) {
        return when (e.reason) {
            // A bank statement or another app's export picked here by mistake: say where it goes.
            Backup.Reason.NOT_A_BACKUP -> if (io.github.sirallap.fulla.core.importers.CsvImporter.detect(text.toByteArray()) > 0.0)
                R.string.backup_is_csv else R.string.backup_not_a_backup
            Backup.Reason.NEWER_VERSION -> R.string.backup_newer
            Backup.Reason.DAMAGED -> R.string.backup_damaged
        }
    }
    return if (ledger.restore(contents)) null else R.string.backup_already_here
}

@Composable
fun BackupSettings(view: HouseholdView) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    var message by remember { mutableStateOf<Int?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = container.ledger.backup(view.id) ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
            }
            if (ok) container.settings.setLastBackup(view.id, System.currentTimeMillis())
            message = if (ok) R.string.backup_saved else R.string.something_failed
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { message = restoreBackup(context, container.ledger, uri) ?: R.string.backup_restored }
    }

    val last by remember(view.id) { container.settings.lastBackup(view.id) }.collectAsStateWithLifecycle(initialValue = -1L)
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = io.github.sirallap.fulla.client.local.CsvExport.write(view.config, view.rows.map { it.transaction })
            val ok = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } } }.isSuccess
            message = if (ok) R.string.backup_saved else R.string.something_failed
        }
    }
    val c2 = FullaTheme.colors
    // As in the Copia board: how old the last backup is and why it matters, then the three things to do, each a card.
    Column(Modifier.padding(top = 8.dp)) {
        val age = (last ?: 0L).takeIf { it > 0 }?.let { ((System.currentTimeMillis() - it) / (24 * 3600 * 1000L)).toInt() }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                .background(if (!view.state.connected && (age == null || age > 30)) c2.ink else c2.paperHigh).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val dark = !view.state.connected && (age == null || age > 30)
            Text(when { age == null -> stringResource(R.string.backup_never); age == 0 -> stringResource(R.string.backup_last_today)
                else -> stringResource(R.string.backup_last_days, age) }, style = FullaType.title, color = if (dark) c2.paper else c2.ink)
            Text(stringResource(if (view.state.connected) R.string.backup_text_shared else R.string.backup_text_local),
                style = FullaType.secondary, color = if (dark) c2.paper.copy(alpha = 0.75f) else c2.inkMuted)
        }
        for ((title, text, action) in listOf(
            Triple(R.string.backup_save, R.string.backup_save_text) { save.launch("fulla-${view.config.household.name.filter { it.isLetterOrDigit() }.lowercase()}-${LocalDate.now()}.json") },
            Triple(R.string.backup_restore, R.string.backup_restore_text) { open.launch(arrayOf("*/*")) },
            Triple(R.string.export_csv, R.string.backup_export_text) { csv.launch("fulla-${LocalDate.now()}.csv") },
        )) {
            io.github.sirallap.fulla.ui.components.ItemCard(onClick = action) {
                Text(stringResource(title), style = FullaType.body, color = c2.ink)
                Text(stringResource(text), style = FullaType.secondary, color = c2.inkMuted)
            }
        }
        message?.let { Text(stringResource(it), style = FullaType.body, color = c2.ink, modifier = Modifier.padding(20.dp)) }
    }
}
