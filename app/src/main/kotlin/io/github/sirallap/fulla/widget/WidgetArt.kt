// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import io.github.sirallap.fulla.core.design.Liquid
import java.util.Random

/**
 * The widget's card, drawn as a piece of glass with the jar's liquid in it.
 *
 * The shape is not a rectangle: a pebble (the corners on one diagonal much
 * rounder than the other) or, for the small one, a bubble. Outside the
 * shape the bitmap is transparent, so the home screen shows through. On the
 * glass: a fine grain, a diagonal sheen, a rim that catches the light and a
 * soft shadow inside the edge. In it, the liquid, with a surface that waves
 * the way the jar's does ([Liquid.surface], mean zero, so the wave never
 * draws more than the level says), a body that darkens towards the bottom
 * and a few bubbles. How high it stands is the widget's own figure
 * ([io.github.sirallap.fulla.core.widget.WidgetData.level]); it never rises
 * above a third of the card, so the words, which are views over this
 * picture, always have glass behind them. A home-screen widget cannot
 * animate, so this is the liquid at rest. Colours come from core's Colors.
 */
object WidgetArt {
    enum class Shape { PEBBLE, BUBBLE }

    /** The share of the card's height the liquid stands at: low, and never above a third. */
    private fun level(fill: Float?, quick: Boolean): Float =
        if (quick) (if (fill == null) 0.26f else 0.26f + 0.12f * fill.coerceIn(0f, 1f)) // above the row of shortcuts, so it shows around them
        else if (fill == null) 0.14f else 0.14f + 0.19f * fill.coerceIn(0f, 1f)

    fun card(
        width: Int, height: Int, radius: Float, fill: Float?, paper: Int, surface: Int, body: Int, stroke: Float,
        shape: Shape = Shape.PEBBLE, quick: Boolean = false,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val unit = if (stroke > 0f) stroke / 2f else maxOf(1f, minOf(w, h) / 90f)
        val outline = outline(shape, w, h, radius)
        val dark = ColorUtils.calculateLuminance(paper) < 0.4

        canvas.save()
        canvas.clipPath(outline)
        // The glass: paper, a little lighter at the top and deeper at the bottom.
        canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h,
                ColorUtils.blendARGB(paper, android.graphics.Color.WHITE, if (dark) 0.07f else 0.45f),
                ColorUtils.blendARGB(paper, android.graphics.Color.BLACK, if (dark) 0.25f else 0.06f), Shader.TileMode.CLAMP)
        })

        // The liquid: a fainter surface behind the first gives it depth, as two layers do in the jar.
        val top = h * (1f - level(fill, quick))
        wave(canvas, w, h, top - h * 0.03f, Liquid.surface(48, 0.03f, 0.62f, 1.1f), surface, body, alpha = 110)
        wave(canvas, w, h, top, Liquid.surface(48, 0.03f, 0.08f, 1.45f, meniscus = 0.01f), surface, body, alpha = 255)
        bubbles(canvas, w, h, top, unit)

        // The grain, the sheen, the rim and the shadow inside the edge.
        canvas.drawPath(outline, Paint().apply { shader = BitmapShader(grain(dark), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
        canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w * 0.8f, h * 0.9f,
                intArrayOf(ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, if (dark) 50 else 130), 0, 0),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = unit * 7
            color = ColorUtils.setAlphaComponent(android.graphics.Color.BLACK, if (dark) 70 else 22)
        })
        canvas.restore()
        canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = unit * 1.6f
            shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, if (dark) 140 else 230),
                    ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, 24),
                    ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, if (dark) 70 else 120)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
        return bitmap
    }

    private fun outline(shape: Shape, w: Float, h: Float, radius: Float): Path = Path().apply {
        when (shape) {
            Shape.BUBBLE -> addOval(RectF(0f, 0f, w, h), Path.Direction.CW)
            Shape.PEBBLE -> {
                // The corners on one diagonal are much rounder than on the other: a pebble, not a card.
                val big = minOf(radius * 2.4f, h / 2f)
                val small = radius * 0.9f
                addRoundRect(RectF(0f, 0f, w, h), floatArrayOf(big, big, small, small, big, big, small, small), Path.Direction.CW)
            }
        }
    }

    private fun wave(canvas: Canvas, w: Float, h: Float, level: Float, offsets: FloatArray, surface: Int, body: Int, alpha: Int) {
        val points = offsets.mapIndexed { i, dy -> (w * i / (offsets.size - 1)) to (level + dy * h) }
        val path = Path().apply {
            moveTo(0f, h)
            points.forEach { (x, y) -> lineTo(x, y) }
            lineTo(w, h)
            close()
        }
        // Light at the surface, the body's colour below, a darker bottom: depth, not a flat colour.
        val deep = ColorUtils.blendARGB(body, android.graphics.Color.BLACK, 0.25f)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, level, 0f, h, intArrayOf(surface, body, deep), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            this.alpha = alpha
        })
    }

    /** A few bubbles, always in the same places: the picture must not change between redraws of the same figure. */
    private fun bubbles(canvas: Canvas, w: Float, h: Float, top: Float, unit: Float) {
        val rnd = Random(11)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = unit * 0.6f; color = ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, 170) }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, 46) }
        val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, 230) }
        repeat(7) {
            val r = unit * (1.2f + rnd.nextFloat() * 2.2f)
            val x = w * (0.08f + rnd.nextFloat() * 0.84f)
            val y = top + (h - top) * (0.35f + rnd.nextFloat() * 0.55f)
            canvas.drawCircle(x, y, r, body)
            canvas.drawCircle(x, y, r, ring)
            canvas.drawCircle(x - r * 0.3f, y - r * 0.3f, r * 0.28f, glint)
        }
    }

    /** Fine grain: white specks on a dark card, dark ones on a light card, the same every time. */
    private fun grain(dark: Boolean): Bitmap {
        val size = 96
        val rnd = Random(7)
        val pixels = IntArray(size * size) {
            val a = rnd.nextInt(if (dark) 22 else 14)
            (a shl 24) or (if (dark) 0xFFFFFF else 0x000000)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
