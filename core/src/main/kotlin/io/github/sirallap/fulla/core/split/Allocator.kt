// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.split

import io.github.sirallap.fulla.core.model.Split
import java.math.BigInteger

/**
 * Who owes what of an amount. The parts always add up to the amount exactly.
 *
 * Equal and share splits use the largest remainder method: everyone gets the
 * floor of their exact share, and the units left over go one each to the
 * largest fractional remainders, ties broken by member id in byte order (member ids
 * are lower-case UUIDs, so String order is byte order, as in the database's
 * `collate "C"`). The
 * result does not depend on the order members were listed in, and no part is
 * more than one unit from its exact share.
 *
 * The same rule is fulla.split_shares in the database. Both pass
 * testdata/vectors/allocate.json, so the phone and the server agree to the unit.
 */
object Allocator {

    fun allocate(amountMinor: Long, split: Split): Map<String, Long> = when (split) {
        is Split.Exact -> split.amounts
        is Split.Equal -> byWeights(amountMinor, split.members.associateWith { 1L })
        is Split.Shares -> byWeights(amountMinor, split.shares.mapValues { it.value.toLong() })
    }

    fun byWeights(amountMinor: Long, weights: Map<String, Long>): Map<String, Long> {
        require(weights.isNotEmpty()) { "A split needs at least one member" }
        require(weights.values.all { it > 0 }) { "Weights must be positive" }
        val amount = BigInteger.valueOf(amountMinor)
        val total = BigInteger.valueOf(weights.values.sum())
        data class Part(val id: String, val base: Long, val remainder: BigInteger)

        val parts = weights.map { (id, w) ->
            val product = amount.multiply(BigInteger.valueOf(w))
            val (q, r) = product.divideAndRemainder(total)
            Part(id, q.toLong(), r)
        }
        val leftover = amountMinor - parts.sumOf { it.base }
        val order = parts.sortedWith(compareByDescending<Part> { it.remainder }.thenBy { it.id })
        val bonus = order.take(leftover.toInt()).map { it.id }.toSet()
        return parts.associate { it.id to it.base + if (it.id in bonus) 1 else 0 }
    }

    /** Validation problems, in the terms the database uses, or empty. */
    fun problems(split: Split, amountMinor: Long, householdMembers: Set<String>): List<String> {
        val out = mutableListOf<String>()
        when (split) {
            is Split.Equal -> {
                if (split.members.isEmpty()) out += "An equal split needs at least one member."
                if (split.members.size != split.members.toSet().size) out += "A member appears twice in the split."
            }
            is Split.Shares -> {
                if (split.shares.isEmpty()) out += "A split by shares needs at least one member."
                if (split.shares.values.any { it !in 1..1000 }) out += "Shares must be whole numbers from 1 to 1000."
            }
            is Split.Exact -> {
                if (split.amounts.isEmpty()) out += "An exact split needs at least one member."
                if (split.amounts.values.any { it < 0 }) out += "Exact split amounts must be zero or more."
                if (split.amounts.values.sum() != amountMinor) out += "An exact split must add up to the amount."
            }
        }
        if (!householdMembers.containsAll(split.memberIds)) out += "Every member in a split must belong to the household."
        return out
    }
}
