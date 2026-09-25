// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import io.github.sirallap.fulla.ui.components.BrandMark
import io.github.sirallap.fulla.ui.theme.FullaMotion
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Restore
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.sirallap.fulla.client.remote.InviteDecision
import io.github.sirallap.fulla.client.remote.InviteLink
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.InviteProblem
import io.github.sirallap.fulla.client.remote.InviteRefused
import io.github.sirallap.fulla.client.remote.ProjectSetup
import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.ui.Formats
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.ActionBar
import io.github.sirallap.fulla.ui.components.BackHeader
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.PasswordField
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.components.SecondaryButton
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Step { WELCOME, LOCAL, AUTO, PROJECT, ACCOUNT, CHOOSE, CREATE, JOIN }

/**
 * The first run. Three ways in: keep everything on this phone, share it
 * through the household's own Supabase project, or look around with invented
 * data. None of them is a dead end: a phone-only household can be shared
 * later without losing anything.
 */
@Composable
fun Onboarding(onCancel: (() -> Unit)?) {
    val appLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val container = LocalContainer.current
    val invite by container.pendingInvite.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    var url by rememberSaveable { mutableStateOf("") }
    var anonKey by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var myName by rememberSaveable { mutableStateOf("") }
    val hosted = container.hosted != null
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var signInError by remember { mutableStateOf<String?>(null) }
    val failedText = stringResource(R.string.something_failed)

    /**
     * Signed in, by Google or by email: join the invite being followed, or
     * bring back the households this account already has (a new phone), or
     * go on to create or join one.
     */
    fun afterSignIn(name: String?) {
        name?.takeIf { it.isNotBlank() }?.let { myName = it }
        if (code.isNotBlank()) { step = Step.JOIN; return }
        scope.launch {
            val api = container.api() ?: return@launch
            val mine = runCatching { api.myHouseholds() }.getOrDefault(emptyList())
            if (mine.isEmpty()) { step = Step.CHOOSE; return@launch }
            for (m in mine) {
                runCatching { container.ledger.adopt(io.github.sirallap.fulla.client.remote.Joined(m.householdId, m.memberId, api.configGet(m.householdId))) }
            }
            container.settings.setOnboarded(true)
        }
    }
    val google: (() -> Unit)? = container.googleClientId?.let { clientId ->
        {
            scope.launch {
                signInError = null
                val supabase = container.supabase() ?: return@launch
                when (val r = io.github.sirallap.fulla.ui.signin.GoogleSignIn.signIn(context, clientId, supabase)) {
                    is io.github.sirallap.fulla.ui.signin.GoogleSignIn.Result.Done -> afterSignIn(r.name)
                    is io.github.sirallap.fulla.ui.signin.GoogleSignIn.Result.Failed -> signInError = r.message ?: failedText
                    io.github.sirallap.fulla.ui.signin.GoogleSignIn.Result.Cancelled -> Unit
                }
            }
        }
    }

    // What arriving with this invite would do, decided against the endpoint
    // this phone actually talks to (saved, or the build's hosted one — never
    // just "saved", which is null on a fresh hosted install and would let an
    // invite's project through unconfirmed). Nothing is persisted here: the
    // invite waits in confirmInvite for the person to confirm below, which is
    // the only place settings.setEndpoint is called for an invite.
    var confirmInvite by remember { mutableStateOf<InviteLink?>(null) }
    var confirmIsSwitch by remember { mutableStateOf(false) }
    LaunchedEffect(invite) {
        invite?.let {
            val effective = container.settings.current().endpoint ?: container.hosted
            when (val outcome = InviteDecision.evaluate(it, effective, container.ledger.connectedIds().isNotEmpty())) {
                is InviteDecision.Outcome.ConfirmSwitch -> confirmIsSwitch = true
                is InviteDecision.Outcome.Proceed -> confirmIsSwitch = false
            }
            confirmInvite = it
            container.pendingInvite.value = null
        }
    }
    confirmInvite?.let { pending ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmInvite = null },
            title = { Text(stringResource(R.string.invite_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.invite_confirm_host, pending.endpoint.host))
                    if (confirmIsSwitch) Text(stringResource(R.string.invite_other_server), modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    scope.launch {
                        val effective = container.settings.current().endpoint ?: container.hosted
                        if (effective != null && effective != pending.endpoint) container.sessions.save(null)
                        container.settings.setEndpoint(pending.endpoint)
                        url = pending.endpoint.url; anonKey = pending.endpoint.anonKey; code = pending.code
                        step = if (container.supabase(pending.endpoint)?.currentSession() != null) Step.JOIN else Step.ACCOUNT
                        confirmInvite = null
                    }
                }) { Text(stringResource(R.string.join)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmInvite = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    BackHandler(enabled = step == Step.WELCOME && onCancel != null) { onCancel?.invoke() }
    BackHandler(enabled = step != Step.WELCOME) {
        step = when (step) {
            Step.ACCOUNT -> if (hosted) Step.WELCOME else Step.PROJECT
            Step.PROJECT -> Step.AUTO
            Step.CREATE, Step.JOIN -> Step.CHOOSE
            else -> Step.WELCOME
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        when (step) {
            Step.WELCOME -> {
                if (onCancel != null) BackHeader(stringResource(R.string.add_household), onCancel)
                Welcome(hosted = hosted, google = google, error = signInError, onLocal = { step = Step.LOCAL },
                    onShared = { step = if (hosted) Step.ACCOUNT else Step.AUTO },
                    onScanned = { container.pendingInvite.value = it })
            }
            Step.LOCAL -> HouseholdForm(title = stringResource(R.string.on_this_phone), onBack = { step = Step.WELCOME }) { name, me, currency ->
                container.ledger.createLocal(name, currency, appLocale.toLanguageTag(), me, initials(me), 0)
                container.settings.setOnboarded(true)
                null
            }
            Step.AUTO -> AutoSetup(onBack = { step = Step.WELCOME }, onManual = { step = Step.PROJECT }) { endpoint ->
                url = endpoint.url; anonKey = endpoint.anonKey
                container.settings.setEndpoint(endpoint)
                step = Step.ACCOUNT
            }
            Step.PROJECT -> ProjectForm(url, anonKey, { url = it }, { anonKey = it }, onBack = { step = Step.AUTO }, onToken = { step = Step.AUTO }) { endpoint ->
                container.settings.setEndpoint(endpoint)
                step = if (container.supabase(endpoint)?.currentSession() != null) Step.CHOOSE else Step.ACCOUNT
            }
            Step.ACCOUNT -> AccountForm(onBack = { step = if (hosted) Step.WELCOME else Step.PROJECT }, startCreating = code.isNotBlank(),
                google = google, googleError = signInError) { afterSignIn(null) }
            Step.CHOOSE -> Choose(onBack = { step = if (hosted) Step.WELCOME else Step.PROJECT }, onCreate = { step = Step.CREATE }, onJoin = { step = Step.JOIN })
            Step.CREATE -> HouseholdForm(title = stringResource(R.string.new_shared_household), onBack = { step = Step.CHOOSE }, initialName = myName) { name, me, currency ->
                val api = container.api() ?: return@HouseholdForm FullaError(FullaError.NOT_AUTHENTICATED, "")
                runCatching { api.householdCreate(name, currency, appLocale.toLanguageTag(), me, initials(me), 0) }
                    .fold({ container.ledger.adopt(it); container.settings.setOnboarded(true); null }, { it })
            }
            Step.JOIN -> JoinForm(code, { code = it }, onBack = { step = Step.CHOOSE }, initialName = myName) { me ->
                val api = container.api() ?: return@JoinForm FullaError(FullaError.NOT_AUTHENTICATED, "")
                runCatching { api.inviteAccept(code.trim(), me, initials(me), 1) }
                    .fold({ container.ledger.adopt(it); container.pendingInvite.value = null; container.settings.setOnboarded(true); null }, { it })
            }
        }
    }
}

private fun initials(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

@Composable
private fun Welcome(hosted: Boolean, google: (() -> Unit)?, error: String?, onLocal: () -> Unit, onShared: () -> Unit, onScanned: (InviteLink) -> Unit) {
    val appLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    var notAnInvite by remember { mutableStateOf(false) }
    var restoreProblem by remember { mutableStateOf<Int?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val restore = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            restoreProblem = io.github.sirallap.fulla.ui.settings.restoreBackup(context, container.ledger, uri)
        }
    }
    val prompt = stringResource(R.string.scan_prompt)
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val link = InviteLink.parse(text)
        notAnInvite = link == null
        if (link != null) onScanned(link)
    }
    // The welcome's one moment: the band draws itself, the liquid rises and
    // settles with a small slosh, then the words and the actions come in.
    val reduced = FullaMotion.reduced()
    val band = remember { Animatable(if (reduced) 1f else 0f) }
    val level = remember { Animatable(if (reduced) 0.58f else 0f) }
    val slosh = remember { Animatable(0f) }
    val name = remember { Animatable(if (reduced) 1f else 0f) }
    val line = remember { Animatable(if (reduced) 1f else 0f) }
    val actions = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        val words = spring<Float>(dampingRatio = 0.9f, stiffness = 260f)
        launch { band.animateTo(1f, tween(620, easing = CubicBezierEasing(0.3f, 0f, 0f, 1f))) }
        launch { delay(240); level.animateTo(0.58f, spring(dampingRatio = 0.6f, stiffness = 55f)) }
        launch { delay(240); slosh.animateTo(0.18f, tween(280)); slosh.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 70f)) }
        launch { delay(520); name.animateTo(1f, words) }
        launch { delay(640); line.animateTo(1f, words) }
        launch { delay(780); actions.animateTo(1f, words) }
    }
    var more by rememberSaveable { mutableStateOf(false) }
    val scanInvite = {
        scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt)
            .setBeepEnabled(false).setOrientationLocked(false))
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center) {
            BrandMark(Modifier.size(88.dp), band = band.value, level = level.value, slosh = slosh.value)
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.app_name), style = FullaType.heroFigure, color = c.ink, modifier = Modifier.arrive(name.value))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.welcome_line), style = FullaType.title, color = c.ink, modifier = Modifier.arrive(line.value))
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.welcome_text), style = FullaType.body, color = c.inkMuted, modifier = Modifier.arrive(line.value))
        }
        Column(Modifier.arrive(actions.value)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                when {
                    google != null -> PrimaryButton(stringResource(R.string.continue_google), google)
                    hosted -> PrimaryButton(stringResource(R.string.continue_email), onShared)
                    else -> PrimaryButton(stringResource(R.string.get_started), onLocal)
                }
                error?.let { Text(it, style = FullaType.secondary, color = c.danger, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(10.dp))
                SecondaryButton(stringResource(R.string.have_invite), scanInvite)
                if (notAnInvite) {
                    Text(stringResource(R.string.not_an_invite), style = FullaType.secondary, color = c.danger, modifier = Modifier.padding(top = 6.dp))
                }
                restoreProblem?.let { Text(stringResource(it), style = FullaType.secondary, color = c.danger, modifier = Modifier.padding(top = 6.dp)) }
            }
            TextButton(onClick = { more = !more }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(stringResource(if (more) R.string.fewer_options else R.string.more_options), style = FullaType.body, color = c.inkMuted)
            }
            AnimatedVisibility(more, enter = expandVertically(FullaMotion.settle<androidx.compose.ui.unit.IntSize>(reduced)) + fadeIn(FullaMotion.functional<Float>(reduced)),
                exit = shrinkVertically(FullaMotion.snappy<androidx.compose.ui.unit.IntSize>(reduced)) + fadeOut(FullaMotion.functional<Float>(reduced))) {
                Column {
                    if (hosted) {
                        if (google != null) ListRow(stringResource(R.string.continue_email), detail = stringResource(R.string.continue_email_text),
                            icon = Icons.Outlined.Cloud, onClick = onShared)
                        ListRow(stringResource(R.string.use_without_account), detail = stringResource(R.string.use_without_account_text),
                            icon = Icons.Outlined.PhoneAndroid, onClick = onLocal)
                    } else {
                        ListRow(stringResource(R.string.shared), detail = stringResource(R.string.shared_text),
                            icon = Icons.Outlined.Cloud, onClick = onShared)
                    }
                    ListRow(stringResource(R.string.backup_restore), detail = stringResource(R.string.backup_restore_welcome),
                        icon = Icons.Outlined.Restore, onClick = { restore.launch(arrayOf("*/*")) })
                    ListRow(stringResource(R.string.look_around), detail = stringResource(R.string.look_around_text),
                        icon = Icons.Outlined.Visibility, divider = false,
                        onClick = { scope.launch { container.ledger.createDemo(appLocale.language) } })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Comes in from slightly below, out of a blur, as [p] goes 0 → 1. */
private fun Modifier.arrive(p: Float): Modifier = this
    .graphicsLayer { alpha = p.coerceIn(0f, 1f); translationY = (1f - p) * 14.dp.toPx() }
    .then(if (p < 0.99f) Modifier.blur(((1f - p) * 6f).coerceAtLeast(0f).dp) else Modifier)

/** A form's body with its error line and one primary action at the bottom. */
@Composable
private fun FormScaffold(
    title: String,
    onBack: () -> Unit,
    action: String,
    enabled: Boolean,
    error: String?,
    busy: Boolean,
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BackHeader(title, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            error?.let { Text(it, style = FullaType.secondary, color = FullaTheme.colors.danger) }
        }
        ActionBar { PrimaryButton(action, onAction, enabled = enabled, busy = busy) }
    }
}

@Composable
private fun HouseholdForm(title: String, onBack: () -> Unit, initialName: String = "", submit: suspend (String, String, String) -> Throwable?) {
    val scope = rememberCoroutineScope()
    val defaultName = stringResource(R.string.default_household_name)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var me by rememberSaveable { mutableStateOf(initialName) }
    var currency by rememberSaveable { mutableStateOf(Formats.proposedCurrency()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && me.isNotBlank() && Currency.of(currency.trim().uppercase()) != null
    val failed = stringResource(R.string.something_failed)
    FormScaffold(title, onBack, stringResource(R.string.create), valid, error, busy, onAction = {
        busy = true
        scope.launch {
            val e = submit(name.trim(), me.trim(), currency.trim().uppercase())
            busy = false
            error = e?.let { (it as? FullaError)?.message ?: failed }
        }
    }) {
        OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.household_name)) }, singleLine = true)
        OutlinedTextField(me, { me = it.take(40) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.your_name)) }, singleLine = true)
        OutlinedTextField(currency, { currency = it.take(3).uppercase() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.currency)) },
            supportingText = { Text(stringResource(R.string.currency_help)) }, singleLine = true,
            isError = Currency.of(currency.trim().uppercase()) == null)
    }
}

