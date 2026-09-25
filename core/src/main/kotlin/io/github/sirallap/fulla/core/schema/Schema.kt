// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.schema

import io.github.sirallap.fulla.core.model.TransactionKind
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The types a household-defined field can have. A dimension can be grouped
 * by in Insights (who, which option); a measure can be summed (a number, an
 * amount). That is what makes a new field extend what the app can tell you,
 * not only what it can store.
 */
enum class FieldType(val key: String, val isDimension: Boolean = false, val isMeasure: Boolean = false) {
    TEXT("text"),
    NUMBER("number", isMeasure = true),
    MONEY("money", isMeasure = true),
    DATE("date"),
    SELECT("select", isDimension = true),
    MULTISELECT("multiselect", isDimension = true),
    BOOLEAN("boolean", isDimension = true),
    MEMBER("member", isDimension = true);

    companion object {
        fun of(key: String): FieldType? = entries.firstOrNull { it.key == key }
    }
}

data class CustomField(
    val id: String,
    /** Lower case, digits and underscores; the key its values are stored under. Never changes. */
    val key: String,
    /** Label per language code, e.g. {"en": "Shop", "es": "Tienda"}. */
    val labels: Map<String, String>,
    val type: FieldType,
    val appliesTo: Set<TransactionKind>,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
    /** Canonical text: digits for money, "true"/"false", a JSON array for multiselect. */
    val defaultValue: String? = null,
    val showInList: Boolean = false,
    val sort: Int = 0,
    val archived: Boolean = false,
) {
    fun label(language: String): String = labels[language] ?: labels["en"] ?: labels.values.firstOrNull() ?: key
}

sealed interface FieldProblem {
    val key: String

    data class Required(override val key: String) : FieldProblem
    data class WrongType(override val key: String, val expected: FieldType) : FieldProblem
    data class UnknownMember(override val key: String) : FieldProblem
}

/** A value checked and in canonical form, and a warning if it deserves one. */
data class CheckedValue(val value: Any, val warning: String? = null)

/** The result of merging incoming custom field values into stored ones. */
data class MergedExtras(
    val extras: Map<String, Any?>,
    val warnings: List<String>,
    val problems: List<FieldProblem>,
)

/**
 * Custom fields: which to show, how to group and sum by them, and how to
 * check and merge their values. The rules match fulla.field_value and
 * fulla.merge_extras in the database exactly, so the phone refuses what the
 * server would refuse, before the row ever leaves.
 */
object SchemaEngine {

    private val NUMBER = Regex("^-?[0-9]{1,15}(\\.[0-9]{1,10})?$")
    private val INTEGER = Regex("^-?[0-9]{1,18}$")

    fun fieldsForForm(fields: List<CustomField>, kind: TransactionKind): List<CustomField> =
        fields.filter { !it.archived && kind in it.appliesTo }.sortedWith(compareBy({ it.sort }, { it.key }))

    fun fieldsForList(fields: List<CustomField>): List<CustomField> =
        fields.filter { !it.archived && it.showInList }.sortedWith(compareBy({ it.sort }, { it.key }))

    fun dimensions(fields: List<CustomField>): List<CustomField> = fields.filter { !it.archived && it.type.isDimension }
    fun measures(fields: List<CustomField>): List<CustomField> = fields.filter { !it.archived && it.type.isMeasure }

    /** One value of one field, or the reason it does not fit. */
    fun check(field: CustomField, value: Any, memberIds: Set<String>): Result<CheckedValue> {
        fun wrong() = Result.failure<CheckedValue>(FieldException(FieldProblem.WrongType(field.key, field.type)))
        return when (field.type) {
            FieldType.TEXT ->
                if (value is String && value.length <= 500) Result.success(CheckedValue(value)) else wrong()
            FieldType.NUMBER -> {
                val text = (value as? String) ?: (value as? Number)?.let { BigDecimal(it.toString()).toPlainString() }
                if (text != null && NUMBER.matches(text)) {
                    Result.success(CheckedValue(BigDecimal(text).stripTrailingZeros().toPlainString()))
                } else wrong()
            }
            FieldType.MONEY -> {
                val text = (value as? String) ?: (value as? Number)?.let { if (it is Long || it is Int) it.toString() else null }
                if (text != null && INTEGER.matches(text)) Result.success(CheckedValue(text.toLong())) else wrong()
            }
            FieldType.DATE ->
                if (value is String && runCatching { LocalDate.parse(value) }.isSuccess && value.length == 10) {
                    Result.success(CheckedValue(value))
                } else wrong()
            FieldType.SELECT ->
                if (value is String && value.length in 1..100) {
                    val unknown = field.options.isNotEmpty() && value !in field.options
                    Result.success(CheckedValue(value, if (unknown) "unknown_option:${field.key}" else null))
                } else wrong()
            FieldType.MULTISELECT -> {
                val list = (value as? List<*>)?.takeIf { l -> l.all { it is String && it.length in 1..100 } }
                if (list != null) {
                    val unknown = field.options.isNotEmpty() && list.any { it !in field.options }
                    Result.success(CheckedValue(list, if (unknown) "unknown_option:${field.key}" else null))
                } else wrong()
            }
            FieldType.BOOLEAN -> if (value is Boolean) Result.success(CheckedValue(value)) else wrong()
            FieldType.MEMBER ->
                if (value is String && value.lowercase() in memberIds) {
                    Result.success(CheckedValue(value.lowercase()))
                } else Result.failure(FieldException(FieldProblem.UnknownMember(field.key)))
        }
    }

    /** A field's default value as the value it stands for. */
    fun defaultOf(field: CustomField): Any? {
        val text = field.defaultValue ?: return null
        return when (field.type) {
            FieldType.MONEY -> text.toLongOrNull()
            FieldType.BOOLEAN -> text.toBooleanStrictOrNull()
            FieldType.MULTISELECT -> text.removePrefix("[").removeSuffix("]").split(',')
                .map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
            else -> text
        }
    }

    /**
     * Merges incoming values into stored ones. An absent key keeps its stored
     * value, a null clears it, an unknown key is dropped with a warning, and a
     * required field with a default gets it when missing.
     */
    fun merge(
        fields: List<CustomField>,
        kind: TransactionKind,
        incoming: Map<String, Any?>,
        stored: Map<String, Any?>,
        memberIds: Set<String>,
    ): MergedExtras {
        val out = stored.toMutableMap()
        val warnings = mutableListOf<String>()
        val problems = mutableListOf<FieldProblem>()
        val byKey = fields.associateBy { it.key }
        for ((key, value) in incoming) {
            val field = byKey[key]
            if (field == null) {
                warnings += "unknown_field:$key"
                continue
            }
            if (value == null) {
                out.remove(key)
                continue
            }
            check(field, value, memberIds).fold(
                onSuccess = { checked ->
                    out[key] = checked.value
                    checked.warning?.let { warnings += it }
                },
                onFailure = { problems += (it as FieldException).problem },
            )
        }
        for (field in fields) {
            if (field.archived || !field.required || kind !in field.appliesTo || out.containsKey(field.key)) continue
            val default = defaultOf(field)
            if (default == null) problems += FieldProblem.Required(field.key) else out[field.key] = default
        }
        return MergedExtras(out, warnings, problems)
    }
}

class FieldException(val problem: FieldProblem) : IllegalArgumentException(problem.toString())
