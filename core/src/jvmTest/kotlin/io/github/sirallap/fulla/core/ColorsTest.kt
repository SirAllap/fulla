// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.design.Accent
import io.github.sirallap.fulla.core.design.BaseColors
import io.github.sirallap.fulla.core.design.Contrast
import io.github.sirallap.fulla.core.design.IdentityColors
import io.github.sirallap.fulla.core.design.MoneyPalette
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every colour that carries text reaches 4.5:1 on both surfaces of its theme. */
class ColorsTest {
    private fun check(name: String, color: Long, dark: Boolean) {
        val t = BaseColors.of(dark)
        for (ground in listOf(t.paper, t.paperHigh)) {
            val r = Contrast.ratio(color, ground)
            assertTrue(r >= Contrast.TEXT_MINIMUM, "$name on ${if (dark) "dark" else "light"}: %.2f".format(r))
        }
    }

    @Test
    fun `base text colours`() {
        for (dark in listOf(false, true)) {
            val t = BaseColors.of(dark)
            check("ink", t.ink, dark)
            check("inkMuted", t.inkMuted, dark)
            check("accent", t.accent, dark)
            check("warning", t.warning, dark)
            check("danger", t.danger, dark)
            assertTrue(Contrast.ratio(t.onAccent, t.accent) >= Contrast.TEXT_MINIMUM, "onAccent")
            assertTrue(Contrast.ratio(t.onHighlight, t.highlight) >= Contrast.TEXT_MINIMUM, "onHighlight")
        }
    }

    @Test
    fun `money text in every palette`() {
        for (p in MoneyPalette.entries) for (dark in listOf(false, true)) {
            check("${p.name}.in", p.of(dark).inText, dark)
            check("${p.name}.out", p.of(dark).outText, dark)
        }
    }

    @Test
    fun `member and category colours`() {
        for (dark in listOf(false, true)) {
            (0 until 10).forEach { check("member $it", IdentityColors.member(it, dark), dark) }
            (0 until 12).forEach { check("category $it", IdentityColors.category(it, dark), dark) }
        }
    }

    @Test
    fun `every accent carries ink, and reads as text in the dark`() {
        for (a in Accent.entries) {
            assertTrue(Contrast.ratio(a.onFill, a.fill) >= Contrast.TEXT_MINIMUM, "${a.name} under its text")
            val dark = BaseColors.of(true, a)
            for (ground in listOf(dark.paper, dark.paperHigh)) {
                assertTrue(Contrast.ratio(dark.accent, ground) >= Contrast.TEXT_MINIMUM, "${a.name} as text in the dark")
            }
            assertTrue(Contrast.ratio(BaseColors.of(false, a).accent, BaseColors.LIGHT.paper) >= Contrast.TEXT_MINIMUM)
        }
    }
}
