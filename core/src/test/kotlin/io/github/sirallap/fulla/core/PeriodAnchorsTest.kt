// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Status
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.rules.PeriodAnchors
import io.github.sirallap.fulla.core.rules.PeriodRule
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeriodAnchorsTest {
    private fun d(month: Int, day: Int) = LocalDate.of(2030, month, day)
    private val config = Fixtures.config().let { c -> c.copy(household = c.household.copy(periodStartDay = 28)) }
    private fun salary(date: LocalDate) = PeriodAnchors.mark(Fixtures.income(200_000, date), true)
    private fun expense(date: LocalDate) = PeriodRule.of(config, rows).periodOf(date, TransactionKind.EXPENSE, Recurrence.VARIABLE)
    private var rows = listOf(salary(d(8, 28)))

    @Test
    fun `a purchase moves to the next period once the salary of the same day is written down`() {
        assertEquals(YearMonth.of(2030, 9), expense(d(9, 29)), "no salary yet: September stays open past the 28th")
        rows = rows + Fixtures.income(100_000, d(9, 26)) // another income, not marked: moves nothing
        assertEquals(YearMonth.of(2030, 9), expense(d(9, 27)))
        rows = rows + salary(d(9, 29))
        assertEquals(YearMonth.of(2030, 10), expense(d(9, 29)))
        assertEquals(YearMonth.of(2030, 9), expense(d(9, 28)))
    }

    @Test
    fun `only confirmed, active, marked income counts`() {
        val generated = salary(d(9, 28)).copy(recurringRuleId = Fixtures.newId(),
            createdAt = "2030-09-28T06:00:00Z", clientUpdatedAt = "2030-09-28T06:00:00Z")
        assertFalse(PeriodAnchors.confirmed(generated))
        assertTrue(PeriodAnchors.confirmed(generated.copy(clientUpdatedAt = "2030-09-28T09:00:00Z")))
        val all = listOf(generated, salary(d(9, 20)).copy(status = Status.DELETED), Fixtures.income(1, d(9, 21)),
            PeriodAnchors.mark(Fixtures.expense(1, d(9, 22)), true))
        assertEquals(emptyList(), PeriodAnchors.from(all))
    }

    @Test
    fun `the mark is a hidden tag that keeps the others`() {
        val t = Fixtures.income(1, d(9, 1)).copy(tags = listOf("bonus"))
        val marked = PeriodAnchors.mark(PeriodAnchors.mark(t, true), true)
        assertEquals(listOf("bonus", PeriodAnchors.TAG), marked.tags)
        assertEquals(listOf("bonus"), PeriodAnchors.visibleTags(marked.tags))
        assertEquals(listOf("bonus"), PeriodAnchors.mark(marked, false).tags)
    }

    @Test
    fun `a salary like the last one is proposed, and asked about once it arrives unmarked`() {
        val last = salary(d(8, 28))
        val imported = Fixtures.income(231_378, d(9, 29))
        val otherAccount = Fixtures.income(100_000, d(9, 27)).copy(accountId = Fixtures.CASH)
        val tooSoon = Fixtures.income(5_000, d(9, 5))
        assertTrue(PeriodAnchors.looksLikeSalary(imported, last))
        assertFalse(PeriodAnchors.looksLikeSalary(otherAccount, last))
        assertFalse(PeriodAnchors.looksLikeSalary(imported, null))
        val rows = listOf(last, imported, otherAccount, tooSoon)
        assertEquals(imported.id, PeriodAnchors.unmarkedSalary(rows)?.id)
        assertEquals(null, PeriodAnchors.unmarkedSalary(rows, dismissed = setOf(imported.id)))
        assertEquals(null, PeriodAnchors.unmarkedSalary(rows.map { if (it.id == imported.id) PeriodAnchors.mark(it, true) else it }))
        assertEquals(null, PeriodAnchors.unmarkedSalary(listOf(imported)), "nothing marked yet: nothing to compare with")
    }

    @Test
    fun `a second salary near the first is shown, and replaces it when marked`() {
        val mine = salary(d(8, 28))
        val theirs = Fixtures.income(100_000, d(8, 28)).copy(accountId = Fixtures.CASH)
        val all = listOf(mine, theirs)
        assertEquals(mine.id, PeriodAnchors.otherSalaryNear(theirs, all)?.id)
        assertEquals(null, PeriodAnchors.otherSalaryNear(mine, all), "not itself")
        assertEquals(null, PeriodAnchors.otherSalaryNear(theirs.copy(date = d(9, 20)), all), "far enough: another month")
        assertFalse(PeriodAnchors.worthOffering(theirs, all), "another account: kept in the details")
        assertTrue(PeriodAnchors.worthOffering(mine, all))
        assertTrue(PeriodAnchors.worthOffering(Fixtures.income(1, d(9, 28)), all), "like the last salary")
        assertTrue(PeriodAnchors.worthOffering(theirs, listOf(theirs)), "nothing marked yet")
    }

    @Test
    fun `the days of a period follow the salaries`() {
        val rule = PeriodRule(28, anchors = listOf(d(8, 28), d(9, 26), d(11, 1)))
        assertEquals(d(8, 28)..d(9, 25), rule.daysOf(YearMonth.of(2030, 9)))
        assertEquals(d(9, 26)..d(10, 31), rule.daysOf(YearMonth.of(2030, 10)))
        assertEquals(d(11, 1)..d(11, 30), rule.daysOf(YearMonth.of(2030, 11)), "still open: a month for now")
        assertEquals(d(12, 1)..d(12, 31), rule.daysOf(YearMonth.of(2030, 12)), "not started yet: guessed")
        assertEquals(d(7, 28)..d(8, 27), rule.daysOf(YearMonth.of(2030, 8)), "before the first salary: the fixed day")
        assertEquals(d(6, 28)..d(7, 27), rule.daysOf(YearMonth.of(2030, 7)))
        assertEquals(d(11, 1), rule.lastStart)
        assertTrue(rule.isOpen(YearMonth.of(2030, 11)))
        assertFalse(rule.isOpen(YearMonth.of(2030, 10)))
        assertTrue(rule.anchored)
        assertFalse(PeriodRule(28).anchored)
        assertEquals(null, rule.daysWaitingForSalary(d(12, 6)))
        assertEquals(36, rule.daysWaitingForSalary(d(12, 7)))
        assertEquals(null, PeriodRule(28).daysWaitingForSalary(d(12, 7)))
    }

    @Test
    fun `every day belongs to the period whose days contain it`() {
        val rule = PeriodRule(28, anchors = listOf(d(3, 26), d(5, 1), d(5, 29), d(7, 14), d(9, 30)))
        var day = d(1, 1)
        while (day < d(12, 31)) {
            val p = rule.periodOf(day, TransactionKind.EXPENSE, Recurrence.VARIABLE)
            assertTrue(day in rule.daysOf(p) || p == YearMonth.of(2030, 10), "$day in $p ${rule.daysOf(p)}")
            day = day.plusDays(1)
        }
    }
}
