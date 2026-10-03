// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.version.ReleaseNotes
import io.github.sirallap.fulla.core.version.ReleaseNotes.Block
import io.github.sirallap.fulla.core.version.ReleaseNotes.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseNotesTest {
    private val notes = """
        Intro for everyone.

        ## Español

        Un resumen.

        - **Negrita.** Y un texto normal.
        - Otro punto.

        ## English

        A summary.

        - **Bold.** And plain text.
    """.trimIndent()

    @Test
    fun the_phone_shows_the_notes_in_its_own_language_English_when_there_are_none_for_it() {
        val es = ReleaseNotes.forLanguage(notes, "es")
        assertTrue("Un resumen." in es && "A summary." !in es && es.startsWith("Intro for everyone."))
        val en = ReleaseNotes.forLanguage(notes, "en")
        assertTrue("A summary." in en && "Un resumen." !in en)
        assertTrue("A summary." in ReleaseNotes.forLanguage(notes, "de"), "German falls back to English")
        assertTrue("## " !in es, "the language heading itself is not shown")
    }

    @Test
    fun notes_that_are_not_divided_by_language_come_back_as_they_are() {
        val single = "- **One.** Two.\n- Three."
        assertEquals(single, ReleaseNotes.forLanguage(single, "es"))
        val onlyOne = "## English\n\nJust this."
        assertEquals(onlyOne, ReleaseNotes.forLanguage(onlyOne, "es"), "one section is not a choice")
        val other = "## Details\n\nx\n\n## More\n\ny"
        assertEquals(other, ReleaseNotes.forLanguage(other, "es"), "headings that are not languages do not divide")
    }

    @Test
    fun bullets_paragraphs_and_headings_become_blocks_with_their_styled_runs() {
        val blocks = ReleaseNotes.parse(ReleaseNotes.forLanguage(notes, "es"))
        assertTrue(blocks[0] is Block.Paragraph && blocks[1] is Block.Paragraph)
        val bullet = blocks[2] as Block.Bullet
        assertEquals(listOf(Span("Negrita.", bold = true), Span(" Y un texto normal.")), bullet.spans)
        assertTrue(blocks[3] is Block.Bullet)
        assertEquals(4, blocks.size)
        val heading = ReleaseNotes.parse("## Title\ntext on\ntwo lines").let { it[0] as Block.Heading to it[1] as Block.Paragraph }
        assertEquals(listOf(Span("Title")), heading.first.spans)
        assertEquals(listOf(Span("text on two lines")), heading.second.spans)
    }

    @Test
    fun bold_italic_and_code_and_a_marker_that_never_closes_is_plain_text() {
        assertEquals(listOf(Span("a "), Span("b", bold = true), Span(" c "), Span("d", italic = true), Span(" "), Span("e", code = true)),
            ReleaseNotes.spans("a **b** c *d* `e`"))
        assertEquals(listOf(Span("2 * 3 = 6")), ReleaseNotes.spans("2 * 3 = 6"))
        assertEquals(listOf(Span("**open")), ReleaseNotes.spans("**open"))
        assertEquals(listOf(Span("Left to spend", bold = true), Span(" and "), Span("per day", bold = true)),
            ReleaseNotes.spans("**Left to spend** and **per day**"))
    }

    @Test
    fun a_line_indented_under_a_bullet_continues_it() {
        val blocks = ReleaseNotes.parse("- First part\n  second part\n- Next")
        assertEquals(2, blocks.size)
        assertEquals("First part second part", (blocks[0] as Block.Bullet).spans.joinToString("") { it.text })
    }
}
