// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.Session
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.components.SecondaryButton
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch

/**
 * Where this household lives. A phone-only household can be shared here;
 * a shared one shows its project and who is signed in.
 */
@Composable
fun SyncSettings(view: HouseholdView, onBack: () -> Unit, onInvite: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    var session by remember { mutableStateOf<Session?>(null) }
    var endpoint by remember { mutableStateOf<Endpoint?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.something_failed)
    val context = androidx.compose.ui.platform.LocalContext.current
    val wrong = stringResource(R.string.wrong_credentials)

    LaunchedEffect(refresh) {
        endpoint = container.settings.current().endpoint ?: container.hosted
        session = container.supabase()?.currentSession()
        endpoint?.let { url = it.url; key = it.anonKey }
    }

    Column {
        Section(stringResource(R.string.where_it_lives), top = 8.dp)
        ListRow(
            title = stringResource(if (view.state.connected) R.string.shared else R.string.on_this_phone),
            context = when {
                !view.state.connected -> stringResource(R.string.local_only_explained)
                container.hosted != null && endpoint == container.hosted -> stringResource(R.string.fulla_cloud)
                else -> endpoint?.host
            },
        )
        if (session != null) {
            ListRow(stringResource(R.string.signed_in_as), context = session?.email ?: "")
        }

        if (!view.state.connected || session == null) {
            Section(stringResource(if (view.state.connected) R.string.sign_in else R.string.share_household))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!view.state.connected) Text(stringResource(R.string.share_household_text), style = FullaType.secondary, color = c.inkMuted)
                val hosted = container.hosted
                val googleId = container.googleClientId
                if (session == null && googleId != null) {
                    PrimaryButton(stringResource(R.string.continue_google), busy = busy, onClick = {
                        scope.launch {
                            busy = true; error = null
                            val supabase = container.supabase(hosted)!!
                            when (val r = io.github.sirallap.fulla.ui.signin.GoogleSignIn.signIn(context, googleId, supabase)) {
                                is io.github.sirallap.fulla.ui.signin.GoogleSignIn.Result.Done -> {
                                    runCatching {
                                        if (!view.state.connected) container.ledger.connect(view.id, container.api()!!)
                                        container.syncAll()
                                    }.onFailure { error = (it as? FullaError)?.message ?: failed }
                                    refresh++
                                }
                                is io.github.sirallap.fulla.ui.signin.GoogleSignIn.Result.Failed -> error = r.message ?: failed
                                io.github.sirallap.fulla.ui.signin.GoogleSignIn.Result.Cancelled -> Unit
                            }
                            busy = false
                        }
                    })
                    Text(stringResource(R.string.or_with_email), style = FullaType.label, color = c.inkMuted)
                }
                if (session == null && hosted == null) {
                    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.project_url)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.anon_key)) }, singleLine = true)
                }
                if (session == null) {
                    OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.email)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    io.github.sirallap.fulla.ui.components.PasswordField(password, { password = it }, stringResource(R.string.password))
                }
                error?.let { Text(it, style = FullaType.secondary, color = c.danger) }
                PrimaryButton(stringResource(if (view.state.connected) R.string.sign_in else R.string.share_household), busy = busy, onClick = {
                    scope.launch {
                        busy = true; error = null
                        try {
                            if (session == null) {
                                val e = container.hosted ?: Endpoint.parse(url, key) ?: throw FullaError(FullaError.VALIDATION_FAILED, failed)
                                if (container.hosted == null) container.settings.setEndpoint(e)
                                container.supabase(e)!!.signIn(email, password)
                            }
                            if (!view.state.connected) container.ledger.connect(view.id, container.api()!!)
                            container.syncAll()
                            refresh++
                        } catch (e: FullaError) {
                            error = if (e.code == FullaError.INVALID_CREDENTIALS) wrong else e.message
                        } catch (e: Exception) {
                            error = failed
                        } finally {
                            busy = false
                        }
                    }
                })
            }
        }

        if (view.state.connected && session != null) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The natural next step after sharing: the other phone scans a code.
                PrimaryButton(stringResource(R.string.invite_someone), onInvite)
                SecondaryButton(stringResource(R.string.sign_out), {
                    scope.launch { container.supabase()?.signOut(); refresh++ }
                })
            }
        }
        if (session != null) {
            Section(stringResource(R.string.your_account))
            ListRow(stringResource(R.string.delete_account), context = stringResource(R.string.delete_account_text),
                titleColor = c.danger, onClick = { deleting = true })
            deleteError?.let { Text(it, style = FullaType.secondary, color = c.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        Section(stringResource(R.string.this_phone))
        ListRow(stringResource(R.string.forget_household), context = stringResource(R.string.forget_household_text),
            titleColor = c.danger, onClick = { forgetting = true })
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_account)) },
            text = { Text(stringResource(R.string.delete_account_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        deleteError = null
                        try {
                            container.api()!!.accountDelete()
                            container.supabase()?.signOut()
                            for (h in container.ledger.connectedIds()) container.ledger.forget(h)
                            onBack()
                        } catch (e: FullaError) {
                            deleteError = e.message
                        }
                    }
                }) { Text(stringResource(R.string.delete_account_yes), color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(stringResource(R.string.forget_household)) },
            text = { Text(stringResource(if (view.state.connected) R.string.forget_shared_confirm else R.string.forget_local_confirm)) },
            confirmButton = {
                TextButton(onClick = { forgetting = false; scope.launch { container.ledger.forget(view.id); onBack() } }) {
                    Text(stringResource(R.string.forget), color = c.danger)
                }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
