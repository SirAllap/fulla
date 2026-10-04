// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.analytics

import io.github.sirallap.fulla.core.split.Allocator

/** Percentages that read as a whole: the labels of one chart add up to 100, not 99 or 101. */
object Percent {

    /**
     * Whole percentages of [parts], by the largest remainder method (the same rule that splits an amount between
     * people, so they always add up to exactly 100). A part that is zero or less is 0 %; with nothing positive,
     * every part is 0 %.
     */
    fun split(parts: List<Long>): List<Int> {
        val positive = parts.indices.filter { parts[it] > 0 }
        if (positive.isEmpty()) return parts.map { 0 }
        // Zero-padded positions as ids: ties go to the earlier part, whatever order the map is built in.
        val shares = Allocator.byWeights(100, positive.associate { it.toString().padStart(6, '0') to parts[it] })
        return parts.indices.map { i -> if (parts[i] > 0) shares.getValue(i.toString().padStart(6, '0')).toInt() else 0 }
    }
}
