// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.model

import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.core.split.Allocator

/**
 * What each kind of transaction requires. The same rules as
 * fulla.validate_transaction in the database, checked on the phone first so a
 * row the server would refuse is never queued.
 *
 *   expense     category (expense or both), payer; a split when the household
 *               has two or more active members
 *   income      category (income or both)
 *   refund      category (expense or both), who got the money back; split as expense
 *   transfer    two different accounts
 *   settlement  two different members
 *
 * Returns problems in plain English, or an empty list.
 */
object TransactionValidator {

    fun problems(t: Transaction, config: Config): List<String> {
        val out = mutableListOf<String>()
        val k = t.kind
        if (t.amountMinor <= 0) out += "The amount must be greater than zero."

        if (k.isCategorised) {
            val category = config.category(t.categoryId)
            when {
                t.categoryId == null -> out += "A ${k.key} needs a category."
                category == null -> out += "That category does not exist in this household."
                !category.appliesTo.allows(k) -> out += "That category is not for a ${k.key}."
            }
        } else if (t.categoryId != null) {
            out += "A ${k.key} has no category."
        }

        if (k == TransactionKind.EXPENSE || k == TransactionKind.REFUND || k == TransactionKind.SETTLEMENT) {
            if (t.paidByMemberId == null) out += "A ${k.key} needs a member who paid."
        }
        if (k == TransactionKind.TRANSFER) {
            if (t.accountId == null || t.toAccountId == null) out += "A transfer needs two accounts."
            else if (t.accountId == t.toAccountId) out += "A transfer needs two different accounts."
        } else if (t.toAccountId != null) {
            out += "Only a transfer has a destination account."
        }
        if (k == TransactionKind.SETTLEMENT) {
            if (t.toMemberId == null) out += "A settlement needs the member who is paid."
            else if (t.toMemberId == t.paidByMemberId) out += "A settlement needs two different members."
        } else if (t.toMemberId != null) {
            out += "Only a settlement has a receiving member."
        }

        val memberIds = config.members.map { it.id }.toSet()
        listOfNotNull(t.paidByMemberId, t.toMemberId).filter { it !in memberIds }
            .forEach { _ -> out += "That member does not exist in this household." }
        val accountIds = config.accounts.map { it.id }.toSet()
        listOfNotNull(t.accountId, t.toAccountId).filter { it !in accountIds }
            .forEach { _ -> out += "That account does not exist in this household." }

        if (k == TransactionKind.EXPENSE || k == TransactionKind.REFUND) {
            val split = t.split
            if (split == null) {
                if (config.activeMembers.size >= 2) out += "A ${k.key} in a household of several people needs a split."
            } else {
                out += Allocator.problems(split, t.amountMinor, memberIds)
            }
        } else if (t.split != null) {
            out += "A ${k.key} is not split."
        }

        if (t.note.length > 500) out += "A note can be at most 500 characters."
        if (t.tags.any { it.trim().length !in 1..30 }) out += "Each tag must be 1 to 30 characters."
        if (t.tags.size > 20) out += "At most 20 tags per transaction."
        if ((t.recurringRuleId == null) != (t.occurrenceDate == null)) {
            out += "A recurring occurrence needs both its rule and its date."
        }
        if ((t.originalAmountMinor == null) != (t.originalCurrency == null)) {
            out += "An original amount needs its currency."
        } else if (t.originalCurrency != null && (Currency.of(t.originalCurrency) == null || t.originalAmountMinor!! <= 0)) {
            out += "The original amount needs a positive amount and a known currency."
        }
        return out
    }
}
