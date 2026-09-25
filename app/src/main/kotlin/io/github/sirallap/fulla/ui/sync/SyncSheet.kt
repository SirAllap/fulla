// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.CopyableText
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.DateFormat
import java.util.Date

/** The one place sync explains itself: state, what was overwritten, what was refused. */
@Composable
fun SyncSheet(view: HouseholdView, onDismiss: () -> Unit, onSettings: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    val status by container.syncStatus.collectAsStateWithLifecycle()
    val pending by remember(view.id) { container.ledger.pendingCount(view.id) }.collectAsStateWithLifecycle(initialValue = 0)
    val notes by remember(view.id) { container.ledger.conflicts(view.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val rejected = view.rows.filter { it.state == io.github.sirallap.fulla.core.sync.SyncState.REJECTED }

    ModalBottomSheet(onDismissRequest = { scope.launch { container.ledger.markConflictsSeen(view.id) }; onDismiss() }, containerColor = c.paper) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            if (!view.state.connected) {
                Text(stringResource(R.string.local_only_explained), style = FullaType.body, color = c.ink, modifier = Modifier.padding(20.dp))
                Column(Modifier.padding(horizontal = 20.dp)) { PrimaryButton(stringResource(R.string.share_household), onSettings) }
                return@Column
            }
            val last = view.state.lastSyncAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
            ListRow(
                title = when {
                    status.running -> stringResource(R.string.syncing)
                    status.needsSignIn -> stringResource(R.string.sync_sign_in_again)
                    pending > 0 -> stringResource(R.string.sync_pending, pending)
                    else -> stringResource(R.string.sync_up_to_date)
                },
                context = last?.let { stringResource(R.string.last_synced, it) } ?: stringResource(R.string.never_synced),
                detail = status.lastError?.message,
                detailColor = c.warning,
            )
            Column(Modifier.padding(20.dp)) {
                PrimaryButton(stringResource(if (status.needsSignIn) R.string.sign_in else R.string.sync_now),
                    { if (status.needsSignIn) onSettings() else scope.launch { container.syncAll() } }, enabled = !status.running)
            }
            if (rejected.isNotEmpty()) {
                Section(stringResource(R.string.refused))
                for (r in rejected) {
                    ListRow(r.transaction.note.ifBlank { view.categoryName(r.transaction.categoryId) ?: "" },
                        context = view.formats.money(r.transaction.amountMinor), detail = r.rejectMessage ?: r.rejectCode, detailColor = c.warning)
                }
            }
            if (notes.isNotEmpty()) {
                Section(stringResource(R.string.overwritten))
                for (n in notes) {
                    val changes = runCatching { Wire.json.parseToJsonElement(n.changesJson).jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
                    ListRow(
                        title = stringResource(if (n.kept == "server") R.string.kept_other_version else R.string.kept_your_version),
                        context = changes.joinToString(" · ") { it["field"]?.jsonPrimitive?.content ?: "" },
                        detail = changes.joinToString("\n") {
                            "${it["field"]?.jsonPrimitive?.content}: ${it["before"]?.jsonPrimitive?.content} → ${it["after"]?.jsonPrimitive?.content}"
                        },
                    )
                }
            }
            Section(stringResource(R.string.diagnostics))
            CopyableText("household ${view.id.take(8)} · config ${view.config.version} · pending $pending · refused ${rejected.size}" +
                (view.state.lastError?.let { " · error $it" } ?: ""))
        }
    }
}
