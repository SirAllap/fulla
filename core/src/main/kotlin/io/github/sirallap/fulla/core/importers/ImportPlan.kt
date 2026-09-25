// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.importers

import io.github.sirallap.fulla.core.defaults.Defaults
import io.github.sirallap.fulla.core.model.Account
import io.github.sirallap.fulla.core.model.AppliesTo
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.text.normalizeName
import java.security.MessageDigest
import java.util.UUID

/** "When a note contains [pattern], then …". The first active rule by [sort] wins. */
data class CategorizationRule(
    val id: String,
    val pattern: String,
    val categoryId: String? = null,
    /** Only [TransactionKind.EXPENSE], [TransactionKind.INCOME] or [TransactionKind.TRANSFER]. */
    val kind: TransactionKind? = null,
    val toAccountId: String? = null,
    val tags: List<String> = emptyList(),
    val sort: Int = 0,
    val active: Boolean = true,
)

object RuleEngine {
    fun match(rules: List<CategorizationRule>, description: String): CategorizationRule? {
        val text = description.normalizeName()
        return rules.filter { it.active }.sortedWith(compareBy({ it.sort }, { it.id }))
            .firstOrNull { text.contains(it.pattern.normalizeName()) }
    }
}

/** A statement line turned into a proposed transaction, for the review screen. */
data class ProposedTransaction(
    val transaction: Transaction,
    val line: StatementLine,
    val matchedRule: CategorizationRule?,
    /** The phone already holds a row with this id: importing again changes nothing. */
    val alreadyImported: Boolean,
    /** A category this line needs written first: new, or existing and widened to both kinds. */
    val newCategory: Category? = null,
    /** Accounts the file names that the household does not have yet; created with the import. */
    val newAccounts: List<Account> = emptyList(),
)

/**
 * What the household already calls things, so a file's own category, payer
 * and account columns land on the right ones. Names are compared without case
 * or accents; a member also answers to their initials.
 */
data class ImportNames(
    val categories: List<Category> = emptyList(),
    val members: List<Member> = emptyList(),
    val accounts: List<Account> = emptyList(),
)

/**
 * Turns statement lines into transactions.
 *
 * Each line gets a fingerprint of account, date, amount, description and its
 * position among identical lines in the same file, and a transaction id
 * derived from it. Importing the same statement twice, on one phone or on two,
 * produces the same ids, so nothing is duplicated.
 */
object ImportPlan {

