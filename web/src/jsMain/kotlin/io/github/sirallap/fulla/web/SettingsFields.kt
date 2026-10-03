// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.local.FieldKeys
import io.github.sirallap.fulla.client.platform.randomUuid
import io.github.sirallap.fulla.client.remote.Structure
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.schema.CustomField
import io.github.sirallap.fulla.core.schema.FieldType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.w3c.dom.HTMLElement

internal fun fieldTypeName(type: FieldType): String = t(when (type) {
    FieldType.TEXT -> "field_text"; FieldType.NUMBER -> "field_number"; FieldType.MONEY -> "field_money"; FieldType.DATE -> "field_date"
    FieldType.SELECT -> "field_select"; FieldType.MULTISELECT -> "field_multiselect"; FieldType.BOOLEAN -> "field_boolean"; FieldType.MEMBER -> "field_member"
})

/**
 * Fields the household adds to every entry: a shop, a receipt, who it was for. Once created a field keeps its key
 * and type, so its values always mean the same thing; the label can change and the field can be archived.
 */
object FieldsPage {
    fun build(parent: HTMLElement, view: HouseholdView, canEdit: Boolean) = parent.run {
        val language = I18n.language
        note(t("fields_text"))
        if (view.config.fields.isEmpty()) emptyState("tune", t("no_fields_title"), t("no_fields_text"))
        div("rows") {
            for (f in view.config.fields.sortedWith(compareBy({ it.archived }, { it.sort }))) {
                listRow(f.label(language), dim = f.archived,
                    context = fieldTypeName(f.type) + if (f.options.isNotEmpty()) " · " + f.options.joinToString(", ") else "",
                    detail = if (f.archived) t("archived") else null,
                    onClick = if (canEdit) ({ edit(view, f) }) else null)
            }
            if (canEdit) listRow(t("add_field"), start = leadIcon("add")) { edit(view, null) }
        }
    }

