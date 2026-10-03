// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.design

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The surface of a liquid on the home screen.
 *
 * Income is drawn as a light liquid floating at the top of a jar, spending as
 * a heavy one settled at the bottom, and the empty space between them is the
 * savings, to scale (see Hero). A wave that bulged more than it dipped would
 * quietly draw a different amount, so every surface returned here has a mean
 * of exactly zero over the polyline actually drawn (the trapezoid rule over
 * the samples), meniscus included: liquid that climbs the walls is taken back
 * out of the middle.
 *
 * Offsets are fractions of the jar's height, positive downwards.
 */
object Liquid {

    /**
     * @param samples points from wall to wall, at least 2.
     * @param amplitude peak of the wave, as a fraction of the height.
     * @param phase shifts the wave sideways, so two surfaces do not mirror each other.
     * @param waves how many waves fit across.
     * @param meniscus how far the liquid climbs each wall; positive pulls it up.
     */
    fun surface(
        samples: Int,
        amplitude: Float,
        phase: Float,
        waves: Float,
        meniscus: Float = 0f,
        meniscusWidth: Float = 0.035f,
    ): FloatArray {
        require(samples >= 2) { "A surface needs at least two points" }
        val y = FloatArray(samples)
        for (i in 0 until samples) {
            val x = i.toFloat() / (samples - 1)
            val wave = amplitude * sin(2 * PI * (waves * x + phase)).toFloat()
            val nearWall = exp(-x / meniscusWidth) + exp(-(1 - x) / meniscusWidth)
            y[i] = wave - meniscus * nearWall.toFloat()
        }
        val mean = trapezoidMean(y)
        for (i in y.indices) y[i] -= mean
        return y
    }

    /** The mean of a polyline with evenly spaced samples, by the trapezoid rule. */
    fun trapezoidMean(y: FloatArray): Float {
        if (y.size < 2) return y.firstOrNull() ?: 0f
        var sum = 0.0
        for (i in 0 until y.size - 1) sum += (y[i] + y[i + 1]) / 2.0
        return (sum / (y.size - 1)).toFloat()
    }

    /**
     * How the surface settles after the levels change: a damped oscillation
     * from 1 at t = 0 to rest at t = 1 (the 420 ms of the home screen's one
     * orchestrated moment).
     */
    fun settle(t: Float): Float {
        if (t >= 1f) return 0f
        return (exp(-4.0 * t) * kotlin.math.cos(3 * PI * t)).toFloat() * (1 - t)
    }
}
