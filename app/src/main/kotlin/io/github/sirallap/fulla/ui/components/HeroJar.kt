// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.core.analytics.Hero
import io.github.sirallap.fulla.core.design.Liquid
import io.github.sirallap.fulla.ui.theme.FullaColors
import io.github.sirallap.fulla.ui.theme.FullaMotion
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import kotlin.random.Random

/**
 * The home screen's one loud thing: the period as a jar holding two liquids.
 *
 * Income is light and floats at the top; spending is heavy and settles at the
 * bottom. What is left between them is the savings, drawn to scale: the
 * levels are [Hero]'s fractions of a field twice the income. When spending
 * passes income the liquids are pressed together and mix, and the mixed band
 * is the deficit.
 *
 * The waves never change how much liquid there is: each surface comes from
 * [Liquid.surface], whose mean is exactly zero. The liquids settle over
 * FullaMotion.LIQUID_MS and then stay still; with animations off they are
 * drawn at rest at once.
 *
 * @param figure the savings as text, already formatted in the household's currency.
 * @param countUp the savings at a fraction of the way there, for the 300 ms count-up.
 */
@Composable
fun HeroJar(
    hero: Hero,
    figure: String,
    countUp: (Float) -> String,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 300.dp,
) {
    val c = FullaTheme.colors
    val reduced = FullaMotion.reduced()
    val clock = remember { Animatable(0f) }
    val total = FullaMotion.LIQUID_MS.toFloat()

    LaunchedEffect(hero.incomeFraction, hero.expenseFraction, reduced) {
        if (reduced) clock.snapTo(total) else {
            clock.snapTo(0f)
            clock.animateTo(total, tween(FullaMotion.LIQUID_MS, easing = LinearEasing))
        }
    }
    val ms = clock.value
    val bubbles = remember { emulsion() }

    BoxWithConstraints(
        modifier.fillMaxWidth().height(height)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        val density = LocalDensity.current
        val jar = with(density) {
            JarFrame.at(
                width = constraints.maxWidth.toFloat(), height = constraints.maxHeight.toFloat(),
                income = hero.incomeFraction.toFloat(), expense = hero.expenseFraction.toFloat(),
                t = (ms / total).coerceIn(0f, 1f), ms = ms, px = { it.toPx() },
            )
        }
        Canvas(Modifier.fillMaxSize()) { drawJar(jar, c, bubbles) }

        val deficit = hero.savingsMinor < 0
        val top: Dp = with(density) {
            if (deficit || jar.mixed) (jar.top + 16.dp.toPx()).toDp()
            else ((jar.incomeLevel + jar.expenseLevel) / 2f).toDp() - 30.dp
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.TopCenter) {
            val shown = if (reduced || ms >= FullaMotion.FIGURE_MS) figure else countUp(ms / FullaMotion.FIGURE_MS)
            Text(
                shown,
                style = FullaType.heroFigure,
                // Over the income liquid the figure is drawn in paper, which
                // inverts with the liquid in both themes.
                color = if (deficit || jar.mixed) c.paper else c.ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(top = top.coerceAtLeast(0.dp)),
            )
        }
    }
}

