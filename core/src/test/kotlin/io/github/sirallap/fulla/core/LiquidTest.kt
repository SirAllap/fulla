// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.design.Liquid
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiquidTest {
    @Test
    fun `a wave never changes how much liquid there is`() {
        val random = Random(3)
        repeat(500) {
            val y = Liquid.surface(
                samples = 2 + random.nextInt(200), amplitude = random.nextFloat() * 0.05f,
                phase = random.nextFloat(), waves = 0.5f + random.nextFloat() * 3, meniscus = random.nextFloat() * 0.04f - 0.02f,
            )
            assertTrue(abs(Liquid.trapezoidMean(y)) < 1e-5f)
        }
    }

    @Test
    fun `settling starts moving and ends still`() {
        assertEquals(1f, Liquid.settle(0f), 1e-6f)
        assertEquals(0f, Liquid.settle(1f))
        assertTrue(abs(Liquid.settle(0.9f)) < 0.05f)
    }
}
