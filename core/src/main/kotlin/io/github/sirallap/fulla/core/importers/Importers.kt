// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.importers

import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.core.money.DecimalStyle
import io.github.sirallap.fulla.core.money.MoneyParser
import io.github.sirallap.fulla.core.text.normalizeName
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Bank statements in, candidate transactions out. No importer knows about any
 * particular bank: CSV is read through a column mapping the user sets up once
 * (an [ImportProfile]), and OFX and QIF are standard formats many banks export.
 */

/** One line of a statement. [amountMinor] is signed: negative is money out. */
data class StatementLine(
    val date: LocalDate,
    val amountMinor: Long,
    val description: String,
    /** The bank's own category text, if the file has one. */
    val bankCategory: String? = null,
    /** An id the bank gives the line (OFX FITID), if any. */
    val bankId: String? = null,
    /** Who paid, as the file writes it: matched to a member by name. */
    val payer: String? = null,
    /** Which account, as the file writes it: matched to an account by name. */
    val account: String? = null,
    /** True when the file marks the line as a fixed amount; null when it says nothing. */
    val fixed: Boolean? = null,
)

data class SkippedLine(val line: Int, val reason: String)

data class StatementResult(val lines: List<StatementLine>, val skipped: List<SkippedLine>)

sealed interface AmountColumns {
    /** One signed column. With [negativeIsExpense] false, the signs are flipped. */
    data class Single(val column: Int, val negativeIsExpense: Boolean = true) : AmountColumns

    /** Money out in one column, money in in another. */
    data class Split(val debitColumn: Int, val creditColumn: Int) : AmountColumns
}

/** How to read one CSV layout. Stored per household, created by the user. */
data class ImportProfile(
    /** Null: detect (UTF-8 if the bytes are valid UTF-8, else Windows-1252). */
    val encoding: String? = null,
    val delimiter: Char = ',',
    val skipRows: Int = 0,
    val hasHeader: Boolean = true,
    val dateColumn: Int,
    val dateFormat: String,
    val amount: AmountColumns,
    val decimal: DecimalStyle = DecimalStyle.DOT,
    val descriptionColumns: List<Int> = emptyList(),
    val categoryColumn: Int? = null,
    /**
     * A column that says whether a line is money in or out, for files whose
     * amounts are all positive. A line is income when the column holds one of
     * [incomeValues] (compared without case or accents), spending otherwise,
     * and the amount's own sign is ignored.
     */
    val kindColumn: Int? = null,
    val incomeValues: List<String> = emptyList(),
    val payerColumn: Int? = null,
    val accountColumn: Int? = null,
    /** A column that marks fixed amounts (rent, salary): fixed when it holds one of [fixedValues]. */
    val fixedColumn: Int? = null,
    val fixedValues: List<String> = emptyList(),
)

interface StatementImporter {
    /** How sure this importer is that it can read the file, 0..1. */
    fun detect(bytes: ByteArray): Double
}

// ── text ─────────────────────────────────────────────────────────────────────

object TextDecoding {
    /**
     * The file's text: the named encoding if given, else UTF-8 when the bytes
     * are valid UTF-8 (a BOM is dropped), else Windows-1252, which is what
     * most spreadsheet exports that are not UTF-8 turn out to be.
     */
    fun decode(bytes: ByteArray, encoding: String? = null): String {
        if (encoding != null && !encoding.equals("UTF-8", ignoreCase = true)) {
            return String(bytes, Charset.forName(encoding))
        }
        val explicitUtf8 = encoding != null
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else bytes
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (e: CharacterCodingException) {
            if (explicitUtf8) String(body, Charsets.UTF_8) else String(body, Charset.forName("windows-1252"))
        }
    }
}

// ── CSV ──────────────────────────────────────────────────────────────────────

object Csv {
    /** RFC 4180: quoted fields may contain the delimiter, newlines and doubled quotes. */
    fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                delimiter -> { row += field.toString(); field.clear() }
                '\r' -> Unit
                '\n' -> { row += field.toString(); field.clear(); rows += row; row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    /** The delimiter that splits the first lines most consistently into more than one column. */
    fun guessDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(10).toList()
        return listOf(',', ';', '\t', '|').maxByOrNull { d ->
            val counts = sample.map { line -> parse(line, d).firstOrNull()?.size ?: 0 }
            if (counts.isEmpty() || counts.min() < 2) 0 else counts.count { it == counts.last() } * 100 + counts.last()
        } ?: ','
    }
}

