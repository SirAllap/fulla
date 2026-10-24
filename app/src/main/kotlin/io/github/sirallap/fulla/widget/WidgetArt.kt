// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import io.github.sirallap.fulla.core.design.Liquid

/**
 * The widget's card drawn as the overview's jar: paper, and a liquid whose
 * surface waves the way the jar's does ([Liquid.surface], mean zero, so the
 * wave never draws more than the level says). How high it stands is the
 * widget's own figure ([io.github.sirallap.fulla.core.widget.WidgetData.level]);
 * without one it rests low and calm. A home-screen widget cannot animate, so
 * this is the jar at rest. Colours come from core's Colors, passed in.
 */
object WidgetArt {
    /** The level drawn: the liquid never fills the card, so the words above it always have paper or a light surface behind them. */
    private fun height(fill: Float?): Float = if (fill == null) 0.2f else 0.16f + 0.7f * fill.coerceIn(0f, 1f)

    fun card(width: Int, height: Int, radius: Float, fill: Float?, paper: Int, surface: Int, body: Int, stroke: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawColor(paper)

        val level = h * (1f - height(fill))
        // A second, fainter surface behind the first gives the liquid depth, as two layers do in the jar.
        wave(canvas, w, h, level - h * 0.035f, Liquid.surface(48, 0.03f, 0.62f, 1.1f), surface, body, alpha = 110, stroke = 0f)
        wave(canvas, w, h, level, Liquid.surface(48, 0.035f, 0.08f, 1.45f, meniscus = 0.012f), surface, body, alpha = 255, stroke = stroke)
        return bitmap
    }

    private fun wave(canvas: Canvas, w: Float, h: Float, level: Float, offsets: FloatArray, surface: Int, body: Int, alpha: Int, stroke: Float) {
        val points = offsets.mapIndexed { i, dy -> (w * i / (offsets.size - 1)) to (level + dy * h) }
        val path = Path().apply {
            moveTo(0f, h)
            points.forEach { (x, y) -> lineTo(x, y) }
            lineTo(w, h)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, level, 0f, h, surface, body, Shader.TileMode.CLAMP)
            this.alpha = alpha
        }
        canvas.drawPath(path, fill)
        if (stroke > 0f) {
            // A light line along the surface is most of what makes a flat colour read as liquid.
            val line = Path().apply { points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y - stroke) else lineTo(x, y - stroke) } }
            canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; color = android.graphics.Color.WHITE; this.alpha = 95
            })
        }
    }
}
