// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.schema.CustomField
import io.github.sirallap.fulla.core.schema.FieldType
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.components.Chip
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.components.SwitchRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * The control for one custom field, one per type: a type is not a labelled
 * text box. Values are written in their wire form (see SchemaEngine.check);
 * null clears the value.
 */
@Composable
fun FieldControl(view: HouseholdView, field: CustomField, value: Any?, onChange: (Any?) -> Unit) {
    val label = field.label(Locale.getDefault().language)
    val f = view.formats
    when (field.type) {
        FieldType.BOOLEAN -> SwitchRow(label, null, value == true) { onChange(it) }
        FieldType.SELECT, FieldType.MEMBER -> {
            Section(label)
            val options = if (field.type == FieldType.MEMBER) view.config.activeMembers.map { it.id to it.displayName }
            else field.options.map { it to it }
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, name) in options) Chip(name, value == id, { onChange(if (value == id) null else id) })
            }
        }
        FieldType.MULTISELECT -> {
            Section(label)
            val chosen = (value as? List<*>)?.filterIsInstance<String>().orEmpty()
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (o in field.options) Chip(o, o in chosen, {
                    val next = if (o in chosen) chosen - o else chosen + o
                    onChange(next.ifEmpty { null })
                })
            }
        }
        FieldType.DATE -> {
            var picking by remember { mutableStateOf(false) }
            Section(label)
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val date = (value as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                Chip(date?.let { f.day(it) } ?: stringResource(R.string.choose_date), date != null, { picking = true })
                if (date != null) Chip(stringResource(R.string.clear), false, { onChange(null) })
            }
            if (picking) {
                val state = rememberDatePickerState()
                DatePickerDialog(onDismissRequest = { picking = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                            picking = false
                        }) { Text(stringResource(R.string.done)) }
                    }) { DatePicker(state) }
            }
        }
        FieldType.MONEY -> {
            var text by remember { mutableStateOf((value as? Number)?.toLong()?.let { f.plain(it) } ?: "") }
            OutlinedTextField(text, {
                text = it
                onChange(if (it.isBlank()) null else MoneyParser.parse(it, f.currency, f.decimalStyle) ?: value)
            }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text(f.currency.code) })
        }
        FieldType.NUMBER -> {
            var text by remember { mutableStateOf(value?.toString() ?: "") }
            OutlinedTextField(text, {
                text = it
                onChange(it.replace(',', '.').trim().ifBlank { null })
            }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        FieldType.TEXT -> {
            OutlinedTextField((value as? String) ?: "", { onChange(it.take(500).ifEmpty { null }) },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), label = { Text(label) }, singleLine = true)
        }
    }
}

/** A field's value as a person reads it in a list, or null when there is nothing to show. */
fun fieldText(view: HouseholdView, field: CustomField, value: Any?): String? {
    val label = field.label(Locale.getDefault().language)
    return when {
        value == null -> null
        field.type == FieldType.BOOLEAN -> if (value == true) label else null
        field.type == FieldType.MEMBER -> view.config.member(value as? String)?.displayName
        field.type == FieldType.MONEY -> (value as? Number)?.toLong()?.let { "$label ${view.formats.money(it)}" }
        field.type == FieldType.DATE -> (value as? String)?.let { runCatching { view.formats.day(LocalDate.parse(it)) }.getOrNull() }
        value is List<*> -> value.joinToString(", ")
        else -> value.toString()
    }
}
