// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.widget

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The widget's card: glass above, liquid below, as high as its figure says, and never past a third of the card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetArtTest {
    private val paper = 0xFFFAF8F3.toInt()
    private val surface = 0xFFE9B949.toInt()
    private val body = 0xFFC9971F.toInt()

    /** The first row, from the top, in the middle of the card, where the picture turns from glass to liquid (gold has little blue). */
    private fun liquidTop(b: Bitmap): Int = (0 until b.height).first { Color.blue(b.getPixel(b.width / 2, it)) < 0xB0 }

    @Test
    fun `the liquid stands as high as its level, with glass above it and never past a third`() {
        val half = WidgetArt.card(200, 100, 0f, 0.5f, paper, surface, body, 0f)
        val full = WidgetArt.card(200, 100, 0f, 1f, paper, surface, body, 0f)
        val empty = WidgetArt.card(200, 100, 0f, 0f, paper, surface, body, 0f)
        assertEquals(200, half.width)
        assertTrue(liquidTop(full) < liquidTop(half))
        assertTrue(liquidTop(half) < liquidTop(empty))
        assertTrue("the liquid never rises past a third of the card", liquidTop(full) >= 60)
    }

    @Test
    fun `outside its shape the card is transparent`() {
        val bubble = WidgetArt.card(100, 100, 0f, 0.5f, paper, surface, body, 0f, WidgetArt.Shape.BUBBLE)
        assertEquals(0, Color.alpha(bubble.getPixel(1, 1)))
        assertTrue(Color.alpha(bubble.getPixel(50, 50)) > 200)
    }
}
