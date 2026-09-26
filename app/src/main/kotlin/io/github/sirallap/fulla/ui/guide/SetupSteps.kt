// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.guide

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.core.guide.MonthStart
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.GuideSheet
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.PeriodSelector
import io.github.sirallap.fulla.ui.components.SwitchRow
import io.github.sirallap.fulla.ui.settings.bundleItem
import io.github.sirallap.fulla.ui.settings.parseOpeningBalance
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * [patch] as `updateHousehold` wants it: only ints and explicit nulls, per
 * [MonthStart.toPatch]. Shared with [io.github.sirallap.fulla.ui.settings]'s
 * own month-start editing so both build the same kind of patch.
 */
internal fun Map<String, Any?>.toJsonObject(): JsonObject = buildJsonObject {
    for ((k, v) in this@toJsonObject) when (v) {
        null -> put(k, JsonNull)
        is Int -> put(k, v)
        else -> put(k, v.toString())
    }
}

/**
 * Setup step 1: when the household's month starts. Saved through
 * [io.github.sirallap.fulla.data.repo.Ledger.updateHousehold]. When the
 * household is shared and this can't be sent right now — offline, or the
 * write itself fails — the choice is not silently dropped: the person sees
 * [R.string.guide_save_later] and stays on the step until they press Next
 * again, which then just moves on (there is nothing more to retry from here;
 * they can change it later in Settings, as the note says).
 */
