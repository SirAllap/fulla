// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.local

import io.github.sirallap.fulla.core.text.normalizeName

/**
 * The storage key of a new custom field, from the label a person typed.
 * Keys never change once a field exists, so they are made once, here, by the
 * database's rules: lower case, digits and underscores, starting with a
 * letter, at most 32 characters, not a built-in column, unique.
 */
object FieldKeys {
    val RESERVED = setOf(
        "id", "household_id", "kind", "date", "amount_minor", "category_id", "account_id", "to_account_id",
        "paid_by_member_id", "to_member_id", "split", "recurrence", "note", "tags", "extras", "status",
        "recurring_rule_id", "occurrence_date", "import_fingerprint", "original_amount_minor",
        "original_currency", "created_at", "client_updated_at", "server_seq", "created_by_member_id",
        "updated_by_user_id",
    )
    private val VALID = Regex("^[a-z][a-z0-9_]{0,31}$")

    fun from(label: String, taken: Set<String>): String {
        var base = label.normalizeName().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (base.isEmpty() || !base[0].isLetter()) base = "field_$base".trimEnd('_')
        base = base.take(28).trimEnd('_')
        var key = base
        var n = 2
        while (key in taken || key in RESERVED) key = "${base.take(28)}_${n++}"
        check(VALID.matches(key))
        return key
    }
}
