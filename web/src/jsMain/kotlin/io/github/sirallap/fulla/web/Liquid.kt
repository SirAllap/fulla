// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.core.design.Liquid
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.random.Random

/**
 * The jar's liquid, drawn as the Android app draws it: the same surfaces (core's Liquid), the same levels, the same
 * 420 ms in which they settle, then still. With reduced motion they are drawn at rest at once.
 */
internal object Liquids {
    const val LIQUID_MS = 420.0

    fun css(name: String): String = window.getComputedStyle(document.documentElement!!).getPropertyValue(name).trim()
    fun reduced(): Boolean = window.matchMedia("(prefers-reduced-motion: reduce)").matches
    fun dark(): Boolean = when (Theme.mode) { "dark" -> true; "light" -> false; else -> window.matchMedia("(prefers-color-scheme: dark)").matches }

    /**
     * A canvas that fills [host] (which must be positioned) and draws [draw] once it has a size, over [duration] ms the
     * first time and again whenever the size changes. [draw] gets the canvas context, its width and height in CSS
     * pixels, the progress 0..1 and the milliseconds since the start.
     */
    fun paint(host: HTMLElement, duration: Double = LIQUID_MS, draw: (ctx: dynamic, w: Double, h: Double, t: Double, ms: Double) -> Unit) {
        val canvas = document.createElement("canvas") as org.w3c.dom.HTMLCanvasElement
        canvas.className = "liquid-canvas"
        canvas.setAttribute("aria-hidden", "true")
        host.insertBefore(canvas, host.firstChild)
        var started = false
        var last = 0.0
        var progress = 0.0
        fun frame(t: Double, ms: Double) {
            val w = host.clientWidth.toDouble(); val h = host.clientHeight.toDouble()
            if (w <= 0 || h <= 0) return
            val dpr = window.devicePixelRatio
            canvas.width = (w * dpr).toInt(); canvas.height = (h * dpr).toInt()
            val ctx = canvas.getContext("2d").asDynamic()
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
            ctx.clearRect(0, 0, w, h)
            draw(ctx, w, h, t, ms)
            last = ms; progress = t
        }
        fun run() {
            started = true
            if (reduced()) { frame(1.0, duration); return }
            val begin = window.performance.now()
            lateinit var tick: (Double) -> Unit
            tick = { now ->
                val ms = (now - begin).coerceAtMost(duration)
                frame(ms / duration, ms)
                if (ms < duration) window.requestAnimationFrame(tick)
            }
            window.requestAnimationFrame(tick)
        }
        val onSize: () -> Unit = { if (!started) { if (host.clientWidth > 0 && host.clientHeight > 0) run() } else frame(progress, last) }
        val rz = js("(function (cb) { return new ResizeObserver(function () { cb(); }); })")(onSize)
        rz.observe(host)
    }

    /** A pane of liquid behind other content: [level] 0..1 of the height when [vertical], of the width otherwise. */
    fun sheet(host: HTMLElement, level: Double, tone: String, vertical: Boolean, phase: Double) {
        if (level <= 0.0) return
        paint(host) { ctx, w, h, t, _ ->
            val lv = (level * t).coerceIn(0.0, 1.0)
            if (lv <= 0.0) return@paint
            val surface = css("--$tone-surface"); val body = css("--$tone-body")
            val alpha = if (dark()) (if (vertical) 0.42 else 0.40) else (if (vertical) 0.30 else 0.26)
            val n = 32
            ctx.beginPath()
            if (vertical) {
                val top = h - h * lv
                val wave = Liquid.surface(n, (4.0 / h).toFloat(), phase.toFloat(), 1.2f)
                ctx.moveTo(0, h)
                for (i in 0 until n) ctx.lineTo(w * i / (n - 1), top + wave[i] * h)
                ctx.lineTo(w, h); ctx.closePath()
                val g = ctx.createLinearGradient(0, top, 0, h)
                g.addColorStop(0, withAlpha(surface, alpha)); g.addColorStop(1, withAlpha(body, alpha))
                ctx.fillStyle = g
            } else {
                val right = w * lv
                val wave = Liquid.surface(n, (5.0 / w).toFloat(), phase.toFloat(), 0.8f)
                ctx.moveTo(0, 0)
                for (i in 0 until n) ctx.lineTo(right + wave[i] * w, h * i / (n - 1))
                ctx.lineTo(0, h); ctx.closePath()
                val g = ctx.createLinearGradient(0, 0, maxOf(right, 1.0), 0)
                g.addColorStop(0, withAlpha(body, alpha)); g.addColorStop(1, withAlpha(surface, alpha))
                ctx.fillStyle = g
            }
            ctx.fill()
        }
    }

    fun withAlpha(hex: String, alpha: Double): String {
        val h = hex.removePrefix("#")
        if (h.length < 6) return hex
        return "rgba(${h.substring(0, 2).toInt(16)},${h.substring(2, 4).toInt(16)},${h.substring(4, 6).toInt(16)},$alpha)"
    }

    private class Bubble(val x: Double, val y: Double, val r: Double, val light: Boolean)

    private val bubbles: List<Bubble> by lazy { val rnd = Random(11); List(64) { Bubble(rnd.nextDouble(), rnd.nextDouble(), 2.0 + rnd.nextDouble() * 5.0, it % 2 == 0) } }

