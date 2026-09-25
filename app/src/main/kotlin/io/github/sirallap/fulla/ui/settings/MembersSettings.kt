// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.local.LocalHousehold
import io.github.sirallap.fulla.client.remote.Invite
import io.github.sirallap.fulla.client.remote.InviteLink
import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.Role
import io.github.sirallap.fulla.core.roles.Permissions
import io.github.sirallap.fulla.core.split.SharedPot
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.MemberBadge
import io.github.sirallap.fulla.ui.components.MoneyModeSheet
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.components.QrImage
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.SecondaryButton
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** An invite ready to show: the QR code, the code in words, the link to send. */
@Composable
fun InvitePanel(link: String, code: String, forName: String?) {
    val context = LocalContext.current
    val c = FullaTheme.colors
    Column {
        forName?.let { Text(stringResource(R.string.invite_for, it), style = FullaType.body, color = c.ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        Column(Modifier.padding(horizontal = 48.dp, vertical = 8.dp)) { QrImage(link, stringResource(R.string.invite_qr_description)) }
        Text(stringResource(R.string.invite_scan_hint), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Text(code, style = FullaType.title, color = c.ink, modifier = Modifier.padding(horizontal = 20.dp))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            SecondaryButton(stringResource(R.string.share_link), {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
            })
        }
    }
}

private data class Shown(val link: String, val code: String, val forName: String?)

/**
 * The people in the household. Tapping someone offers exactly what your role
 * allows with them; nothing is shown that the server would refuse.
 */
@Composable
fun MembersSettings(view: HouseholdView, change: Change, onShare: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val me = view.me
    val connected = view.state.connected
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Member?>(null) }
    var shown by remember { mutableStateOf<Shown?>(null) }
    var open by remember { mutableStateOf<List<Invite>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.something_failed)
    val canInvite = connected && me != null && Permissions.canInvite(me, Role.MEMBER)
    var asking by remember { mutableStateOf<(() -> Unit)?>(null) }

    /**
     * Before somebody else joins, an admin whose household has not chosen yet
     * is asked how it handles money; [then] goes ahead whatever they answer.
     */
    fun askFirst(then: () -> Unit) {
        if (SharedPot.shouldAsk(view.config, me)) asking = then else then()
    }

    LaunchedEffect(refresh, connected) {
        if (canInvite) open = runCatching { container.api()!!.inviteList(view.id) }.getOrDefault(emptyList())
    }

    fun invite(claim: Member?, role: String = "member") {
        scope.launch {
            error = null
            runCatching {
                val created = container.api()!!.inviteCreate(view.id, role, claim?.id, 72)
                val endpoint = container.settings.current().endpoint!!
                shown = Shown(InviteLink(endpoint, created.code).toUri(), created.code, claim?.displayName)
                refresh++
            }.onFailure { error = failed }
        }
    }

    Column {
        for (m in view.config.members.filter { it.isActive }) {
            ListRow(
                title = m.displayName + if (m.id == view.config.meMemberId) " · ${stringResource(R.string.you)}" else "",
                start = { MemberBadge(m.initials, m.colorIndex) },
                context = stringResource(roleName(m.role)),
                detail = if (!m.hasAccount) stringResource(R.string.no_account_member) else null,
                onClick = { selected = m },
            )
        }
        if (me != null && Permissions.canAddMemberWithoutAccount(me)) {
            ListRow(stringResource(R.string.add_member), context = stringResource(R.string.add_member_text), icon = Icons.Outlined.PersonAdd, onClick = { adding = true })
        }
        error?.let { Text(it, color = FullaTheme.colors.danger, style = FullaType.secondary, modifier = Modifier.padding(horizontal = 20.dp)) }

        Section(stringResource(R.string.invite))
        when {
            !connected -> ListRow(stringResource(R.string.share_first), context = stringResource(R.string.share_first_text), icon = Icons.Outlined.Share, onClick = onShare)
            !canInvite -> Text(stringResource(R.string.invite_admins_only), style = FullaType.secondary, color = FullaTheme.colors.inkMuted, modifier = Modifier.padding(20.dp))
            else -> {
                shown?.let { InvitePanel(it.link, it.code, it.forName) }
                Text(stringResource(R.string.invite_text), style = FullaType.secondary, color = FullaTheme.colors.inkMuted, modifier = Modifier.padding(20.dp))
                Column(Modifier.padding(horizontal = 20.dp)) {
                    PrimaryButton(stringResource(if (shown == null) R.string.create_invite else R.string.create_another_invite), { askFirst { invite(null) } }, icon = Icons.Outlined.QrCode)
                }
                if (open.isNotEmpty()) {
                    Section(stringResource(R.string.open_invites))
                    for (i in open) {
                        ListRow(i.code, context = i.claimMemberId?.let { view.memberName(it) } ?: stringResource(roleName(Role.of(i.role))),
                            detail = stringResource(R.string.expires, i.expiresAt.take(16).replace('T', ' ')),
                            end = {
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { container.api()!!.inviteRevoke(view.id, i.code) }.onFailure { error = failed }
                                        if (shown?.code == i.code) shown = null
                                        refresh++
                                    }
                                }) { Text(stringResource(R.string.revoke), color = FullaTheme.colors.danger) }
                            })
                    }
                }
            }
        }
    }

    if (adding) {
        EditDialog(stringResource(R.string.add_member), "", stringResource(R.string.name), onDismiss = { adding = false }) { name ->
            val member = buildJsonObject {
                put("id", UUID.randomUUID().toString()); put("display_name", name); put("initials", initialsOf(name))
                put("color_index", view.config.members.size % 10)
            }
            askFirst {
                change { api ->
                    val next = if (api == null) LocalHousehold.upsertMember(view.state.bundle, member) else api.memberCreateVirtual(view.id, member)
                    container.ledger.storeConfig(view.id, next)
                }
            }
        }
    }

    selected?.let { m -> MemberSheet(view, m, change, onInviteToClaim = { askFirst { invite(m) } }, onDismiss = { selected = null }) }

    asking?.let { then ->
        // "Not now" leaves it unchosen: the question comes back with the next invite.
        MoneyModeSheet(current = null, onDismiss = { asking = null; then() }, onChoose = { mode ->
            asking = null
            change { api ->
                container.ledger.updateHousehold(view.id, buildJsonObject { put("money_mode", mode.key) }, api)
                then()
            }
        })
    }
}

