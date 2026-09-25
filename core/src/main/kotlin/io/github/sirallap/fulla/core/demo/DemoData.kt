// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.demo

import io.github.sirallap.fulla.core.defaults.Defaults
import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Household
import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Role
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.recurring.DeterministicId
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

/**
 * An invented household to explore the app with: Alice, Bob and Carol (who has
 * no account), three months of made-up spending. Every row is tagged
 * [TAG] so it can be removed in one go, and every amount comes from a seeded
 * random generator, never from anybody's real life.
 */
object DemoData {

    const val TAG = "demo"
    private val NAMESPACE = UUID.fromString("6f1c2b8e-3d4a-4e5f-9a0b-1c2d3e4f5a6b")

    data class Demo(val config: Config, val transactions: List<Transaction>)

    fun build(today: LocalDate, language: String = "en", seed: Int = 2030): Demo {
        fun id(name: String) = DeterministicId.uuid5(NAMESPACE, name)
        val random = Random(seed)
        val alice = Member(id("alice"), "Alice", "A", 0, Role.OWNER, hasAccount = true)
        val bob = Member(id("bob"), "Bob", "B", 1, Role.ADMIN, hasAccount = true)
        val carol = Member(id("carol"), "Carol", "C", 2)
        val members = listOf(alice, bob, carol)
        val categories = Defaults.categories.mapIndexed { i, c ->
            Category(id("category:${c.key}"), c.name(language), c.appliesTo, icon = c.icon, colorIndex = c.colorIndex, sort = i)
        }
        val accounts = Defaults.accounts.mapIndexed { i, a -> Account(id("account:${a.key}"), a.name(language), a.type, sort = i) }
        fun cat(key: String) = id("category:$key")
        val main = id("account:main")
        val cash = id("account:cash")
        val everyone = Split.Equal(members.map { it.id })

        val out = mutableListOf<Transaction>()
        var n = 0
        fun add(date: LocalDate, kind: TransactionKind, amount: Long, category: String?, note: String,
                payer: Member? = null, recurrence: Recurrence = Recurrence.VARIABLE,
                account: String = main, toAccount: String? = null) {
            val stamp = "${date}T12:00:00.000Z"
            out += Transaction(
                id = id("tx:${n++}"), kind = kind, date = date, amountMinor = amount, categoryId = category,
                accountId = account, toAccountId = toAccount,
                paidByMemberId = payer?.id, split = if (kind == TransactionKind.EXPENSE) everyone else null,
                recurrence = recurrence, note = note, tags = listOf(TAG), createdAt = stamp, clientUpdatedAt = stamp,
                createdByMemberId = payer?.id ?: alice.id,
            )
        }

        for (monthsAgo in 2 downTo 0) {
            val month = today.minusMonths(monthsAgo.toLong()).withDayOfMonth(1)
            val last = if (monthsAgo == 0) today else month.plusMonths(1).minusDays(1)
            add(month, TransactionKind.INCOME, 320_000, cat("salary"), "ACME CORP", alice, Recurrence.FIXED)
            add(month.plusDays(2), TransactionKind.INCOME, 280_000, cat("salary"), "EXAMPLE LTD", bob, Recurrence.FIXED)
            add(month.plusDays(1), TransactionKind.EXPENSE, 150_000, cat("housing"), "RENT", alice, Recurrence.FIXED)
            add(month.plusDays(4), TransactionKind.EXPENSE, 1_299, cat("subscriptions"), "STREAMING SERVICE", bob, Recurrence.FIXED)
            add(month.plusDays(9), TransactionKind.EXPENSE, 8_000 + random.nextLong(4_000), cat("utilities"), "POWER UTILITY", alice, Recurrence.FIXED)
            add(month.plusDays(3), TransactionKind.TRANSFER, 20_000, null, "CASH MACHINE", alice, account = main, toAccount = cash)
            var day = month
            while (day <= last) {
                if (random.nextInt(3) == 0) {
                    add(day, TransactionKind.EXPENSE, 1_500 + random.nextLong(9_000), cat("groceries"),
                        "GROCERY STORE 0${1 + random.nextInt(3)}", listOf(alice, bob).random(random))
                }
                if (random.nextInt(7) == 0) {
                    add(day, TransactionKind.EXPENSE, 800 + random.nextLong(3_500), cat("eating_out"), "COFFEE SHOP",
                        members.random(random), account = if (random.nextBoolean()) cash else main)
                }
                if (random.nextInt(10) == 0) {
                    add(day, TransactionKind.EXPENSE, 250 + random.nextLong(1_000), cat("transport"), "CITY TRANSIT",
                        listOf(alice, bob).random(random))
                }
                day = day.plusDays(1)
            }
        }
        val name = mapOf("es" to "Hogar de ejemplo", "fr" to "Foyer d'exemple", "de" to "Beispielhaushalt",
            "it" to "Casa di esempio", "pt" to "Casa de exemplo")[language] ?: "Demo household"
        val household = Household(id("household"), name, "EUR", mapOf("es" to "es-ES", "fr" to "fr-FR", "de" to "de-DE", "it" to "it-IT", "pt" to "pt-PT")[language] ?: "en-GB")
        val config = Config(1, household, alice.id, members, accounts, categories)
        return Demo(config, out.sortedBy { it.date })
    }
}
