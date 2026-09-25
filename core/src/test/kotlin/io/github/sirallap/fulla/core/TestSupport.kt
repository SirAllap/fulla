// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.core.model.AppliesTo
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Household
import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.Role
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.time.LocalDate

/** Shared fixtures. Invented people, invented amounts, a fictitious year. */
object Fixtures {
    val root: File = File(System.getProperty("fulla.root") ?: "..")

    fun json(path: String): JsonElement = Json.parseToJsonElement(File(root, path).readText())

    const val ALICE = "00000000-0000-4000-8000-00000000000a"
    const val BOB = "00000000-0000-4000-8000-00000000000b"
    const val CAROL = "00000000-0000-4000-8000-00000000000c"
    const val GROCERIES = "00000000-0000-4000-8000-000000000101"
    const val SALARY = "00000000-0000-4000-8000-000000000102"
    const val LEISURE = "00000000-0000-4000-8000-000000000103"
    const val SNACKS = "00000000-0000-4000-8000-000000000104"
    const val MAIN = "00000000-0000-4000-8000-000000000201"
    const val CASH = "00000000-0000-4000-8000-000000000202"

    fun config(members: List<Member> = listOf(
        Member(ALICE, "Alice", "A", 0, Role.OWNER, hasAccount = true),
        Member(BOB, "Bob", "B", 1, Role.MEMBER, hasAccount = true),
    )) = Config(
        version = 1,
        household = Household("00000000-0000-4000-8000-000000000001", "Demo household", "EUR", "en-GB"),
        meMemberId = ALICE,
        members = members,
        accounts = listOf(Account(MAIN, "Main account", AccountType.CHECKING), Account(CASH, "Cash", AccountType.CASH)),
        categories = listOf(
            Category(GROCERIES, "Groceries", AppliesTo.EXPENSE),
            Category(SALARY, "Salary", AppliesTo.INCOME),
            Category(LEISURE, "Leisure", AppliesTo.EXPENSE),
            Category(SNACKS, "Snacks", AppliesTo.EXPENSE, parentId = GROCERIES),
        ),
    )

    private var counter = 0
    fun newId(): String = "00000000-0000-4000-9000-%012d".format(++counter)

    fun expense(
        amount: Long = 1234,
        date: LocalDate = LocalDate.of(2030, 1, 15),
        payer: String = ALICE,
        split: Split? = Split.Equal(listOf(ALICE, BOB)),
        category: String = GROCERIES,
        stamp: String = "2030-01-15T12:00:00.000Z",
        id: String = newId(),
    ) = Transaction(
        id = id, kind = TransactionKind.EXPENSE, date = date, amountMinor = amount, categoryId = category,
        accountId = MAIN, paidByMemberId = payer, split = split, note = "GROCERY STORE 01",
        createdAt = stamp, clientUpdatedAt = stamp,
    )

    fun income(amount: Long, date: LocalDate, fixed: Boolean = true) = Transaction(
        id = newId(), kind = TransactionKind.INCOME, date = date, amountMinor = amount, categoryId = SALARY,
        accountId = MAIN, paidByMemberId = ALICE,
        recurrence = if (fixed) io.github.sirallap.fulla.core.model.Recurrence.FIXED else io.github.sirallap.fulla.core.model.Recurrence.VARIABLE,
        createdAt = "2030-01-01T00:00:00.000Z", clientUpdatedAt = "2030-01-01T00:00:00.000Z",
    )
}