@Composable
fun MonthStartStep(view: HouseholdView, stepOf: Pair<Int, Int>, onNext: () -> Unit, onSkip: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    var choice by remember { mutableStateOf(MonthStart.of(view.config.household)) }
    var savedLater by remember { mutableStateOf(false) }
    val preview = remember(choice) { choice.preview(LocalDate.now()) }
    val f = view.formats

    fun save() {
        if (savedLater) { onNext(); return }
        scope.launch {
            val api = if (view.state.connected) container.api() else null
            val ok = if (view.state.connected && api == null) false
                else runCatching { container.ledger.updateHousehold(view.id, choice.toPatch().toJsonObject(), api) }.isSuccess
            if (ok) onNext() else savedLater = true
        }
    }

    GuideSheet(
        title = stringResource(R.string.guide_month_title),
        stepOf = stepOf,
        primaryLabel = stringResource(R.string.guide_next),
        onPrimary = { save() },
        onSkip = onSkip,
        // Holds a choice the person just made; an accidental scrim tap or
        // Back must not throw it away as if they had pressed Skip.
        dismissOnOutsideTap = false,
    ) {
        ListRow(stringResource(R.string.guide_month_first), onClick = { choice = MonthStart.Calendar },
            end = { if (choice is MonthStart.Calendar) Icon(Icons.Outlined.Check, null, tint = c.accent) })
        ListRow(stringResource(R.string.guide_month_payday), onClick = { choice = MonthStart.Payday((choice as? MonthStart.Payday)?.day ?: 25) },
            divider = choice !is MonthStart.Payday,
            end = { if (choice is MonthStart.Payday) Icon(Icons.Outlined.Check, null, tint = c.accent) })
        (choice as? MonthStart.Payday)?.let { p ->
            PeriodSelector(stringResource(R.string.period_start_day_value, p.day),
                onPrevious = { choice = MonthStart.Payday((p.day - 1).coerceIn(2, 28)) },
                onNext = { choice = MonthStart.Payday((p.day + 1).coerceIn(2, 28)) })
        }
        ListRow(stringResource(R.string.guide_month_salary_next), context = stringResource(R.string.guide_month_salary_help),
            onClick = { choice = MonthStart.SalaryNextMonth((choice as? MonthStart.SalaryNextMonth)?.day ?: 25) },
            divider = choice !is MonthStart.SalaryNextMonth,
            end = { if (choice is MonthStart.SalaryNextMonth) Icon(Icons.Outlined.Check, null, tint = c.accent) })
        (choice as? MonthStart.SalaryNextMonth)?.let { s ->
            PeriodSelector(stringResource(R.string.income_shift_day_value, s.day),
                onPrevious = { choice = MonthStart.SalaryNextMonth((s.day - 1).coerceIn(2, 31)) },
                onNext = { choice = MonthStart.SalaryNextMonth((s.day + 1).coerceIn(2, 31)) })
        }
        Text(
            stringResource(R.string.guide_month_preview, f.period(preview.label),
                "${f.day(preview.days.start)} – ${f.day(preview.days.endInclusive)}"),
            style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.guide_month_pay_example, f.day(preview.salaryExample.first), f.period(preview.salaryExample.second)),
            style = FullaType.secondary, color = c.inkMuted,
        )
        if (savedLater) Text(stringResource(R.string.guide_save_later), style = FullaType.secondary, color = c.inkMuted,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * Setup step 2: what each account holds today. Blank keeps it at 0; only the
 * accounts somebody actually typed a number for are saved, one
 * [io.github.sirallap.fulla.client.remote.Structure.ACCOUNT] upsert each. As
 * with [MonthStartStep], a shared household this can't reach right now shows
 * [R.string.guide_save_later] instead of silently dropping what was typed,
 * and stays on the step until Next is pressed again.
 */
@Composable
fun OpeningBalancesStep(view: HouseholdView, stepOf: Pair<Int, Int>, onNext: () -> Unit, onSkip: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = FullaTheme.colors
    val f = view.formats
    val accounts = remember(view) { view.config.accounts.filter { !it.archived }.sortedBy { it.sort } }
    val texts = remember { mutableStateMapOf<String, String>() }
    var savedLater by remember { mutableStateOf(false) }

    fun save() {
        if (savedLater) { onNext(); return }
        scope.launch {
            val api = if (view.state.connected) container.api() else null
            var failed = view.state.connected && api == null
            if (!failed) {
                for (a in accounts) {
                    val text = texts[a.id].orEmpty()
                    if (text.isBlank()) continue
                    val minor = parseOpeningBalance(text, f) ?: continue
                    val item = bundleItem(view, Structure.ACCOUNT, a.id) ?: continue
                    val ok = runCatching {
                        container.ledger.upsert(view.id, Structure.ACCOUNT,
                            JsonObject(item + mapOf(
                                "opening_balance_minor" to JsonPrimitive(minor),
                                // Typing a balance here means "this is what the
                                // account holds today": the date it was measured
                                // is today, same as a brand new account in the
                                // settings dialog.
                                "opening_balance_date" to JsonPrimitive(java.time.LocalDate.now().toString()),
                            )), api)
                    }.isSuccess
                    if (!ok) failed = true
                }
            }
            if (failed) savedLater = true else onNext()
        }
    }

    GuideSheet(
        title = stringResource(R.string.guide_accounts_title),
        stepOf = stepOf,
        primaryLabel = stringResource(R.string.guide_next),
        onPrimary = { save() },
        onSkip = onSkip,
        dismissOnOutsideTap = false,
    ) {
        for (a in accounts) {
            OutlinedTextField(
                value = texts[a.id] ?: "",
                onValueChange = { texts[a.id] = it },
                label = { Text(a.name) },
                placeholder = { Text(f.plain(0L)) },
                supportingText = { Text(stringResource(R.string.opening_balance_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        if (savedLater) Text(stringResource(R.string.guide_save_later), style = FullaType.secondary, color = c.inkMuted,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/** Setup step 3 (only offered when the phone has biometrics or a screen lock set up): the app lock, off by default. */
@Composable
fun LockStep(stepOf: Pair<Int, Int>, onNext: () -> Unit, onSkip: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val checked = settings?.lock ?: false

    GuideSheet(
        title = stringResource(R.string.guide_lock_title),
        stepOf = stepOf,
        primaryLabel = stringResource(R.string.guide_next),
        onPrimary = onNext,
        onSkip = onSkip,
    ) {
        SwitchRow(stringResource(R.string.lock), stringResource(R.string.lock_text), checked) { on ->
            scope.launch { container.settings.setLock(on) }
        }
    }
}