object CsvImporter : StatementImporter {

    override fun detect(bytes: ByteArray): Double {
        val text = TextDecoding.decode(bytes).take(4096)
        if (text.trimStart().startsWith("<") || text.contains("OFXHEADER") || text.startsWith("!Type")) return 0.0
        return if (Csv.parse(text, Csv.guessDelimiter(text)).firstOrNull().orEmpty().size >= 2) 0.5 else 0.0
    }

    /** The first rows as cells, for the column-mapping screen. */
    fun preview(bytes: ByteArray, rows: Int = 6): List<List<String>> {
        val text = TextDecoding.decode(bytes)
        return Csv.parse(text, Csv.guessDelimiter(text)).take(rows)
    }

    /**
     * The different values a column holds below the header, most frequent
     * first: what the screen offers when asking which value means income.
     */
    fun values(bytes: ByteArray, profile: ImportProfile, column: Int, limit: Int = 12): List<String> {
        val rows = Csv.parse(TextDecoding.decode(bytes, profile.encoding), profile.delimiter)
            .drop(profile.skipRows + if (profile.hasHeader) 1 else 0)
        return rows.mapNotNull { it.getOrNull(column)?.trim()?.takeIf { v -> v.isNotEmpty() } }
            .groupingBy { it.normalizeName() }.eachCount().entries
            .sortedByDescending { it.value }.take(limit)
            .map { e -> rows.first { it.getOrNull(column)?.trim()?.normalizeName() == e.key }[column].trim() }
    }

    fun read(bytes: ByteArray, profile: ImportProfile, currency: Currency): StatementResult {
        val income = profile.incomeValues.map { it.normalizeName() }.toSet()
        val fixed = profile.fixedValues.map { it.normalizeName() }.toSet()
        val rows = Csv.parse(TextDecoding.decode(bytes, profile.encoding), profile.delimiter)
        val body = rows.drop(profile.skipRows + if (profile.hasHeader) 1 else 0)
        val first = profile.skipRows + (if (profile.hasHeader) 1 else 0) + 1
        val formatter = DateTimeFormatter.ofPattern(profile.dateFormat)
        val lines = mutableListOf<StatementLine>()
        val skipped = mutableListOf<SkippedLine>()
        body.forEachIndexed { index, cells ->
            val lineNo = first + index
            fun cell(i: Int?): String? = i?.let { cells.getOrNull(it)?.trim() }
            val date = runCatching { LocalDate.parse(cell(profile.dateColumn), formatter) }.getOrNull()
            if (date == null) { skipped += SkippedLine(lineNo, "date"); return@forEachIndexed }
            val amount = when (val a = profile.amount) {
                is AmountColumns.Single -> MoneyParser.parse(cell(a.column).orEmpty(), currency, profile.decimal)
                    ?.let { if (a.negativeIsExpense) it else -it }
                is AmountColumns.Split -> {
                    val debit = cell(a.debitColumn).orEmpty().takeIf { it.isNotEmpty() }
                        ?.let { MoneyParser.parse(it, currency, profile.decimal) }
                    val credit = cell(a.creditColumn).orEmpty().takeIf { it.isNotEmpty() }
                        ?.let { MoneyParser.parse(it, currency, profile.decimal) }
                    when {
                        debit != null && debit != 0L -> -kotlin.math.abs(debit)
                        credit != null -> kotlin.math.abs(credit)
                        else -> null
                    }
                }
            }
            if (amount == null || amount == 0L) { skipped += SkippedLine(lineNo, "amount"); return@forEachIndexed }
            val signed = if (profile.kindColumn == null) amount else {
                val isIncome = cell(profile.kindColumn).orEmpty().normalizeName() in income
                if (isIncome) kotlin.math.abs(amount) else -kotlin.math.abs(amount)
            }
            val description = profile.descriptionColumns.mapNotNull { cell(it)?.takeIf { s -> s.isNotEmpty() } }
                .joinToString(" · ")
            lines += StatementLine(
                date, signed, description,
                bankCategory = cell(profile.categoryColumn)?.takeIf { it.isNotEmpty() },
                payer = cell(profile.payerColumn)?.takeIf { it.isNotEmpty() },
                account = cell(profile.accountColumn)?.takeIf { it.isNotEmpty() },
                fixed = profile.fixedColumn?.let { cell(it).orEmpty().normalizeName() in fixed },
            )
        }
        return StatementResult(lines, skipped)
    }
}

