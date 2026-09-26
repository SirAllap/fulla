// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.model

import io.github.sirallap.fulla.core.trips.Trip
import java.time.LocalDate

/**
 * The household and everything in it, as the app holds them. Field names and
 * values match the database and the wire (docs/data-model.md), so mapping is
 * mechanical and a name means the same thing everywhere.
 */

enum class TransactionKind(val key: String) {
    EXPENSE("expense"), INCOME("income"), REFUND("refund"), TRANSFER("transfer"), SETTLEMENT("settlement");

    /** Counts towards income or spending. Transfers and settlements only move money around. */
    val countsInTotals: Boolean get() = this == EXPENSE || this == INCOME || this == REFUND

    /** Has a category, and may be split. */
    val isCategorised: Boolean get() = this == EXPENSE || this == INCOME || this == REFUND

    companion object {
        fun of(key: String): TransactionKind? = entries.firstOrNull { it.key == key }
    }
}

enum class Recurrence(val key: String) {
    FIXED("fixed"), VARIABLE("variable");

    companion object {
        fun of(key: String?): Recurrence = entries.firstOrNull { it.key == key } ?: VARIABLE
    }
}

enum class Status(val key: String) {
    ACTIVE("active"), DELETED("deleted");

    companion object {
        fun of(key: String?): Status = if (key == DELETED.key) DELETED else ACTIVE
    }
}

enum class Role(val key: String, val rank: Int) {
    MEMBER("member", 1), ADMIN("admin", 2), OWNER("owner", 3);

    fun atLeast(other: Role): Boolean = rank >= other.rank

    companion object {
        fun of(key: String?): Role = entries.firstOrNull { it.key == key } ?: MEMBER
    }
}

enum class MemberStatus(val key: String) {
    ACTIVE("active"), ARCHIVED("archived"), REMOVED("removed");

    companion object {
        fun of(key: String?): MemberStatus = entries.firstOrNull { it.key == key } ?: ACTIVE
    }
}

enum class AccountType(val key: String) {
    CASH("cash"), CHECKING("checking"), SAVINGS("savings"), CREDIT_CARD("credit_card"), OTHER("other");

