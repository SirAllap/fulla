// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.analytics.Slice
import io.github.sirallap.fulla.core.model.AppliesTo
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.schema.CustomField
import io.github.sirallap.fulla.core.schema.FieldType
import io.github.sirallap.fulla.core.schema.SchemaEngine
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pets › Vet › who it was for: a subcategory, then a field limited to it. */
class CategoryLevelsTest {
    private val pets = "00000000-0000-4000-8000-000000000301"
    private val vet = "00000000-0000-4000-8000-000000000302"
    private val food = "00000000-0000-4000-8000-000000000303"
    private val forWhom = CustomField("f1", "for_whom", mapOf("en" to "For whom"), FieldType.SELECT, setOf(TransactionKind.EXPENSE),
        options = listOf("Rex", "Tom", "All"), categoryIds = setOf(vet))
    private val shop = CustomField("f2", "shop", mapOf("en" to "Shop"), FieldType.TEXT, setOf(TransactionKind.EXPENSE))
    private val config = Fixtures.config().let { c ->
        c.copy(categories = c.categories + listOf(Category(pets, "Pets"), Category(vet, "Vet", parentId = pets),
            Category(food, "Food", parentId = pets)), fields = listOf(forWhom, shop))
    }
    private val jan = YearMonth.of(2030, 1)
    private fun d(day: Int) = LocalDate.of(2030, 1, day)
    private fun spend(amount: Long, category: String, who: String? = null) =
        Fixtures.expense(amount, d(10), category = category).copy(extras = if (who == null) emptyMap() else mapOf("for_whom" to who))
    private val rows = listOf(
        spend(6_855, vet, "Rex"), spend(2_700, vet, "Tom"), spend(4_595, vet, "All"), spend(1_000, vet),
        spend(2_259, food), spend(399, pets),
        spend(9_999, Fixtures.GROCERIES),
    )
    private val analytics = Analytics(config, PeriodRule())

    @Test
    fun `a category splits into its subcategories, its own rows under its own id`() {
        assertEquals(listOf(Slice(vet, 15_150), Slice(food, 2_259), Slice(pets, 399)), analytics.bySubcategory(rows, jan, pets))
        assertEquals(emptyList(), analytics.bySubcategory(rows, jan, Fixtures.GROCERIES), "no subcategories: nothing to split")
        assertEquals(17_808, analytics.byCategory(rows, jan).first { it.categoryId == pets }.amountMinor, "the total still rolls up")
    }

    @Test
    fun `a subcategory splits by who it was for, rows without it last`() {
        assertEquals(listOf(Slice("Rex", 6_855), Slice("All", 4_595), Slice("Tom", 2_700), Slice(null, 1_000)),
            analytics.byFieldValue(rows, jan, vet, "for_whom"))
        assertEquals(listOf(6_855L), analytics.spending(rows, jan, vet, withSubcategories = false, field = "for_whom" to "Rex").map { it.amountMinor })
        assertEquals(listOf(1_000L), analytics.spending(rows, jan, vet, withSubcategories = false, field = "for_whom" to null).map { it.amountMinor })
        assertEquals(6, analytics.spending(rows, jan, pets, withSubcategories = true).size)
    }

    @Test
    fun `a field limited to a category is asked there, and only there`() {
        val byId = config.categories.associateBy { it.id }
        assertEquals(listOf("for_whom"), SchemaEngine.fieldsForCategory(config.fields, TransactionKind.EXPENSE, byId[vet]).map { it.key })
        assertEquals(emptyList(), SchemaEngine.fieldsForCategory(config.fields, TransactionKind.EXPENSE, byId[food]))
        assertEquals(emptyList(), SchemaEngine.fieldsForCategory(config.fields, TransactionKind.EXPENSE, null))
        val onParent = forWhom.copy(categoryIds = setOf(pets))
        assertEquals(listOf("for_whom"), SchemaEngine.fieldsForCategory(listOf(onParent), TransactionKind.EXPENSE, byId[food]).map { it.key },
            "limited to the parent: every subcategory asks")
        assertEquals(listOf("shop"), SchemaEngine.fieldsForForm(config.fields, TransactionKind.EXPENSE).map { it.key },
            "the details ask only the fields not limited to categories")
        assertEquals(AppliesTo.EXPENSE, byId[vet]!!.appliesTo)
    }
}

class TopCategoriesTest {
    private val jan = YearMonth.of(2030, 1)
    private val config = Fixtures.config()
    private val analytics = Analytics(config, PeriodRule())

    @Test
    fun `the top categories, then the rest together, never below zero`() {
        val rows = listOf(
            Fixtures.expense(5_000, category = Fixtures.GROCERIES), Fixtures.expense(1_000, category = Fixtures.SNACKS),
            Fixtures.expense(3_000, category = Fixtures.LEISURE),
            Fixtures.expense(700, category = Fixtures.SALARY).copy(kind = TransactionKind.REFUND),
        )
        assertEquals(listOf(Slice(Fixtures.GROCERIES, 6_000), Slice(Fixtures.LEISURE, 3_000)), analytics.topCategories(rows, jan))
        assertEquals(listOf(Slice(Fixtures.GROCERIES, 6_000), Slice(null, 3_000)), analytics.topCategories(rows, jan, top = 1))
    }

    @Test
    fun `no earlier spending is no trend`() {
        val rows = listOf(Fixtures.expense(50_000, category = Fixtures.LEISURE))
        assertEquals(emptyList(), analytics.trends(rows, jan, minimumMinor = 500))
    }
}
