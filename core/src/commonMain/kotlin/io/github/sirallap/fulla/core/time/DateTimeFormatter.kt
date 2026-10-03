// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.time

/**
 * Reads a date written in a bank's layout: `dd/MM/yyyy`, `MM/dd/yyyy`,
 * `yyyy-MM-dd`, `d/M/yyyy`, `yyyyMMdd`. Letters are `d`, `M` and `y`; any
 * other character stands for itself. `dd`, `MM` and `yyyy` need exactly that
 * many digits, `d` and `M` take one or two, `yy` is read as 20yy.
 *
 * Like java.time's default, a day past the end of the month ("31/04/2030")
 * falls on the last day of that month.
 */
class DateTimeFormatter private constructor(private val tokens: List<Token>) {

    private sealed interface Token {
        data class Literal(val char: Char) : Token
        data class Field(val letter: Char, val width: Int) : Token
    }

    internal fun parseDate(text: String): LocalDate {
        var at = 0
        var year: Int? = null
        var month: Int? = null
        var day: Int? = null
        fun fail(): Nothing = throw DateTimeException("Text '$text' could not be parsed at index $at")
        for (token in tokens) {
            when (token) {
                is Token.Literal -> {
                    if (at >= text.length || text[at] != token.char) fail()
                    at++
                }
                is Token.Field -> {
                    val (min, max) = when {
                        token.letter == 'y' && token.width == 2 -> 2 to 2
                        token.letter == 'y' -> token.width to token.width
                        token.width >= 2 -> 2 to 2
                        else -> 1 to 2
                    }
                    var end = at
                    while (end < text.length && end - at < max && text[end] in '0'..'9') end++
                    if (end - at < min) fail()
                    val value = text.substring(at, end).toInt()
                    at = end
                    when (token.letter) {
                        'y' -> year = if (token.width == 2) 2000 + value else value
                        'M' -> month = value
                        else -> day = value
                    }
                }
            }
        }
        if (at != text.length) fail()
        val y = year ?: throw DateTimeException("Text '$text' could not be parsed: no year")
        val m = month ?: throw DateTimeException("Text '$text' could not be parsed: no month")
        val d = day ?: throw DateTimeException("Text '$text' could not be parsed: no day")
        if (m !in 1..12) throw DateTimeException("Invalid value for MonthOfYear: $m")
        if (d !in 1..31) throw DateTimeException("Invalid value for DayOfMonth: $d")
        return LocalDate.of(y, m, minOf(d, LocalDate.monthLength(y, m)))
    }

    companion object {
        fun ofPattern(pattern: String): DateTimeFormatter {
            val tokens = mutableListOf<Token>()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                if (c == 'd' || c == 'M' || c == 'y') {
                    var j = i
                    while (j < pattern.length && pattern[j] == c) j++
                    val width = j - i
                    require(c != 'y' || width == 2 || width == 4) { "Unsupported year pattern: $pattern" }
                    require(width <= 2 || c == 'y') { "Unsupported pattern: $pattern" }
                    tokens += Token.Field(c, width)
                    i = j
                } else {
                    require(!c.isLetter()) { "Unknown pattern letter: $c" }
                    tokens += Token.Literal(c)
                    i++
                }
            }
            return DateTimeFormatter(tokens)
        }

        /** 20300131. */
        val BASIC_ISO_DATE: DateTimeFormatter = ofPattern("yyyyMMdd")
    }
}