    companion object {
        fun of(key: String?): AccountType = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** Which kinds of transaction a category can be used for. */
enum class AppliesTo(val key: String) {
    EXPENSE("expense"), INCOME("income"), BOTH("both");

    fun allows(kind: TransactionKind): Boolean = when (kind) {
        TransactionKind.EXPENSE, TransactionKind.REFUND -> this != INCOME
        TransactionKind.INCOME -> this != EXPENSE
        else -> false
    }

    companion object {
        fun of(key: String?): AppliesTo = entries.firstOrNull { it.key == key } ?: EXPENSE
    }
}

/**
 * How money works between the people of a household. [SPLIT]: each expense is
 * shared between people and balances say who owes whom. [SHARED]: one pot,
 * where nobody owes anybody (split.SharedPot). A household that has not
 * chosen (null) behaves like [SPLIT].
 */
enum class MoneyMode(val key: String) {
    SPLIT("split"), SHARED("shared");

    companion object {
        fun of(key: String?): MoneyMode? = entries.firstOrNull { it.key == key }
    }
}

data class Household(
    val id: String,
    val name: String,
    val currency: String,
    val locale: String,
    val periodStartDay: Int = 1,
    val incomeShiftDay: Int? = null,
    val weekStart: Int = 1,
    val memberLimit: Int = 20,
    val moneyMode: MoneyMode? = null,
)

data class Member(
    val id: String,
    val displayName: String,
    val initials: String,
    val colorIndex: Int = 0,
    val role: Role = Role.MEMBER,
    val status: MemberStatus = MemberStatus.ACTIVE,
    val hasAccount: Boolean = false,
) {
    val isActive: Boolean get() = status == MemberStatus.ACTIVE
}

data class Account(
    val id: String,
    val name: String,
    val type: AccountType = AccountType.OTHER,
    val openingBalanceMinor: Long = 0,
    /**
     * The day [openingBalanceMinor] was measured, as of its start. Null on a
     * legacy account: every movement counts, exactly as before this field
     * existed.
     */
    val openingBalanceDate: LocalDate? = null,
    val sort: Int = 0,
    val archived: Boolean = false,
)

data class Category(
    val id: String,
    val name: String,
    val appliesTo: AppliesTo = AppliesTo.EXPENSE,
    val parentId: String? = null,
    val icon: String = "label",
    val colorIndex: Int = 0,
    val sort: Int = 0,
    val archived: Boolean = false,
)

data class Budget(
    val id: String,
    val categoryId: String,
    /** YYYY-MM, or null for the default that applies to every period. */
    val period: String?,
    val amountMinor: Long,
)

/**
 * How an expense or refund is divided between members. The participants are
 * part of the row: a member who joins later changes no past split.
 */
sealed interface Split {
    val memberIds: Set<String>

    data class Equal(val members: List<String>) : Split {
        override val memberIds: Set<String> get() = members.toSet()
    }

    data class Shares(val shares: Map<String, Int>) : Split {
        override val memberIds: Set<String> get() = shares.keys
    }

    data class Exact(val amounts: Map<String, Long>) : Split {
        override val memberIds: Set<String> get() = amounts.keys
    }
}

data class Transaction(
    val id: String,
    val kind: TransactionKind,
    val date: LocalDate,
    val amountMinor: Long,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val paidByMemberId: String? = null,
    val toMemberId: String? = null,
    val split: Split? = null,
    val recurrence: Recurrence = Recurrence.VARIABLE,
    val note: String = "",
    val tags: List<String> = emptyList(),
    /** Custom field values by field key, in their wire form (string, number, boolean or list). */
    val extras: Map<String, Any?> = emptyMap(),
    val status: Status = Status.ACTIVE,
    val recurringRuleId: String? = null,
    val occurrenceDate: LocalDate? = null,
    val importFingerprint: String? = null,
    val originalAmountMinor: Long? = null,
    val originalCurrency: String? = null,
    /** The trip this row belongs to, only ever set on an expense or refund. */
    val tripId: String? = null,
    /**
     * Whether the wire form this row was decoded from carried a `trip_id`
     * key at all. False only for a row an old app version stored before it
     * knew about trips: Wire writes the key only when this is true or
     * [tripId] is not null, so such a row's own edits push without it and
     * fulla_sync_push keeps whatever trip was already stored (the same
     * "absent keeps its value" rule as extras).
     */
    val tripKnown: Boolean = true,
    /** ISO-8601 UTC, the creating phone's clock. */
    val createdAt: String,
    /** ISO-8601 UTC, the editing phone's clock. Decides conflicts; never pages the sync. */
    val clientUpdatedAt: String,
    /** Assigned by the server; 0 until the row has been pulled back. */
    val serverSeq: Long = 0,
    val createdByMemberId: String? = null,
) {
    val isActive: Boolean get() = status == Status.ACTIVE
}

/** The household's structure, as a pull carries it. */
data class Config(
    val version: Int,
    val household: Household,
    val meMemberId: String?,
    val members: List<Member> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val fields: List<io.github.sirallap.fulla.core.schema.CustomField> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val recurringRules: List<io.github.sirallap.fulla.core.recurring.RecurringRule> = emptyList(),
    val trips: List<Trip> = emptyList(),
) {
    val activeMembers: List<Member> get() = members.filter { it.isActive }
    fun member(id: String?): Member? = members.firstOrNull { it.id == id }
    fun category(id: String?): Category? = categories.firstOrNull { it.id == id }
    fun account(id: String?): Account? = accounts.firstOrNull { it.id == id }
    fun trip(id: String?): Trip? = trips.firstOrNull { it.id == id }
    fun me(): Member? = member(meMemberId)
}
