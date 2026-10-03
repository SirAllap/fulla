// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.version

/**
 * A release's notes as the update sheet shows them.
 *
 * The notes are the release's text on GitHub: a little Markdown (bold, italic,
 * code, bullets, headings), and in several languages when the notes have a
 * `## Español` and a `## English` section. The phone shows the one in its
 * own language, English when there is none for it, and everything when the
 * notes are not divided that way.
 */
object ReleaseNotes {

    /** A run of text with the same look. */
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

    sealed interface Block {
        val spans: List<Span>
        data class Heading(override val spans: List<Span>) : Block
        data class Paragraph(override val spans: List<Span>) : Block
        data class Bullet(override val spans: List<Span>) : Block
    }

    /** What the heading of a language's section may say, lower case. */
    private val names = mapOf(
        "en" to listOf("english", "inglés", "ingles"),
        "es" to listOf("español", "espanol", "spanish", "castellano"),
        "de" to listOf("deutsch", "german", "alemán"),
        "fr" to listOf("français", "francais", "french", "francés"),
        "it" to listOf("italiano", "italian"),
        "pt" to listOf("português", "portugues", "portuguese"),
    )

    private fun languageOf(heading: String): String? {
        val h = heading.trim().lowercase()
        return names.entries.firstOrNull { (_, words) -> words.any { h == it } }?.key
    }

    /**
     * The notes in [language] (`"es"`, `"en"`…): what comes before the first
     * `## ` heading, then the section whose heading names the language, else
     * English, else the first. Notes with no language sections come back as
     * they are.
     */
    fun forLanguage(notes: String, language: String): String {
        val lines = notes.lines()
        val starts = lines.indices.filter { lines[it].startsWith("## ") && languageOf(lines[it].removePrefix("## ")) != null }
        if (starts.size < 2) return notes
        val intro = lines.subList(0, starts.first()).joinToString("\n").trim()
        val sections = starts.mapIndexed { n, start ->
            val end = starts.getOrNull(n + 1) ?: lines.size
            languageOf(lines[start].removePrefix("## "))!! to lines.subList(start + 1, end).joinToString("\n").trim()
        }
        val chosen = sections.firstOrNull { it.first == language } ?: sections.firstOrNull { it.first == "en" } ?: sections.first()
        return listOf(intro, chosen.second).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** The notes as blocks: headings, paragraphs and bullets, each with its styled runs. */
    fun parse(notes: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += Block.Paragraph(spans(paragraph.toString().trim())).also { paragraph.clear() }
        }
        for (raw in notes.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                line.trimStart().startsWith("#") -> {
                    flush()
                    blocks += Block.Heading(spans(line.trimStart().trimStart('#').trim()))
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    flush()
                    blocks += Block.Bullet(spans(line.trimStart().drop(2).trim()))
                }
                // A line that continues the bullet above it.
                raw.startsWith("  ") && blocks.lastOrNull() is Block.Bullet && paragraph.isEmpty() -> {
                    val last = blocks.removeAt(blocks.lastIndex) as Block.Bullet
                    blocks += Block.Bullet(last.spans + spans(" " + line.trim()))
                }
                else -> { if (paragraph.isNotEmpty()) paragraph.append(' '); paragraph.append(line.trim()) }
            }
        }
        flush()
        return blocks
    }

    /** `**bold**`, `*italic*` and `` `code` `` in one line. A marker that never closes is plain text. */
    fun spans(line: String): List<Span> {
        val out = mutableListOf<Span>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out += Span(plain.toString()); plain.clear() } }
        var i = 0
        while (i < line.length) {
            val marker = when {
                line.startsWith("**", i) -> "**"
                line[i] == '`' -> "`"
                line[i] == '*' -> "*"
                else -> null
            }
            val close = marker?.let { line.indexOf(it, i + it.length) }
            if (marker != null && close != null && close > i + marker.length) {
                flush()
                val inner = line.substring(i + marker.length, close)
                out += when (marker) {
                    "**" -> Span(inner, bold = true)
                    "*" -> Span(inner, italic = true)
                    else -> Span(inner, code = true)
                }
                i = close + marker.length
            } else {
                plain.append(line[i]); i++
            }
        }
        flush()
        return out
    }
}