    private fun edit(view: HouseholdView, existing: CustomField?) {
        val language = I18n.language
        var categories = existing?.categoryIds.orEmpty()
        var type = existing?.type ?: FieldType.TEXT
        var kinds = existing?.appliesTo ?: setOf(TransactionKind.EXPENSE)
        var inList = existing?.showInList ?: false
        var archived = existing?.archived ?: false
        var label = existing?.label(language) ?: ""
        var options = existing?.options?.joinToString(", ") ?: ""
        var saveButton: HTMLElement? = null
        fun valid(): Boolean {
            val list = options.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            return label.isNotBlank() && kinds.isNotEmpty() && ((type != FieldType.SELECT && type != FieldType.MULTISELECT) || list.isNotEmpty())
        }
        fun refresh() { saveButton?.let { if (valid()) it.removeAttribute("disabled") else it.setAttribute("disabled", "") } }
        sheet(t(if (existing == null) "add_field" else "edit")) { close ->
            val body = div("")
            body.run {
                fun paint() {
                    body.clear()
                    body.run {
                        val name = field(t("name"), label) { attr("maxlength", "40") }
                        name.on("input") { label = name.value; refresh() }
                        if (existing == null) {
                            formLabel(t("field_type"))
                            chipRow(wrap = true) { for (ty in FieldType.entries) chip(fieldTypeName(ty), ty == type) { type = ty; paint() } }
                        } else note(t("field_type_fixed", fieldTypeName(type)))
                        if (type == FieldType.SELECT || type == FieldType.MULTISELECT) {
                            val o = field(t("field_options"), options, help = t("field_options_help"))
                            o.on("input") { options = o.value; refresh() }
                        }
                        formLabel(t("field_applies_to"))
                        chipRow(wrap = true) {
                            for ((k, n) in listOf(TransactionKind.EXPENSE to "kind_expense", TransactionKind.INCOME to "kind_income", TransactionKind.REFUND to "kind_refund", TransactionKind.TRANSFER to "kind_transfer"))
                                chip(t(n), k in kinds) { kinds = if (k in kinds) kinds - k else kinds + k; paint() }
                        }
                        // Limited to categories, it is asked right under them when writing a row down: Pets › Vet › who for.
                        formLabel(t("field_categories"))
                        note(t("field_categories_help"))
                        val all = view.config.categories.filter { !it.archived }
                        val named = all.map { c -> c to (all.firstOrNull { it.id == c.parentId }?.let { "${it.name} › ${c.name}" } ?: c.name) }
                        chipRow(wrap = true) {
                            for ((c, n) in named.sortedBy { it.second.lowercase() }) chip(n, c.id in categories) { categories = if (c.id in categories) categories - c.id else categories + c.id; paint() }
                        }
                        div("rows") {
                            switchRow(t("field_in_list"), t("field_in_list_help"), inList) { inList = it }
                            if (existing != null) switchRow(t("archive"), t("archive_help"), archived) { archived = it }
                        }
                        val optionList = options.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                        val needsOptions = type == FieldType.SELECT || type == FieldType.MULTISELECT
                        val valid = label.isNotBlank() && kinds.isNotEmpty() && (!needsOptions || optionList.isNotEmpty())
                        div("actions") {
                            saveButton = primaryButton(t("save"), enabled = valid) {
                                close()
                                val key = existing?.key ?: FieldKeys.from(label, view.config.fields.map { it.key }.toSet())
                                val labels = (existing?.labels ?: emptyMap()) + (language to label.trim())
                                val item = JsonObject(mapOf(
                                    "id" to JsonPrimitive(existing?.id ?: randomUuid()), "key" to JsonPrimitive(key),
                                    "labels" to JsonObject(labels.mapValues { JsonPrimitive(it.value) }), "type" to JsonPrimitive(type.key),
                                    "applies_to" to JsonArray(kinds.map { JsonPrimitive(it.key) }), "required" to JsonPrimitive(false),
                                    "options" to JsonArray(if (needsOptions) optionList.map(::JsonPrimitive) else emptyList()),
                                    "show_in_list" to JsonPrimitive(inList), "sort" to JsonPrimitive(existing?.sort ?: view.config.fields.size),
                                    "archived" to JsonPrimitive(archived),
                                ))
                                val before = existing?.categoryIds.orEmpty()
                                val picked = categories
                                App.launch {
                                    try {
                                        Ledger.upsert(Structure.FIELD, item)
                                        if (picked != before) Ledger.setFieldCategories((item["id"] as JsonPrimitive).content, picked)
                                    } catch (e: Throwable) { App.toast(Remote.message(e)) }
                                }
                            }
                            quietButton(t("cancel")) { close() }
                        }
                    }
                }
                paint()
            }
        }
    }

    private fun HTMLElement.formLabel(caption: String) { div("form-label") { span("t-label") { text(caption) } } }
}

/** "When a statement line says X, it is category Y." Made while importing; here they can be paused and resumed. */
object RulesPage {
    fun build(parent: HTMLElement, view: HouseholdView, canEdit: Boolean) = parent.run {
        val rules = io.github.sirallap.fulla.client.wire.Wire.rules(view.bundle).sortedWith(compareBy({ !it.active }, { it.sort }))
        note(t("rules_text"))
        if (rules.isEmpty()) emptyState("rule", t("no_rules_title"), t("no_rules_text"))
        div("rows") {
            for (r in rules) listRow("“${r.pattern}”", dim = !r.active,
                context = "→ " + (view.config.category(r.categoryId)?.name ?: t("uncategorized")),
                detail = if (!r.active) t("paused") else null,
                onClick = if (canEdit) ({
                    val stored = (view.bundle["categorization_rules"]?.jsonArray.orEmpty()).map { it.jsonObject }.firstOrNull { (it["id"] as? JsonPrimitive)?.content == r.id }
                    if (stored != null) App.launch { Ledger.upsert(Structure.RULE, JsonObject(stored + ("active" to JsonPrimitive(!r.active)))) }
                }) else null)
        }
    }
}