    fun fingerprint(accountId: String, line: StatementLine, ordinal: Int): String {
        val text = listOf(accountId, line.date.toString(), line.amountMinor.toString(),
            line.description.normalizeName(), ordinal.toString()).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun propose(
        householdId: String,
        accountId: String,
        lines: List<StatementLine>,
        rules: List<CategorizationRule>,
        uncategorizedExpenseId: String,
        uncategorizedIncomeId: String,
        payerMemberId: String,
        defaultSplit: Split?,
        existingIds: Set<String>,
        now: String,
        names: ImportNames = ImportNames(),
    ): List<ProposedTransaction> {
        val seen = HashMap<String, Int>()
        val accounts = names.accounts.filter { !it.archived }.associateBy { it.name.normalizeName() }
        val members = names.members.filter { it.isActive }.let { active ->
            active.associateBy { it.initials.normalizeName() } + active.associateBy { it.displayName.normalizeName() }
        }
        val categories = names.categories.filter { !it.archived }
        // The categories a name can mean: its own name first, then, for one of
        // Fulla's starting categories, any of its names in six languages and
        // their synonyms ("Supermercado" is the household's Groceries). Top-level
        // before subcategories.
        val byName = HashMap<String, MutableList<Category>>()
        val ordered = categories.sortedBy { it.parentId != null }
        for (c in ordered) byName.getOrPut(c.name.normalizeName()) { mutableListOf() } += c
        for (c in ordered) {
            val keys = CategoryHints.defaultKeys(c.name)
            val aliases = Defaults.categories.filter { it.key in keys }
                .flatMap { d -> d.names.values + listOf(d.key) }.map { it.normalizeName() }.toSet() +
                CategoryHints.synonymsOf(keys)
            for (a in aliases) byName.getOrPut(a) { mutableListOf() }.let { if (c !in it) it += c }
        }

        // First pass: where each line's money went, and whether it is spending,
        // income, a refund or money changing pockets.
        // Accounts the file names and the household lacks are created, once
        // each, like categories; a line without one goes to [accountId].
        val newAccounts = LinkedHashMap<String, Account>()
        fun accountFor(name: String): Account {
            val key = name.normalizeName()
            return accounts[key] ?: newAccounts.getOrPut(key) {
                Account(id = accountIdFor(householdId, name), name = name.trim().take(60),
                    sort = accounts.size + newAccounts.size)
            }
        }
        val accountsInFile = lines.mapNotNull { it.account?.normalizeName()?.takeIf(String::isNotEmpty) }.toSet()
        class Read(val line: StatementLine, val account: String, val rule: CategorizationRule?, val pocket: Account?, val name: String?)
        val read = lines.map { line ->
            val lineAccount = line.account?.trim()?.takeIf { it.isNotEmpty() }?.let { accountFor(it).id } ?: accountId
            val rule = RuleEngine.match(rules, line.description)
            // A line whose category is the name of one of the household's own
            // accounts is money moving between pockets (cash drawn, cash paid
            // in), not spending or income: counting it would count it twice.
            val pocket = line.bankCategory?.takeIf { rule?.categoryId == null }
                ?.let { accounts[it.normalizeName()] ?: it.takeIf { n -> n.normalizeName() in accountsInFile }?.let(::accountFor) }
                ?.takeIf { it.id != lineAccount }
            val name = line.bankCategory?.takeIf { rule?.categoryId == null && pocket == null }?.trim()?.takeIf { it.isNotEmpty() }
            Read(line, lineAccount, rule, pocket, name)
        }
        // The file's own word is kept: money in is income, even in a category
        // it also spends in, so the totals match the ledger it came from. Only
        // a rule that files money in under a spending category makes a refund.
        fun kindOf(r: Read): TransactionKind = when {
            r.rule?.kind == TransactionKind.TRANSFER && r.line.amountMinor < 0 -> TransactionKind.TRANSFER
            r.pocket != null -> TransactionKind.TRANSFER
            r.line.amountMinor < 0 -> TransactionKind.EXPENSE
            r.rule?.categoryId != null && categories.firstOrNull { it.id == r.rule.categoryId }?.appliesTo == AppliesTo.EXPENSE -> TransactionKind.REFUND
            else -> TransactionKind.INCOME
        }
        val kinds = read.map(::kindOf)

        // Second pass: one category per name. An existing one is used, and
        // widened to both kinds if the file needs it for the other; a missing
        // one is created once, for whatever the file uses it for.
        val usedFor = HashMap<String, MutableSet<TransactionKind>>()
        read.forEachIndexed { i, r -> r.name?.let { usedFor.getOrPut(it.normalizeName()) { mutableSetOf() } += kinds[i] } }
        val written = HashMap<String, Category>()
        val widened = HashMap<String, Category>()
        fun categoryFor(name: String): Category {
            val key = name.normalizeName()
            written[key]?.let { return it }
            val uses = usedFor[key].orEmpty()
            val spends = TransactionKind.EXPENSE in uses || TransactionKind.REFUND in uses
            val earns = TransactionKind.INCOME in uses
            val needs = when {
                spends && earns -> AppliesTo.BOTH
                earns -> AppliesTo.INCOME
                else -> AppliesTo.EXPENSE
            }
            val candidates = byName[key].orEmpty()
            val existing = candidates.firstOrNull { (!spends || it.appliesTo != AppliesTo.INCOME) && (!earns || it.appliesTo != AppliesTo.EXPENSE) }
                ?: candidates.firstOrNull()
            // Two file names can land on one category: widen what was widened.
            val base = existing?.let { widened[it.id] ?: it }
            val category = when {
                base == null -> Category(
                    id = categoryId(householdId, name), name = name.take(60), appliesTo = needs,
                    icon = CategoryHints.iconFor(name) ?: "label",
                    colorIndex = (categories.size + written.size) % 12, sort = categories.size + written.size,
                )
                (spends && base.appliesTo == AppliesTo.INCOME) || (earns && base.appliesTo == AppliesTo.EXPENSE) ->
                    base.copy(appliesTo = AppliesTo.BOTH).also { widened[it.id] = it }
                else -> base
            }
            written[key] = category
            return category
        }

        return read.mapIndexed { i, r ->
            val line = r.line
            val kind = kinds[i]
            val identity = "${r.account}|${line.date}|${line.amountMinor}|${line.description.normalizeName()}"
            val ordinal = seen.merge(identity, 1, Int::plus)!! - 1
            val fp = fingerprint(r.account, line, ordinal)
            val id = DeterministicId.imported(householdId, fp)
            val amount = kotlin.math.abs(line.amountMinor)
            val category = r.name?.takeIf { kind != TransactionKind.TRANSFER }?.let(::categoryFor)
            // Only what changes needs writing: a new category, or one widened.
            val toWrite = category?.takeIf { c -> categories.none { it == c } }
            val payer = line.payer?.let { members[it.normalizeName()]?.id } ?: payerMemberId
            val base = Transaction(
                id = id, kind = kind, date = line.date, amountMinor = amount,
                note = line.description.take(500), tags = r.rule?.tags.orEmpty(),
                importFingerprint = fp, createdAt = now, clientUpdatedAt = now,
                recurrence = if (line.fixed == true && kind != TransactionKind.TRANSFER) Recurrence.FIXED else Recurrence.VARIABLE,
            )
            val tx = when (kind) {
                TransactionKind.TRANSFER -> when {
                    r.pocket == null -> base.copy(accountId = r.account, toAccountId = r.rule!!.toAccountId)
                    line.amountMinor < 0 -> base.copy(accountId = r.account, toAccountId = r.pocket.id)
                    else -> base.copy(accountId = r.pocket.id, toAccountId = r.account)
                }
                TransactionKind.INCOME -> base.copy(
                    accountId = r.account,
                    categoryId = r.rule?.categoryId ?: category?.id ?: uncategorizedIncomeId,
                )
                else -> base.copy(
                    accountId = r.account,
                    categoryId = r.rule?.categoryId ?: category?.id ?: uncategorizedExpenseId,
                    paidByMemberId = payer,
                    split = defaultSplit,
                )
            }
            val usedAccounts = setOfNotNull(tx.accountId, tx.toAccountId)
            ProposedTransaction(tx, line, r.rule, alreadyImported = id in existingIds, newCategory = toWrite,
                newAccounts = newAccounts.values.filter { it.id in usedAccounts })
        }
    }

    /** The accounts an import creates, once each. */
    fun newAccounts(proposals: List<ProposedTransaction>): List<Account> =
        proposals.filter { !it.alreadyImported }.flatMap { it.newAccounts }.distinctBy { it.id }

    fun accountIdFor(householdId: String, name: String): String =
        DeterministicId.uuid5(UUID.fromString(householdId), "account|${name.normalizeName()}")

    /** The categories an import writes, once each: new ones, and existing ones widened to both kinds. */
    fun newCategories(proposals: List<ProposedTransaction>): List<Category> =
        proposals.filter { !it.alreadyImported }.mapNotNull { it.newCategory }.groupBy { it.id }
            .map { (_, versions) -> versions.firstOrNull { it.appliesTo == AppliesTo.BOTH } ?: versions.first() }

    /**
     * A category created by an import has an id derived from its name, so the
     * same file imported on two phones creates one category, not two.
     */
    fun categoryId(householdId: String, name: String): String =
        DeterministicId.uuid5(UUID.fromString(householdId), "category|${name.normalizeName()}")
}
