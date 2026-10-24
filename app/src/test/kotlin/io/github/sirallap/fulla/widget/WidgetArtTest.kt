// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The widget's card: paper above, liquid below, as high as its figure says. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetArtTest {
    private val paper = 0xFFFAF8F3.toInt()
    private val surface = 0xFFE9B949.toInt()
    private val body = 0xFFC9971F.toInt()

    @Test
    fun `the liquid stands as high as its level, with paper above it`() {
        val half = WidgetArt.card(200, 100, 0f, 0.5f, paper, surface, body, 0f)
        assertEquals(200, half.width)
        assertEquals(paper, half.getPixel(100, 5))
        assertNotEquals(paper, half.getPixel(100, 95))
        // Full, the liquid reaches higher than half; empty, it stays near the bottom.
        val full = WidgetArt.card(200, 100, 0f, 1f, paper, surface, body, 0f)
        val empty = WidgetArt.card(200, 100, 0f, 0f, paper, surface, body, 0f)
        assertNotEquals(paper, full.getPixel(100, 30))
        assertEquals(paper, empty.getPixel(100, 60))
    }
}