@Composable
private fun ProjectForm(url: String, anonKey: String, onUrl: (String) -> Unit, onKey: (String) -> Unit, onBack: () -> Unit, onToken: () -> Unit, onReady: suspend (Endpoint) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val problem = Endpoint.problem(url, anonKey)
    val messages = mapOf(
        Endpoint.Problem.NOT_HTTPS to stringResource(R.string.url_not_https),
        Endpoint.Problem.NOT_SUPABASE to stringResource(R.string.url_not_supabase),
        Endpoint.Problem.NOT_A_URL to stringResource(R.string.url_not_supabase),
    )
    val unreachable = stringResource(R.string.project_unreachable)
    val notSetUp = stringResource(R.string.project_not_set_up)
    FormScaffold(stringResource(R.string.shared), onBack, stringResource(R.string.connect), problem == null, error, busy, onAction = {
        val endpoint = Endpoint.parse(url, anonKey) ?: return@FormScaffold
        busy = true
        scope.launch {
            // A ping before anything else: it proves the URL, the key and the database in one call.
            val failure = runCatching { container.api(endpoint)!!.ping() }.exceptionOrNull()
            busy = false
            if (failure == null) {
                onReady(endpoint)
            } else {
                error = if ((failure as? FullaError)?.code == FullaError.NETWORK) unreachable else notSetUp
            }
        }
    }) {
        Text(stringResource(R.string.project_help), style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
        androidx.compose.material3.TextButton(onClick = onToken) {
            Text(stringResource(R.string.use_token_instead), style = FullaType.body, color = FullaTheme.colors.accent)
        }
        OutlinedTextField(url, onUrl, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.project_url)) },
            placeholder = { Text("https://…supabase.co") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = url.isNotBlank() && problem != null && problem != Endpoint.Problem.BAD_KEY,
            supportingText = { if (url.isNotBlank() && problem != null && problem != Endpoint.Problem.BAD_KEY) Text(messages[problem] ?: "") })
        OutlinedTextField(anonKey, onKey, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.anon_key)) },
            supportingText = { Text(stringResource(R.string.anon_key_help)) }, singleLine = true)
    }
}

