// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.Role
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.model.TransactionValidator
import io.github.sirallap.fulla.core.schema.CustomField
import io.github.sirallap.fulla.core.schema.FieldProblem
import io.github.sirallap.fulla.core.schema.FieldType
import io.github.sirallap.fulla.core.schema.SchemaEngine
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaAndValidatorTest {
    private val expense = setOf(TransactionKind.EXPENSE)
    private fun field(key: String, type: FieldType, options: List<String> = emptyList(), required: Boolean = false, default: String? = null) =
        CustomField(key, key, mapOf("en" to key), type, expense, required, options, default)

    private val fields = listOf(
        field("shop", FieldType.TEXT),
        field("method", FieldType.SELECT, listOf("Card", "Cash")),
        field("people", FieldType.NUMBER),
        field("tip", FieldType.MONEY),
        field("business", FieldType.BOOLEAN, required = true, default = "false"),
        field("for_whom", FieldType.MEMBER),
    )
    private val members = setOf(Fixtures.ALICE, Fixtures.BOB)

    @Test
    fun `an absent key keeps its value, null clears it, unknown keys warn`() {
        val stored = mapOf<String, Any?>("shop" to "CORNER SHOP", "method" to "Card", "business" to false)
        val r = SchemaEngine.merge(fields, TransactionKind.EXPENSE, mapOf("method" to "Cash", "mystery" to 1), stored, members)
        assertEquals(mapOf("shop" to "CORNER SHOP", "method" to "Cash", "business" to false), r.extras)
        assertEquals(listOf("unknown_field:mystery"), r.warnings)
        val cleared = SchemaEngine.merge(fields, TransactionKind.EXPENSE, mapOf("shop" to null), stored, members)
        assertTrue("shop" !in cleared.extras)
    }

    @Test
    fun `values are checked and put in canonical form like the database does`() {
        val r = SchemaEngine.merge(fields, TransactionKind.EXPENSE,
            mapOf("people" to 3.50, "tip" to "250", "for_whom" to Fixtures.ALICE.uppercase(), "method" to "Voucher"), emptyMap(), members)
        assertEquals("3.5", r.extras["people"])
        assertEquals(250L, r.extras["tip"])
        assertEquals(Fixtures.ALICE, r.extras["for_whom"])
        assertEquals("Voucher", r.extras["method"], "an unknown option is kept")
        assertEquals(false, r.extras["business"], "a required field takes its default")
        assertEquals(listOf("unknown_option:method"), r.warnings)
        val bad = SchemaEngine.merge(fields, TransactionKind.EXPENSE, mapOf("people" to "three", "tip" to 2.5, "for_whom" to "someone"), emptyMap(), members)
        assertEquals(3, bad.problems.size)
        assertTrue(bad.problems.any { it is FieldProblem.UnknownMember })
    }

    @Test
    fun `dimensions and measures`() {
        assertEquals(listOf("method", "business", "for_whom"), SchemaEngine.dimensions(fields).map { it.key })
        assertEquals(listOf("people", "tip"), SchemaEngine.measures(fields).map { it.key })
    }

    @Test
    fun `what each kind of transaction requires`() {
        val config = Fixtures.config()
        fun problems(t: io.github.sirallap.fulla.core.model.Transaction) = TransactionValidator.problems(t, config)
        assertTrue(problems(Fixtures.expense()).isEmpty())
        assertTrue(problems(Fixtures.expense(split = null)).isNotEmpty(), "two members need a split")
        assertTrue(problems(Fixtures.expense(category = Fixtures.SALARY)).isNotEmpty())
        assertTrue(problems(Fixtures.expense(amount = 0)).isNotEmpty())
        assertTrue(problems(Fixtures.expense(split = Split.Equal(listOf(Fixtures.CAROL)))).isNotEmpty())
        val single = Fixtures.config(listOf(Member(Fixtures.ALICE, "Alice", "A", role = Role.OWNER)))
        assertTrue(TransactionValidator.problems(Fixtures.expense(split = null), single).isEmpty(), "one member needs no split")
        val base = Fixtures.expense()
        val transfer = base.copy(kind = TransactionKind.TRANSFER, categoryId = null, split = null, toAccountId = Fixtures.CASH)
        assertTrue(problems(transfer).isEmpty())
        assertTrue(problems(transfer.copy(toAccountId = Fixtures.MAIN)).isNotEmpty())
        val settle = base.copy(kind = TransactionKind.SETTLEMENT, categoryId = null, split = null, paidByMemberId = Fixtures.BOB, toMemberId = Fixtures.ALICE)
        assertTrue(problems(settle).isEmpty())
        assertTrue(problems(settle.copy(toMemberId = Fixtures.BOB)).isNotEmpty())
        assertTrue(problems(Fixtures.income(1000, LocalDate.of(2030, 1, 1))).isEmpty())
        assertTrue(problems(Fixtures.income(1000, LocalDate.of(2030, 1, 1)).copy(split = Split.Equal(listOf(Fixtures.ALICE)))).isNotEmpty())
    }
}