/** Everything's position, in pixels, for one frame. */
private class JarFrame(
    val side: Float,
    val top: Float,
    val bottom: Float,
    val radius: Float,
    val width: Float,
    val incomeLevel: Float,
    val expenseLevel: Float,
    val incomeSurface: List<Offset>,
    val expenseSurface: List<Offset>,
    val stroke: Float,
) {
    val mixed: Boolean get() = expenseLevel < incomeLevel

    companion object {
        private const val SAMPLES = 96

        fun at(width: Float, height: Float, income: Float, expense: Float, t: Float, ms: Float, px: (Dp) -> Float): JarFrame {
            val side = px(12.dp)
            val top = px(6.dp)
            val bottom = height - px(6.dp)
            val inside = (bottom - top).coerceAtLeast(1f)
            // The levels rise with an ease-out; the surface sloshes and comes to rest.
            val rise = 1f - (1f - t) * (1f - t) * (1f - t)
            val slosh = Liquid.settle(t)
            val amplitude = (px(3.dp) + px(12.dp) * kotlin.math.abs(slosh)) / inside
            val meniscus = px(6.dp) * rise / inside
            val phase = ms / 170f
            val incomeLevel = top + inside * income.coerceIn(0f, 1f) * rise + px(8.dp) * slosh
            val expenseLevel = bottom - inside * expense.coerceIn(0f, 1f) * rise - px(8.dp) * slosh
            val usable = width - 2 * side
            // Income hangs from the lid, so its meniscus pulls down at the walls; spending climbs them.
            val incomeWave = Liquid.surface(SAMPLES, amplitude, phase + 0.3f, 1.4f, meniscus = -meniscus)
            val expenseWave = Liquid.surface(SAMPLES, amplitude * 0.85f, -phase + 1.9f, 1.1f, meniscus = meniscus)
            fun x(i: Int) = side + usable * i / (SAMPLES - 1)
            return JarFrame(
                side, top, bottom, px(28.dp), width, incomeLevel, expenseLevel,
                List(SAMPLES) { Offset(x(it), incomeLevel + incomeWave[it] * inside) },
                List(SAMPLES) { Offset(x(it), expenseLevel + expenseWave[it] * inside) },
                px(1.5.dp),
            )
        }
    }
}

private class Bubble(val x: Float, val y: Float, val r: Float, val light: Boolean)

/** Seeded, so the same deficit always looks the same. */
private fun emulsion(): List<Bubble> {
    val rnd = Random(11)
    return List(64) { Bubble(rnd.nextFloat(), rnd.nextFloat(), 2f + rnd.nextFloat() * 5f, it % 2 == 0) }
}

private fun DrawScope.drawJar(j: JarFrame, c: FullaColors, bubbles: List<Bubble>) {
    val outline = Path().apply { addRoundRect(RoundRect(j.side, j.top, j.width - j.side, j.bottom, CornerRadius(j.radius, j.radius))) }
    clipPath(outline) {
        drawRect(c.paperHigh, Offset(j.side, j.top), Size(j.width - 2 * j.side, j.bottom - j.top))

        val income = Path().apply {
            moveTo(j.side, j.top)
            lineTo(j.width - j.side, j.top)
            for (p in j.incomeSurface.asReversed()) lineTo(p.x, p.y)
            close()
        }
        drawPath(income, Brush.verticalGradient(listOf(c.inBody, c.inSurface), startY = j.top, endY = maxOf(j.incomeLevel, j.top + 1f)))

        val expense = Path().apply {
            moveTo(j.side, j.bottom)
            for (p in j.expenseSurface) lineTo(p.x, p.y)
            lineTo(j.width - j.side, j.bottom)
            close()
        }
        drawPath(expense, Brush.verticalGradient(listOf(c.outSurface, c.outBody), startY = minOf(j.expenseLevel, j.bottom - 1f), endY = j.bottom))

        if (j.mixed) {
            val band = Path().apply {
                j.incomeSurface.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                for (p in j.expenseSurface.asReversed()) lineTo(p.x, p.y)
                close()
            }
            clipPath(band) {
                drawRect(Brush.verticalGradient(listOf(c.inSurface, c.outSurface), startY = j.expenseLevel,
                    endY = maxOf(j.incomeLevel, j.expenseLevel + 1f)))
                val depth = (j.incomeLevel - j.expenseLevel) + 24f
                for (b in bubbles) {
                    drawCircle((if (b.light) c.inBody else c.outBody).copy(alpha = 0.5f), b.r * density,
                        Offset(j.side + b.x * (j.width - 2 * j.side), j.expenseLevel - 12f + b.y * depth))
                }
            }
        }

        // A light line along each surface is most of what makes a flat colour read as liquid.
        fun highlight(points: List<Offset>, dy: Float, alpha: Float) = drawPath(
            Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y + dy) else lineTo(p.x, p.y + dy) } },
            Color.White.copy(alpha = alpha), style = Stroke(j.stroke),
        )
        highlight(j.incomeSurface, -2f, 0.35f)
        highlight(j.expenseSurface, 2f, 0.3f)
    }
    drawRoundRect(c.line, Offset(j.side, j.top), Size(j.width - 2 * j.side, j.bottom - j.top), CornerRadius(j.radius, j.radius), style = Stroke(j.stroke))
}