// ── OFX ──────────────────────────────────────────────────────────────────────

/** OFX 1.x (SGML, tags often left open) and 2.x (XML). */
object OfxImporter : StatementImporter {

    private val TRANSACTION = Regex("<STMTTRN>(.*?)(</STMTTRN>|(?=<STMTTRN>)|</BANKTRANLIST>)", RegexOption.DOT_MATCHES_ALL)

    override fun detect(bytes: ByteArray): Double {
        val head = TextDecoding.decode(bytes).take(2048).uppercase()
        return if ("OFXHEADER" in head || "<OFX>" in head) 1.0 else 0.0
    }

    private fun tag(block: String, name: String): String? =
        Regex("<$name>([^<\\r\\n]*)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    fun read(bytes: ByteArray, currency: Currency): StatementResult {
        val text = TextDecoding.decode(bytes)
        val lines = mutableListOf<StatementLine>()
        val skipped = mutableListOf<SkippedLine>()
        TRANSACTION.findAll(text).forEachIndexed { i, m ->
            val block = m.groupValues[1]
            val date = tag(block, "DTPOSTED")?.take(8)?.let {
                runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            }
            val raw = tag(block, "TRNAMT")
            val style = if (raw != null && ',' in raw && '.' !in raw) DecimalStyle.COMMA else DecimalStyle.DOT
            val amount = raw?.let { MoneyParser.parse(it, currency, style) }
            if (date == null || amount == null || amount == 0L) {
                skipped += SkippedLine(i + 1, if (date == null) "date" else "amount")
                return@forEachIndexed
            }
            val description = listOfNotNull(tag(block, "NAME"), tag(block, "MEMO")).distinct().joinToString(" · ")
            lines += StatementLine(date, amount, description, bankId = tag(block, "FITID"))
        }
        return StatementResult(lines, skipped)
    }
}

// ── QIF ──────────────────────────────────────────────────────────────────────

object QifImporter : StatementImporter {

    override fun detect(bytes: ByteArray): Double =
        if (TextDecoding.decode(bytes).trimStart().startsWith("!Type", ignoreCase = true)) 1.0 else 0.0

    /** QIF has no standard date order; [dateFormat] says which (e.g. "MM/dd/yyyy" or "dd/MM/yyyy"). */
    fun read(bytes: ByteArray, currency: Currency, dateFormat: String, decimal: DecimalStyle = DecimalStyle.DOT): StatementResult {
        val formatter = DateTimeFormatter.ofPattern(dateFormat)
        val lines = mutableListOf<StatementLine>()
        val skipped = mutableListOf<SkippedLine>()
        var fields = mutableMapOf<Char, String>()
        var record = 0
        for (raw in TextDecoding.decode(bytes).lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty() || line.startsWith("!")) continue
            if (line == "^") {
                record++
                // Some writers use an apostrophe before a two-digit year: 1/15'30.
                val dateText = fields['D']?.replace('\'', '/')?.replace(' ', '0')
                val date = dateText?.let { runCatching { LocalDate.parse(it, formatter) }.getOrNull() }
                val amount = (fields['T'] ?: fields['U'])?.let { MoneyParser.parse(it, currency, decimal) }
                if (date == null || amount == null || amount == 0L) {
                    skipped += SkippedLine(record, if (date == null) "date" else "amount")
                } else {
                    val description = listOfNotNull(fields['P'], fields['M']).distinct().joinToString(" · ")
                    lines += StatementLine(date, amount, description, bankCategory = fields['L'])
                }
                fields = mutableMapOf()
                continue
            }
            fields[line[0]] = line.substring(1).trim()
        }
        return StatementResult(lines, skipped)
    }
}
