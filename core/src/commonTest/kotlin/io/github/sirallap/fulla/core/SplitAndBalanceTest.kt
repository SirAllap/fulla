// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.balance.Balances
import io.github.sirallap.fulla.core.balance.MemberBalance
import io.github.sirallap.fulla.core.balance.SettlementPlanner
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.split.Allocator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitAndBalanceTest {
    private val ids = (1..10).map { "00000000-0000-4000-8000-" + it.toString().padStart(12, '0') }

    @Test
    fun allocation_always_adds_up_is_within_one_unit_of_exact_and_ignores_listing_order() {
        val random = Random(7)
        repeat(1000) {
            val n = 1 + random.nextInt(10)
            val members = ids.shuffled(random).take(n)
            val weights = members.associateWith { 1L + random.nextInt(5) }
            val amount = random.nextLong(1, 10_000_000)
            val parts = Allocator.byWeights(amount, weights)
            assertEquals(amount, parts.values.sum())
            val total = weights.values.sum()
            for ((id, part) in parts) {
                // |part - amount * weight / total| < 1, without a fraction in sight.
                val diff = part * total - amount * weights.getValue(id)
                assertTrue(kotlin.math.abs(diff) < total, "$part vs ${amount * weights.getValue(id)} / $total")
            }
            assertEquals(parts, Allocator.byWeights(amount, weights.entries.reversed().associate { it.key to it.value }))
        }
    }

    @Test
    fun split_problems_are_the_database_s() {
        val house = ids.take(3).toSet()
        assertTrue(Allocator.problems(Split.Exact(mapOf(ids[0] to 600L, ids[1] to 399L)), 1000, house).isNotEmpty())
        assertTrue(Allocator.problems(Split.Exact(mapOf(ids[0] to 600L, ids[1] to 400L)), 1000, house).isEmpty())
        assertTrue(Allocator.problems(Split.Equal(listOf(ids[0], ids[0])), 1000, house).isNotEmpty())
        assertTrue(Allocator.problems(Split.Equal(listOf(ids[9])), 1000, house).isNotEmpty())
        assertTrue(Allocator.problems(Split.Shares(mapOf(ids[0] to 0)), 1000, house).isNotEmpty())
    }

    @Test
    fun balances_always_add_up_to_zero() {
        val random = Random(11)
        repeat(200) {
            val members = ids.take(2 + random.nextInt(8))
            val txs = List(30) {
                Fixtures.expense(
                    amount = random.nextLong(1, 100_000), payer = members.random(random),
                    split = Split.Shares(members.filter { random.nextBoolean() }.ifEmpty { members.take(1) }.associateWith { 1 + random.nextInt(3) }),
                )
            }
            assertEquals(0L, Balances.of(txs, members).sumOf { it.balanceMinor })
        }
    }

    @Test
    fun a_settlement_plan_settles_everyone_in_at_most_n_1_payments() {
        val random = Random(13)
        repeat(500) {
            val n = 2 + random.nextInt(9)
            val raw = List(n - 1) { random.nextLong(-50_000, 50_000) }
            val balances = ids.take(n).mapIndexed { i, id ->
                val b = if (i < n - 1) raw[i] else -raw.sum()
                MemberBalance(id, maxOf(b, 0), maxOf(-b, 0))
            }
            val plan = SettlementPlanner.plan(balances)
            assertTrue(plan.size <= n - 1)
            val after = balances.associate { it.memberId to it.balanceMinor }.toMutableMap()
            plan.forEach { after[it.fromMemberId] = after.getValue(it.fromMemberId) + it.amountMinor; after[it.toMemberId] = after.getValue(it.toMemberId) - it.amountMinor }
            assertTrue(after.values.all { it == 0L }, "$after")
            assertEquals(plan, SettlementPlanner.plan(balances.shuffled(random)), "deterministic")
        }
    }
}