/**
 * The household's own Supabase project, set up from here: an account on
 * supabase.com, a personal access token pasted once, and the app does the
 * rest. A project somebody already has is one tap away ("I already have
 * one"), through [ProjectForm].
 */
@Composable
private fun AutoSetup(onBack: () -> Unit, onManual: () -> Unit, onReady: suspend (Endpoint) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val c = FullaTheme.colors
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reached by remember { mutableStateOf<ProjectSetup.Step?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // The account's projects, once the token has been read and none is Fulla's own.
    var choices by remember { mutableStateOf<List<ProjectSetup.Project>?>(null) }
    // Held here, rather than handed straight to onReady, so the person sees
    // the token-deletion note before this screen navigates away.
    var succeeded by remember { mutableStateOf<Endpoint?>(null) }
    val messages = mapOf(
        ProjectSetup.PROJECT_PAUSED to stringResource(R.string.setup_project_paused),
        ProjectSetup.TOKEN_REFUSED to stringResource(R.string.setup_token_refused),
        ProjectSetup.PROJECT_LIMIT to stringResource(R.string.setup_project_limit),
        ProjectSetup.SETUP_SLOW to stringResource(R.string.setup_slow),
        FullaError.NETWORK to stringResource(R.string.project_unreachable),
    )
    val failed = stringResource(R.string.setup_failed)
    val noOrganization = stringResource(R.string.setup_no_organization)
    /** Runs one setup call with the token, and connects when it hands back an endpoint (null: a choice to make first). */
    fun go(work: suspend (ProjectSetup, String) -> Endpoint?) {
        busy = true; error = null
        scope.launch {
            runCatching {
                val setup = container.projectSetup(token.trim())
                val sql = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.setupSql() }
                work(setup, sql)
            }.fold({ endpoint -> if (endpoint != null) { token = ""; succeeded = endpoint } }, { e ->
                val code = (e as? FullaError)?.code
                error = messages[code] ?: if (code == ProjectSetup.SETUP_FAILED && e.message == noOrganization) noOrganization else failed
            })
            busy = false
        }
    }
    suspend fun createNew(setup: ProjectSetup, sql: String): Endpoint {
        val organization = setup.organizations().firstOrNull() ?: throw FullaError(ProjectSetup.SETUP_FAILED, noOrganization)
        return setup.run(organization.slug, sql, ProjectSetup.regionFor(java.util.TimeZone.getDefault().id)) { reached = it }
    }
    val done = succeeded
    FormScaffold(stringResource(R.string.setup_title), onBack,
        if (done != null) stringResource(R.string.setup_done_continue)
        else stringResource(if (choices == null) R.string.setup_action else R.string.setup_create_new),
        if (done != null) true else token.trim().length >= 20 && !busy, error, busy, onAction = {
            if (done != null) scope.launch { onReady(done) }
            else if (choices != null) go { setup, sql -> createNew(setup, sql) }
            else go { setup, sql ->
                // Fulla's own project from before (a reinstall, a new phone) is
                // picked up by itself; with none and no others, one is created;
                // otherwise the person chooses.
                val projects = setup.projects()
                val own = projects.firstOrNull { it.name == ProjectSetup.PROJECT_NAME }
                when {
                    own != null -> setup.connect(own.ref, sql, ownProject = true) { reached = it }
                    projects.isEmpty() -> createNew(setup, sql)
                    else -> { choices = projects; null }
                }
            }
        }) {
        if (done != null) {
            Text(stringResource(R.string.setup_token_delete), style = FullaType.body, color = c.ink)
            androidx.compose.material3.TextButton(onClick = { uri.openUri("https://supabase.com/dashboard/account/tokens") }) {
                Text(stringResource(R.string.setup_token_delete_link), style = FullaType.body, color = c.accent)
            }
        } else {
            Text(stringResource(R.string.setup_intro), style = FullaType.body, color = c.ink)
            SetupStepRow(1, stringResource(R.string.setup_step_account), stringResource(R.string.setup_step_account_text),
                stringResource(R.string.setup_open)) { uri.openUri(ProjectSetup.SIGN_UP_PAGE) }
            SetupStepRow(2, stringResource(R.string.setup_step_token), stringResource(R.string.setup_step_token_text),
                stringResource(R.string.setup_open)) { uri.openUri(ProjectSetup.TOKEN_PAGE) }
            SetupStepRow(3, stringResource(R.string.setup_step_paste), stringResource(R.string.setup_step_paste_text), null, null)
            OutlinedTextField(token, { token = it.trim() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.setup_token)) },
                placeholder = { Text("sbp_…") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                visualTransformation = PasswordVisualTransformation())
            if (busy || reached != null) {
                val order = ProjectSetup.Step.entries
                val labels = listOf(R.string.setup_creating, R.string.setup_starting, R.string.setup_installing, R.string.setup_configuring)
                for ((i, label) in labels.withIndex()) {
                    val stepDone = reached != null && order.indexOf(reached!!) > i
                    val now = reached != null && order.indexOf(reached!!) == i
                    Text((if (stepDone) "✓  " else if (now) "…  " else "   ") + stringResource(label), style = FullaType.secondary,
                        color = if (stepDone || now) c.ink else c.inkMuted)
                }
            }
            choices?.let { list ->
                Text(stringResource(R.string.setup_choose_project), style = FullaType.body, color = c.ink)
                Text(stringResource(R.string.setup_choose_note), style = FullaType.secondary, color = c.inkMuted)
                for (p in list) {
                    io.github.sirallap.fulla.ui.components.ListRow(p.name,
                        context = listOfNotNull(p.region, stringResource(R.string.setup_paused).takeIf { p.paused }).joinToString(" · ").ifEmpty { null },
                        onClick = if (busy) null else ({ go { setup, sql -> setup.connect(p.ref, sql, ownProject = false) { reached = it } } }))
                }
            }
            androidx.compose.material3.TextButton(onClick = onManual, enabled = !busy) {
                Text(stringResource(R.string.setup_manual), style = FullaType.body, color = c.inkMuted)
            }
        }
    }
}

