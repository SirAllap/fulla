// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.importers

import io.github.sirallap.fulla.core.money.DecimalStyle
import io.github.sirallap.fulla.core.text.normalizeName
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class ColumnRole { DATE, AMOUNT, DESCRIPTION, CATEGORY, KIND, PAYER, ACCOUNT, RECURRENCE }

/**
 * A first guess at a CSV's layout from its header and a few values, in the
 * six languages the app speaks, so a file with ordinary column names maps
 * itself and the person only corrects what is wrong. Only ever a starting
 * point: every guess is shown as a selected chip that can be changed.
 */
object ColumnGuess {

    private val WORDS: Map<ColumnRole, List<String>> = mapOf(
        ColumnRole.DATE to listOf("date", "fecha", "datum", "data", "booking date", "fecha operacion", "buchungstag",
            "date operation", "data operazione", "data movimento", "day", "dia"),
        ColumnRole.AMOUNT to listOf("amount", "importe", "monto", "cantidad", "betrag", "montant", "importo", "valor",
            "quantia", "sum", "value", "total"),
        ColumnRole.DESCRIPTION to listOf("description", "descripcion", "concepto", "note", "nota", "notes", "memo", "payee",
            "details", "detalle", "beschreibung", "verwendungszweck", "libelle", "descrizione", "causale", "descricao", "text"),
        ColumnRole.CATEGORY to listOf("category", "categoria", "kategorie", "categorie"),
        ColumnRole.KIND to listOf("type", "tipo", "kind", "flow", "flujo", "typ", "art", "sens", "direction", "movimiento"),
        ColumnRole.PAYER to listOf("paid by", "payer", "who", "quien", "pagado por", "pagador", "bezahlt von", "wer",
            "paye par", "qui", "pagato da", "chi", "pago por", "quem", "person", "member", "miembro"),
        ColumnRole.ACCOUNT to listOf("account", "cuenta", "konto", "compte", "conto", "conta", "wallet"),
        ColumnRole.RECURRENCE to listOf("recurrence", "recurrencia", "recurring", "repeats", "repeat", "fixed", "fijo", "recurrente",
            "wiederkehrend", "fest", "fixe", "ricorrenza", "ricorrente", "fisso", "recorrencia", "recorrente", "fixo"),
    )

    /** Values in a recurrence column that mean a fixed amount. */
    private val FIXED = setOf("fixed", "fijo", "fija", "fixe", "fest", "fix", "fisso", "fissa", "fixo", "fixa",
        "recurring", "recurrente", "ricorrente", "recorrente", "wiederkehrend", "yes", "si", "oui", "ja", "sim", "true")

    /** Values in a type column that mean money in. */
    private val INCOME = setOf("in", "income", "ingreso", "ingresos", "entrada", "entradas", "credit", "credito", "haber",
        "abono", "einnahme", "einnahmen", "gutschrift", "revenu", "revenus", "entree", "recette", "entrata", "entrate",
        "receita", "receitas", "+")

    /** Which column plays which part. A column plays at most one. */
    fun columns(header: List<String>): Map<ColumnRole, Int> {
        val names = header.map { it.normalizeName() }
        val taken = mutableSetOf<Int>()
        val found = linkedMapOf<ColumnRole, Int>()
        for (exact in listOf(true, false)) {
            for (role in ColumnRole.entries) {
                if (role in found) continue
                val words = WORDS.getValue(role)
                val i = names.indices.firstOrNull { i ->
                    i !in taken && words.any { w ->
                        if (exact) names[i] == w else Regex("(^|[^\\p{L}])${Regex.escape(w)}([^\\p{L}]|$)").containsMatchIn(names[i])
                    }
                } ?: continue
                found[role] = i
                taken += i
            }
        }
        return found
    }

    /** The first of [candidates] that reads every non-blank value, or null. */
    fun dateFormat(values: List<String>, candidates: List<String>): String? {
        val sample = values.map { it.trim() }.filter { it.isNotEmpty() }.take(30)
        if (sample.isEmpty()) return null
        return candidates.firstOrNull { pattern ->
            val f = DateTimeFormatter.ofPattern(pattern)
            sample.all { runCatching { LocalDate.parse(it, f) }.isSuccess }
        }
    }

    /** Comma or dot, when the amounts make it plain; null when they do not. */
    fun decimal(values: List<String>): DecimalStyle? {
        val sample = values.map { it.trim() }
        return when {
            sample.any { Regex("\\d,\\d{1,2}$").containsMatchIn(it) } -> DecimalStyle.COMMA
            sample.any { Regex("\\d\\.\\d{1,2}$").containsMatchIn(it) } -> DecimalStyle.DOT
            else -> null
        }
    }

    /** Which of a type column's values mean money in. */
    fun incomeValues(values: List<String>): List<String> = values.filter { it.normalizeName() in INCOME }

    /** Which of a recurrence column's values mean a fixed amount. */
    fun fixedValues(values: List<String>): List<String> = values.filter { it.normalizeName() in FIXED }
}
