// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.categories

import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Transaction

/**
 * What still refers to a category. Nothing is ever physically deleted (rows
 * point at their category), so deleting one is archiving it: if nothing
 * refers to it any more it simply leaves the list, and if something does it
 * stays there, archived, because that history needs its name.
 */
object CategoryUse {

    /** What refers to one category. Rows counted are the ones that are not deleted; archived subcategories, fixed costs and fields do not count. */
    data class Use(val rows: Int, val budgets: Int, val recurring: Int, val fields: Int, val children: Int) {
        val total: Int get() = rows + budgets + recurring + fields + children
        val unused: Boolean get() = total == 0
    }

    fun of(config: Config, rows: Iterable<Transaction>, id: String): Use = Use(
        rows = rows.count { it.isActive && it.categoryId == id },
        budgets = config.budgets.count { it.categoryId == id },
        recurring = config.recurringRules.count { !it.archived && it.template.categoryId == id },
        fields = config.fields.count { !it.archived && it.categoryIds?.contains(id) == true },
        children = config.categories.count { it.parentId == id && !it.archived },
    )

    /**
     * The categories the entries of [id] can be moved to when it is deleted: not
     * archived, not itself nor below it, and allowed for every kind of entry that
     * uses it. The category above comes first, then the rest as they are listed.
     */
    fun targetsFor(config: Config, rows: Iterable<Transaction>, id: String): List<Category> {
        val kinds = rows.filter { it.isActive && it.categoryId == id }.map { it.kind }.toSet()
        val below = config.categories.filter { it.parentId == id }.map { it.id }.toSet()
        val parent = config.category(id)?.parentId
        return config.categories
            .filter { !it.archived && it.id != id && it.id !in below && kinds.all { k -> it.appliesTo.allows(k) } }
            .sortedWith(compareBy<Category>({ it.id != parent }, { it.parentId != null }, { it.sort }))
    }

    /** The entries of [from], as they are once moved to [to]: only those are returned. */
    fun move(rows: Iterable<Transaction>, from: String, to: String): List<Transaction> =
        rows.filter { it.isActive && it.categoryId == from }.map { it.copy(categoryId = to) }

    /**
     * Every category something refers to, and the category above each of
     * those: the ones an archived category must stay in the list for. Computed
     * once for the whole list instead of once per category.
     */
    fun referenced(config: Config, rows: Iterable<Transaction>): Set<String> {
        val ids = HashSet<String>()
        for (t in rows) if (t.isActive) t.categoryId?.let(ids::add)
        config.budgets.forEach { ids += it.categoryId }
        config.recurringRules.filter { !it.archived }.forEach { r -> r.template.categoryId?.let(ids::add) }
        config.fields.filter { !it.archived }.forEach { f -> f.categoryIds?.let(ids::addAll) }
        config.categories.filter { !it.archived }.forEach { c -> c.parentId?.let(ids::add) }
        // A subcategory that stays keeps the category it sits under.
        for (c in config.categories) if (c.id in ids) c.parentId?.let(ids::add)
        return ids
    }

    /** Whether the settings list leaves [categoryId] out: it was deleted (archived) and nothing refers to it. */
    fun isGone(config: Config, referenced: Set<String>, categoryId: String): Boolean =
        config.category(categoryId)?.let { it.archived && it.id !in referenced } ?: false
}