@Composable
private fun SetupStepRow(n: Int, title: String, text: String, action: String?, onAction: (() -> Unit)?) {
    val c = FullaTheme.colors
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("$n", style = FullaType.title, color = c.inkMuted, modifier = Modifier.padding(end = 14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = FullaType.body, color = c.ink)
            Text(text, style = FullaType.secondary, color = c.inkMuted)
        }
        if (action != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) { Text(action, style = FullaType.body, color = c.accent) }
        }
    }
}

@Composable
private fun AccountForm(onBack: () -> Unit, startCreating: Boolean, google: (() -> Unit)?, googleError: String?, onSignedIn: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    // Somebody arriving with an invite most likely has no account yet.
    var creating by rememberSaveable { mutableStateOf(startCreating) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val wrong = stringResource(R.string.wrong_credentials)
    val confirm = stringResource(R.string.confirm_email)
    val failed = stringResource(R.string.something_failed)
    val differ = creating && repeat.isNotEmpty() && repeat != password
    val valid = email.contains('@') && password.length >= 8 && (!creating || repeat == password)
    FormScaffold(stringResource(if (creating) R.string.create_account else R.string.sign_in), onBack,
        stringResource(if (creating) R.string.create_account else R.string.sign_in), valid, error, busy, onAction = {
            busy = true; error = null; info = null
            scope.launch {
                val supabase = container.supabase()
                try {
                    if (creating) {
                        if (supabase!!.signUp(email, password) == null) { info = confirm } else { onSignedIn() }
                    } else {
                        supabase!!.signIn(email, password)
                        onSignedIn()
                    }
                } catch (e: FullaError) {
                    error = when (e.code) {
                        FullaError.INVALID_CREDENTIALS -> wrong
                        FullaError.EMAIL_NOT_CONFIRMED -> confirm
                        else -> e.message.ifBlank { failed }
                    }
                } finally {
                    busy = false
                }
            }
        }) {
        if (google != null) {
            PrimaryButton(stringResource(R.string.continue_google), google)
            googleError?.let { Text(it, style = FullaType.secondary, color = FullaTheme.colors.danger) }
            Text(stringResource(R.string.or_with_email), style = FullaType.label, color = FullaTheme.colors.inkMuted)
        }
        OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.email)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        PasswordField(password, { password = it }, stringResource(R.string.password),
            supportingText = if (creating) stringResource(R.string.password_help) else null)
        if (creating) {
            PasswordField(repeat, { repeat = it }, stringResource(R.string.repeat_password), isError = differ,
                supportingText = if (differ) stringResource(R.string.passwords_differ) else null)
        }
        info?.let { Text(it, style = FullaType.secondary, color = FullaTheme.colors.ink) }
        SecondaryButton(stringResource(if (creating) R.string.have_account else R.string.no_account), { creating = !creating })
    }
}