    /** The jar itself: income a light liquid at the top, spending a heavy one at the bottom, the empty middle the savings. */
    fun jar(host: HTMLElement, income: Double, expense: Double) {
        paint(host) { ctx, w, h, t, ms ->
            val side = 12.0; val top = 6.0; val bottom = h - 6.0; val inside = (bottom - top).coerceAtLeast(1.0)
            val rise = 1 - (1 - t) * (1 - t) * (1 - t)
            val slosh = Liquid.settle(t.toFloat()).toDouble()
            val amplitude = (3.0 + 12.0 * abs(slosh)) / inside
            val meniscus = 6.0 * rise / inside
            val phase = ms / 170.0
            val incomeLevel = top + inside * income.coerceIn(0.0, 1.0) * rise + 8.0 * slosh
            val expenseLevel = bottom - inside * expense.coerceIn(0.0, 1.0) * rise - 8.0 * slosh
            val samples = 96
            val usable = w - 2 * side
            val iw = Liquid.surface(samples, amplitude.toFloat(), (phase + 0.3).toFloat(), 1.4f, meniscus = (-meniscus).toFloat())
            val ew = Liquid.surface(samples, (amplitude * 0.85).toFloat(), (-phase + 1.9).toFloat(), 1.1f, meniscus = meniscus.toFloat())
            fun x(i: Int) = side + usable * i / (samples - 1)
            val mixed = expenseLevel < incomeLevel
            val c = object { val paperHigh = css("--paper-high"); val line = css("--line"); val inS = css("--in-surface"); val inB = css("--in-body"); val outS = css("--out-surface"); val outB = css("--out-body") }
            fun outline() { ctx.beginPath(); ctx.roundRect(side, top, w - 2 * side, bottom - top, 28) }
            ctx.save()
            outline(); ctx.clip()
            ctx.fillStyle = c.paperHigh; ctx.fillRect(side, top, w - 2 * side, bottom - top)
            // income, hanging from the lid
            ctx.beginPath(); ctx.moveTo(side, top); ctx.lineTo(w - side, top)
            for (i in samples - 1 downTo 0) ctx.lineTo(x(i), incomeLevel + iw[i] * inside)
            ctx.closePath()
            var g = ctx.createLinearGradient(0, top, 0, maxOf(incomeLevel, top + 1))
            g.addColorStop(0, c.inB); g.addColorStop(1, c.inS); ctx.fillStyle = g; ctx.fill()
            // spending, settled at the bottom
            ctx.beginPath(); ctx.moveTo(side, bottom)
            for (i in 0 until samples) ctx.lineTo(x(i), expenseLevel + ew[i] * inside)
            ctx.lineTo(w - side, bottom); ctx.closePath()
            g = ctx.createLinearGradient(0, minOf(expenseLevel, bottom - 1), 0, bottom)
            g.addColorStop(0, c.outS); g.addColorStop(1, c.outB); ctx.fillStyle = g; ctx.fill()
            if (mixed) {
                ctx.save()
                ctx.beginPath()
                for (i in 0 until samples) { val y = incomeLevel + iw[i] * inside; if (i == 0) ctx.moveTo(x(i), y) else ctx.lineTo(x(i), y) }
                for (i in samples - 1 downTo 0) ctx.lineTo(x(i), expenseLevel + ew[i] * inside)
                ctx.closePath(); ctx.clip()
                g = ctx.createLinearGradient(0, expenseLevel, 0, maxOf(incomeLevel, expenseLevel + 1))
                g.addColorStop(0, c.inS); g.addColorStop(1, c.outS); ctx.fillStyle = g; ctx.fillRect(0, 0, w, h)
                val depth = (incomeLevel - expenseLevel) + 24.0
                for (b in bubbles) {
                    ctx.fillStyle = withAlpha(if (b.light) c.inB else c.outB, 0.5)
                    ctx.beginPath(); ctx.arc(side + b.x * usable, expenseLevel - 12.0 + b.y * depth, b.r, 0, 6.283185307179586); ctx.fill()
                }
                ctx.restore()
            }
            // a light line along each surface is most of what makes a flat colour read as liquid
            ctx.lineWidth = 1.5
            ctx.strokeStyle = "rgba(255,255,255,0.35)"; ctx.beginPath()
            for (i in 0 until samples) { val y = incomeLevel + iw[i] * inside - 2; if (i == 0) ctx.moveTo(x(i), y) else ctx.lineTo(x(i), y) }
            ctx.stroke()
            ctx.strokeStyle = "rgba(255,255,255,0.3)"; ctx.beginPath()
            for (i in 0 until samples) { val y = expenseLevel + ew[i] * inside + 2; if (i == 0) ctx.moveTo(x(i), y) else ctx.lineTo(x(i), y) }
            ctx.stroke()
            ctx.restore()
            ctx.strokeStyle = c.line; ctx.lineWidth = 1.5; outline(); ctx.stroke()
        }
    }
}

/** The overview's one loud thing: the period as a jar holding two liquids, with the savings in it. */
object Jar {
    private const val HEIGHT = 300.0

    fun build(income: Double, expense: Double, figure: String, savingsMinor: Long, description: String): HTMLElement = el("div", "jar") {
        attr("role", "img"); attr("aria-label", description)
        style.height = "${HEIGHT}px"
        val inc = income.coerceIn(0.0, 1.0); val exp = expense.coerceIn(0.0, 1.0)
        val top = 6.0; val inside = HEIGHT - 12.0
        val incomeLevel = top + inside * inc
        val expenseLevel = top + inside - inside * exp
        val mixed = expenseLevel < incomeLevel
        val deficit = savingsMinor < 0
        val figureTop = if (deficit || mixed) top + 16.0 else ((incomeLevel + expenseLevel) / 2.0 - 30.0)
        Liquids.jar(this, inc, exp)
        div("jar-figure" + if (deficit || mixed) " on-liquid" else "") {
            style.top = "${figureTop.coerceAtLeast(0.0)}px"
            text(figure)
        }
    }
}
