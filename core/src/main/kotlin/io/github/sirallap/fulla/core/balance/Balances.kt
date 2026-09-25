// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.balance

import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.split.Allocator

/** What a member has paid, what their share came to, and the difference. */
data class MemberBalance(val memberId: String, val paidMinor: Long, val shareMinor: Long) {
    /** Positive: the household owes them. Negative: they owe the household. */
    val balanceMinor: Long get() = paidMinor - shareMinor
}

/**
 * Balances between members.
 *
 *   expense A paid by P, split s     paid(P) += A    share(i) += s_i
 *   refund  A to P, split s          paid(P) -= A    share(i) -= s_i
 *   settlement A from X to Y         paid(X) += A    paid(Y) -= A
 *   income, transfer                 no effect
 *
 * An expense without a split (a household of one) is all the payer's share.
 * Balances in a household always add up to zero.
 *
 * The same rule is the fulla.member_balances view. Both pass
 * testdata/vectors/balance.json.
 */
object Balances {

    fun of(transactions: Iterable<Transaction>, memberIds: Iterable<String>): List<MemberBalance> {
        val paid = HashMap<String, Long>()
        val share = HashMap<String, Long>()
        fun add(map: HashMap<String, Long>, id: String?, amount: Long) {
            if (id != null) map[id] = (map[id] ?: 0L) + amount
        }
        for (t in transactions) {
            if (!t.isActive) continue
            when (t.kind) {
                TransactionKind.EXPENSE, TransactionKind.REFUND -> {
                    val sign = if (t.kind == TransactionKind.REFUND) -1 else 1
                    add(paid, t.paidByMemberId, sign * t.amountMinor)
                    val parts = t.split?.let { Allocator.allocate(t.amountMinor, it) }
                        ?: mapOf((t.paidByMemberId ?: continue) to t.amountMinor)
                    for ((id, part) in parts) add(share, id, sign * part)
                }
                TransactionKind.SETTLEMENT -> {
                    add(paid, t.paidByMemberId, t.amountMinor)
                    add(paid, t.toMemberId, -t.amountMinor)
                }
                TransactionKind.INCOME, TransactionKind.TRANSFER -> Unit
            }
        }
        val ids = (memberIds + paid.keys + share.keys).distinct()
        return ids.map { MemberBalance(it, paid[it] ?: 0, share[it] ?: 0) }
    }
}

/** One payment of a settlement plan: [fromMemberId] pays [toMemberId]. */
data class Payment(val fromMemberId: String, val toMemberId: String, val amountMinor: Long)

/**
 * The fewest payments that bring every balance to zero, give or take: largest
 * debtor pays largest creditor the smaller of the two amounts, repeatedly.
 * At most N-1 payments for N members with a non-zero balance. Deterministic:
 * ties go by member id.
 */
object SettlementPlanner {

    fun plan(balances: List<MemberBalance>): List<Payment> {
        require(balances.sumOf { it.balanceMinor } == 0L) { "Balances must add up to zero" }
        val creditors = balances.filter { it.balanceMinor > 0 }
            .map { it.memberId to it.balanceMinor }.toMutableList()
        val debtors = balances.filter { it.balanceMinor < 0 }
            .map { it.memberId to -it.balanceMinor }.toMutableList()
        val order = compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }
        val payments = mutableListOf<Payment>()
        while (creditors.isNotEmpty() && debtors.isNotEmpty()) {
            creditors.sortWith(order)
            debtors.sortWith(order)
            val (creditor, owed) = creditors[0]
            val (debtor, owes) = debtors[0]
            val amount = minOf(owed, owes)
            payments += Payment(debtor, creditor, amount)
            if (owed == amount) creditors.removeAt(0) else creditors[0] = creditor to owed - amount
            if (owes == amount) debtors.removeAt(0) else debtors[0] = debtor to owes - amount
        }
        return payments
    }
}
