// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.settings

import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.local.FieldKeys
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.schema.CustomField
import io.github.sirallap.fulla.core.schema.FieldType
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.LocalContainer
import io.github.sirallap.fulla.ui.components.Chip
import io.github.sirallap.fulla.ui.components.EmptyState
import io.github.sirallap.fulla.ui.components.ListRow
import io.github.sirallap.fulla.ui.components.SwitchRow
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import java.util.UUID

internal fun typeName(t: FieldType): Int = when (t) {
    FieldType.TEXT -> R.string.field_text
    FieldType.NUMBER -> R.string.field_number
    FieldType.MONEY -> R.string.field_money
    FieldType.DATE -> R.string.field_date
    FieldType.SELECT -> R.string.field_select
    FieldType.MULTISELECT -> R.string.field_multiselect
    FieldType.BOOLEAN -> R.string.field_boolean
    FieldType.MEMBER -> R.string.field_member
}

/**
 * Fields the household adds to every entry: a shop, a receipt, who it was
 * for. Once created a field keeps its key and type, so its values always mean
 * the same thing; the label can change and the field can be archived.
 */
@Composable
fun FieldsSettings(view: HouseholdView, canEdit: Boolean, change: Change) {
    val ledger = LocalContainer.current.ledger
    val c = FullaTheme.colors
    val language = Locale.getDefault().language
    var editing by remember { mutableStateOf<CustomField?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.fields_text), style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(20.dp))
        if (view.config.fields.isEmpty()) EmptyState(Icons.Outlined.Tune, stringResource(R.string.no_fields_title), stringResource(R.string.no_fields_text))
        for (f in view.config.fields.sortedWith(compareBy({ it.archived }, { it.sort }))) {
            ListRow(f.label(language), titleColor = if (f.archived) c.inkMuted else c.ink,
                context = stringResource(typeName(f.type)) + if (f.options.isNotEmpty()) " · " + f.options.joinToString(", ") else "",
                detail = if (f.archived) stringResource(R.string.archived) else null,
                onClick = if (canEdit) ({ editing = f }) else null)
        }
        if (canEdit) ListRow(stringResource(R.string.add_field), icon = Icons.Outlined.Add, onClick = { creating = true })
    }
    if (creating || editing != null) {
        FieldDialog(view, editing, language, onDismiss = { creating = false; editing = null }) { item ->
            change { api -> ledger.upsert(view.id, Structure.FIELD, item, api) }
        }
    }
}

@Composable
private fun FieldDialog(view: HouseholdView, existing: CustomField?, language: String, onDismiss: () -> Unit, onSave: (JsonObject) -> Unit) {
    var label by remember { mutableStateOf(existing?.label(language) ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: FieldType.TEXT) }
    var options by remember { mutableStateOf(existing?.options?.joinToString(", ") ?: "") }
    var kinds by remember { mutableStateOf(existing?.appliesTo ?: setOf(TransactionKind.EXPENSE)) }
    var inList by remember { mutableStateOf(existing?.showInList ?: false) }
    var archived by remember { mutableStateOf(existing?.archived ?: false) }
    val needsOptions = type == FieldType.SELECT || type == FieldType.MULTISELECT
    val optionList = options.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val valid = label.isNotBlank() && kinds.isNotEmpty() && (!needsOptions || optionList.isNotEmpty())
    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.add_field else R.string.edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it.take(40) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name)) }, singleLine = true)
                if (existing == null) {
                    Text(stringResource(R.string.field_type), style = FullaType.label, color = FullaTheme.colors.inkMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in FieldType.entries) Chip(stringResource(typeName(t)), t == type, { type = t })
                    }
                } else {
                    Text(stringResource(R.string.field_type_fixed, stringResource(typeName(type))), style = FullaType.secondary, color = FullaTheme.colors.inkMuted)
                }
                if (needsOptions) {
                    OutlinedTextField(options, { options = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_options)) },
                        supportingText = { Text(stringResource(R.string.field_options_help)) })
                }
                Text(stringResource(R.string.field_applies_to), style = FullaType.label, color = FullaTheme.colors.inkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, name) in listOf(TransactionKind.EXPENSE to R.string.kind_expense, TransactionKind.INCOME to R.string.kind_income,
                        TransactionKind.REFUND to R.string.kind_refund, TransactionKind.TRANSFER to R.string.kind_transfer)) {
                        Chip(stringResource(name), k in kinds, { kinds = if (k in kinds) kinds - k else kinds + k })
                    }
                }
                SwitchRow(stringResource(R.string.field_in_list), stringResource(R.string.field_in_list_help), inList) { inList = it }
                if (existing != null) SwitchRow(stringResource(R.string.archive), stringResource(R.string.archive_help), archived) { archived = it }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val key = existing?.key ?: FieldKeys.from(label, view.config.fields.map { it.key }.toSet())
                val labels = (existing?.labels ?: emptyMap()) + (language to label.trim())
                onSave(JsonObject(mapOf(
                    "id" to JsonPrimitive(existing?.id ?: UUID.randomUUID().toString()),
                    "key" to JsonPrimitive(key),
                    "labels" to JsonObject(labels.mapValues { JsonPrimitive(it.value) }),
                    "type" to JsonPrimitive(type.key),
                    "applies_to" to JsonArray(kinds.map { JsonPrimitive(it.key) }),
                    "required" to JsonPrimitive(false),
                    "options" to JsonArray(if (needsOptions) optionList.map(::JsonPrimitive) else emptyList()),
                    "show_in_list" to JsonPrimitive(inList),
                    "sort" to JsonPrimitive(existing?.sort ?: view.config.fields.size),
                    "archived" to JsonPrimitive(archived),
                )))
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
