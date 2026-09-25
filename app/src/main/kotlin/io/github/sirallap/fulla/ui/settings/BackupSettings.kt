// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

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

    Column {
        Text(stringResource(if (view.state.connected) R.string.backup_text_shared else R.string.backup_text_local),
            style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(20.dp))
        ListRow(stringResource(R.string.backup_save), context = stringResource(R.string.backup_save_text), icon = Icons.Outlined.FileDownload,
            onClick = { save.launch("fulla-${view.config.household.name.filter { it.isLetterOrDigit() }.lowercase()}-${LocalDate.now()}.json") })
        ListRow(stringResource(R.string.backup_restore), context = stringResource(R.string.backup_restore_text), icon = Icons.Outlined.FileUpload,
            onClick = { open.launch(arrayOf("*/*")) })
        message?.let { Text(stringResource(it), style = FullaType.body, color = c.ink, modifier = Modifier.padding(20.dp)) }
    }
}