@Composable
private fun MemberSheet(view: HouseholdView, target: Member, change: Change, onInviteToClaim: () -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val c = FullaTheme.colors
    val me = view.me ?: return
    val connected = view.state.connected
    val self = me.id == target.id
    var renaming by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.paper) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            ListRow(target.displayName, start = { MemberBadge(target.initials, target.colorIndex, size = 40.dp) },
                context = stringResource(roleName(target.role)), divider = false)
            if (Permissions.canEditMember(me, target)) {
                ListRow(stringResource(R.string.rename), icon = Icons.Outlined.Edit, onClick = { renaming = true })
            }
            if (connected && !target.hasAccount && Permissions.canInvite(me, Role.MEMBER)) {
                ListRow(stringResource(R.string.invite_to_claim), context = stringResource(R.string.invite_to_claim_text, target.displayName),
                    icon = Icons.Outlined.QrCode, onClick = { onDismiss(); onInviteToClaim() })
            }
            if (connected && !self && target.hasAccount && target.role != Role.OWNER && Permissions.canChangeRoles(me)) {
                val toAdmin = target.role == Role.MEMBER
                ListRow(stringResource(if (toAdmin) R.string.make_admin else R.string.make_member), icon = Icons.Outlined.AdminPanelSettings,
                    context = stringResource(if (toAdmin) R.string.make_admin_text else R.string.make_member_text), onClick = {
                        onDismiss()
                        change { api -> container.ledger.storeConfig(view.id, api!!.memberSetRole(view.id, target.id, if (toAdmin) "admin" else "member")) }
                    })
            }
            if (connected && !self && target.hasAccount && Permissions.canTransferOwnership(me)) {
                val text = stringResource(R.string.transfer_ownership_confirm, target.displayName)
                ListRow(stringResource(R.string.transfer_ownership), icon = Icons.Outlined.SwapHoriz, onClick = {
                    confirming = text to {
                        change { api -> container.ledger.storeConfig(view.id, api!!.ownerTransfer(view.id, target.id)) }
                    }
                })
            }
            if (Permissions.canRemove(me, target)) {
                val text = stringResource(R.string.remove_confirm, target.displayName)
                ListRow(stringResource(R.string.remove_member), icon = Icons.Outlined.PersonRemove, iconTint = c.danger, titleColor = c.danger, onClick = {
                    confirming = text to {
                        change { api ->
                            val next = if (api == null) {
                                LocalHousehold.upsertMember(view.state.bundle, buildJsonObject { put("id", target.id); put("status", "archived") })
                            } else api.memberRemove(view.id, target.id)
                            container.ledger.storeConfig(view.id, next)
                        }
                    }
                })
            }
            if (connected && self && Permissions.canLeave(me)) {
                val text = stringResource(R.string.leave_confirm)
                ListRow(stringResource(R.string.leave_household), icon = Icons.AutoMirrored.Outlined.Logout, iconTint = c.danger, titleColor = c.danger, onClick = {
                    confirming = text to {
                        change { api -> api!!.memberLeave(view.id); container.ledger.forget(view.id) }
                    }
                })
            }
        }
    }

    if (renaming) {
        EditDialog(stringResource(R.string.rename), target.displayName, stringResource(R.string.name), onDismiss = { renaming = false }) { name ->
            val patch = buildJsonObject { put("display_name", name); put("initials", initialsOf(name)) }
            change { api ->
                val next = if (api == null) {
                    LocalHousehold.upsertMember(view.state.bundle, kotlinx.serialization.json.JsonObject(patch + ("id" to JsonPrimitive(target.id))))
                } else api.memberUpdate(view.id, target.id, patch)
                container.ledger.storeConfig(view.id, next)
            }
            onDismiss()
        }
    }
    confirming?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirming = null; onDismiss(); action() }) { Text(stringResource(R.string.confirm), color = c.danger) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

internal fun roleName(role: Role): Int = when (role) {
    Role.OWNER -> R.string.role_owner
    Role.ADMIN -> R.string.role_admin
    Role.MEMBER -> R.string.role_member
}

internal fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
