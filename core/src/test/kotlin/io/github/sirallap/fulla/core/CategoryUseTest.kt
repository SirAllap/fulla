// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.categories.CategoryUse
import io.github.sirallap.fulla.core.model.Budget
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Config
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A category deleted by mistake leaves the list; one with history stays, archived. */
class CategoryUseTest {
    private val base: Config = Fixtures.config()
    private val shop: Category = base.categories.first { it.id == Fixtures.GROCERIES }
    private val sub: Category = Category(id = "00000000-0000-4000-8000-0000000008a1", name = "Other", appliesTo = shop.appliesTo, parentId = shop.id, icon = "label")
    private val config: Config = base.copy(categories = base.categories + sub)
    private val day = LocalDate.of(2030, 1, 5)

    @Test
    fun `nothing refers to a subcategory made by mistake`() {
        val use = CategoryUse.of(config, listOf(Fixtures.expense(100, day, category = Fixtures.GROCERIES)), sub.id)
        assertTrue(use.unused)
        // Deleted, it leaves the list.
        val gone = config.copy(categories = config.categories.map { if (it.id == sub.id) it.copy(archived = true) else it })
        assertTrue(CategoryUse.isGone(gone, CategoryUse.referenced(gone, emptyList()), sub.id))
    }

    @Test
    fun `entries, budgets, fixed costs and subcategories keep a deleted category in the list`() {
        val row = Fixtures.expense(100, day, category = sub.id)
        assertEquals(1, CategoryUse.of(config, listOf(row), sub.id).rows)
        assertEquals(0, CategoryUse.of(config, listOf(row.copy(status = Status.DELETED)), sub.id).rows, "a deleted entry does not count")
        val withBudget = config.copy(budgets = listOf(Budget("00000000-0000-4000-8000-0000000008b1", sub.id, null, 5_000)))
        assertEquals(1, CategoryUse.of(withBudget, emptyList(), sub.id).budgets)
        val rule = RecurringRule("00000000-0000-4000-8000-0000000008c1", "Gym", Fixtures.expense(100, day, category = sub.id),
            Schedule(Frequency.MONTHLY, byMonthDay = 1), day)
        assertEquals(1, CategoryUse.of(config.copy(recurringRules = listOf(rule)), emptyList(), sub.id).recurring)
        assertEquals(2, CategoryUse.of(config, emptyList(), shop.id).children, "Snacks and the one made here")

        fun archived(c: Config) = c.copy(categories = c.categories.map { if (it.id == sub.id) it.copy(archived = true) else it })
        val a = archived(config)
        assertFalse(CategoryUse.isGone(a, CategoryUse.referenced(a, listOf(row)), sub.id), "history needs its name")
        assertFalse(CategoryUse.isGone(a, CategoryUse.referenced(a, emptyList()), shop.id), "a category that is not archived is never left out")
    }

    @Test
    fun `the entries of a deleted category move to the one above it, or to any other that allows them`() {
        val rows = listOf(Fixtures.expense(100, day, category = sub.id), Fixtures.expense(200, day, category = Fixtures.LEISURE))
        val targets = CategoryUse.targetsFor(config, rows, sub.id)
        assertEquals(shop.id, targets.first().id, "the category above comes first")
        assertFalse(sub.id in targets.map { it.id })
        assertFalse(Fixtures.SALARY in targets.map { it.id }, "an income category does not take expenses")
        val moved = CategoryUse.move(rows, sub.id, shop.id)
        assertEquals(1, moved.size, "only what used it")
        assertEquals(shop.id, moved.single().categoryId)
        assertEquals(sub.id, rows.first().categoryId, "the originals are not touched")
        // A category with subcategories offers neither itself nor them.
        val parentTargets = CategoryUse.targetsFor(config, listOf(Fixtures.expense(100, day, category = shop.id)), shop.id).map { it.id }
        assertFalse(shop.id in parentTargets || sub.id in parentTargets || Fixtures.SNACKS in parentTargets)
        assertTrue(Fixtures.LEISURE in parentTargets)
    }

    @Test
    fun `the category above one that stays also stays`() {
        val row = Fixtures.expense(100, day, category = sub.id)
        val archivedBoth = config.copy(categories = config.categories.map { if (it.id == sub.id || it.id == shop.id) it.copy(archived = true) else it })
        val kept = CategoryUse.referenced(archivedBoth, listOf(row))
        assertTrue(shop.id in kept && sub.id in kept)
        assertFalse(CategoryUse.isGone(archivedBoth, kept, shop.id))
    }
}
