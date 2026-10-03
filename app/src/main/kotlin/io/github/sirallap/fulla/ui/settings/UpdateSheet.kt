// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.Update
import io.github.sirallap.fulla.core.version.ReleaseNotes
import io.github.sirallap.fulla.data.update.InstallOutcome
import io.github.sirallap.fulla.data.update.InstallStatus
import io.github.sirallap.fulla.data.update.Installer
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A release's size, to the nearest tenth of a megabyte; there is no need for more precision here. */
private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

/**
 * What tapping the pending-update row or the header's dot opens: the
 * release's notes and size, and the button that downloads and installs it.
 */
@Composable
fun UpdateSheet(update: Update, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    var progress by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { needsPermission = false }

    LaunchedEffect(Unit) {
        InstallStatus.events.collect { outcome ->
            when (outcome) {
                InstallOutcome.Success -> { progress = null; scope.launch { container.checkDbSchema() }; onDismiss() }
                is InstallOutcome.Failure -> { progress = null; error = outcome.message ?: context.getString(R.string.something_failed) }
            }
        }
    }

    fun start() {
        val installer = Installer(context)
        if (!installer.canInstall()) {
            needsPermission = true
            return
        }
        needsPermission = false
        error = null
        progress = 0
        scope.launch {
            val dest = File(File(context.cacheDir, "updates"), "fulla-${update.version}.apk")
            try {
                container.updateCheck.download(update, dest) { read, total ->
                    if (total > 0) progress = ((read * 100) / total).toInt()
                }
                withContext(Dispatchers.IO) { installer.install(dest) }
                // The session already has its own copy; the download is spent either way.
            } catch (e: Exception) {
                progress = null
                error = (e as? FullaError)?.message ?: context.getString(R.string.something_failed)
            } finally {
                dest.delete()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.paper) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp)) {
            Text(stringResource(R.string.update_available, update.version), style = FullaType.title, color = c.ink)
            Text(stringResource(R.string.update_why), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(top = 4.dp))
            if (update.sizeBytes > 0) {
                Text(megabytes(update.sizeBytes), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(top = 4.dp))
            }
            if (update.notes.isNotBlank()) {
                androidx.compose.foundation.layout.Box(Modifier.padding(top = 16.dp)) { ReleaseNotesText(update.notes) }
            }
            error?.let { Text(it, style = FullaType.secondary, color = c.danger, modifier = Modifier.padding(top = 16.dp)) }
            if (needsPermission) {
                Text(stringResource(R.string.update_permission_explain), style = FullaType.secondary, color = c.inkMuted,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                PrimaryButton(stringResource(R.string.update_permission_action),
                    { permissionLauncher.launch(Installer(context).unknownSourcesIntent()) })
            } else {
                PrimaryButton(
                    text = progress?.let { stringResource(R.string.update_downloading, it) } ?: stringResource(R.string.update_now),
                    onClick = ::start,
                    busy = progress != null,
                    modifier = Modifier.padding(top = 20.dp),
                )
                // Not now: it stays in Settings (the gear's dot and the banner), and the app mentions it again tomorrow.
                if (progress == null) androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_later))
                }
            }
        }
    }
}

/**
 * A release's notes with their formatting (bold, bullets, headings) and in the
 * phone's language when the notes have one (see ReleaseNotes), instead of the
 * raw Markdown the release is written in.
 */
@Composable
private fun ReleaseNotesText(notes: String) {
    val c = FullaTheme.colors
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val blocks = remember(notes, language) {
        runCatching { ReleaseNotes.parse(ReleaseNotes.forLanguage(notes, language)) }.getOrNull()
    }
    if (blocks == null) {
        Text(notes, style = FullaType.body, color = c.ink)
        return
    }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            val text = androidx.compose.ui.text.buildAnnotatedString {
                for (span in block.spans) {
                    withStyle(androidx.compose.ui.text.SpanStyle(
                        fontWeight = if (span.bold) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                        fontStyle = if (span.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                        fontFamily = if (span.code) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                    )) { append(span.text) }
                }
            }
            when (block) {
                is ReleaseNotes.Block.Heading -> Text(text, style = FullaType.title, color = c.ink)
                is ReleaseNotes.Block.Paragraph -> Text(text, style = FullaType.body, color = c.ink)
                is ReleaseNotes.Block.Bullet -> androidx.compose.foundation.layout.Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Text("•", style = FullaType.body, color = c.inkMuted)
                    Text(text, style = FullaType.body, color = c.ink, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