@Composable
private fun Choose(onBack: () -> Unit, onCreate: () -> Unit, onJoin: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BackHeader(stringResource(R.string.shared), onBack)
        ListRow(stringResource(R.string.create_household), context = stringResource(R.string.create_household_text), onClick = onCreate)
        ListRow(stringResource(R.string.join_household), context = stringResource(R.string.join_household_text), onClick = onJoin)
    }
}

@Composable
private fun JoinForm(code: String, onCode: (String) -> Unit, onBack: () -> Unit, initialName: String = "", submit: suspend (String) -> Throwable?) {
    val scope = rememberCoroutineScope()
    var me by rememberSaveable { mutableStateOf(initialName) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val texts = mapOf(
        InviteProblem.INVALID to stringResource(R.string.invite_invalid),
        InviteProblem.EXPIRED to stringResource(R.string.invite_expired),
        InviteProblem.USED to stringResource(R.string.invite_used),
        InviteProblem.ALREADY_MEMBER to stringResource(R.string.invite_already_member),
        InviteProblem.HOUSEHOLD_FULL to stringResource(R.string.invite_full),
    )
    val tooMany = stringResource(R.string.too_many_attempts)
    val failed = stringResource(R.string.something_failed)
    FormScaffold(stringResource(R.string.join_household), onBack, stringResource(R.string.join), code.isNotBlank() && me.isNotBlank(), error, busy, onAction = {
        busy = true
        scope.launch {
            val e = submit(me.trim())
            busy = false
            error = when {
                e == null -> null
                e is InviteRefused -> texts[e.problem]
                (e as? FullaError)?.code == FullaError.RATE_LIMITED -> tooMany
                else -> (e as? FullaError)?.message ?: failed
            }
        }
    }) {
        OutlinedTextField(code, { onCode(it.uppercase().take(20)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.invite_code)) }, singleLine = true)
        OutlinedTextField(me, { me = it.take(40) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.your_name)) }, singleLine = true)
    }
}
