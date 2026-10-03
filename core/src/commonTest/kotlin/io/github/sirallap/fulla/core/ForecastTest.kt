// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.analytics.Analytics
import io.github.sirallap.fulla.core.analytics.FixedStatus
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Transaction
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.recurring.DeterministicId
import io.github.sirallap.fulla.core.recurring.Frequency
import io.github.sirallap.fulla.core.recurring.RecurringRule
import io.github.sirallap.fulla.core.recurring.Schedule
import io.github.sirallap.fulla.core.rules.PeriodRule
import io.github.sirallap.fulla.core.time.LocalDate
import io.github.sirallap.fulla.core.time.YearMonth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a period is likely to end: exact where the answer is known (what was
 * spent, the fixed costs that write themselves), learned from the previous
 * periods where it is not (everyday spending).
 */
class ForecastTest {
    private val jan = YearMonth.of(2030, 1)
    private fun d(day: Int, month: Int = 1, year: Int = 2030) = LocalDate.of(year, month, day)

    private fun rule(n: Int, name: String, amount: Long, day: Int, category: String, start: LocalDate = d(1, 6, 2029),
                     kind: TransactionKind = TransactionKind.EXPENSE, auto: Boolean = true) = RecurringRule(
        id = "00000000-0000-4000-8000-0000000004" + n.toString().padStart(2, '0'), name = name,
        template = Fixtures.expense(amount, start, category = category).copy(kind = kind, recurrence = Recurrence.FIXED),
        schedule = Schedule(Frequency.MONTHLY, byMonthDay = day), startDate = start, autoCreate = auto,
    )

    /** What the app writes on the day: the deterministic id, the rule's id. */
    private fun written(r: RecurringRule, date: LocalDate, amount: Long = r.template.amountMinor): Transaction =
        r.template.copy(id = DeterministicId.occurrence(r.id, date), date = date, amountMinor = amount, recurringRuleId = r.id, occurrenceDate = date)

    private val rent = rule(1, "Rent", 80_000, 1, Fixtures.GROCERIES)
    private val car = rule(2, "Car", 35_000, 7, Fixtures.LEISURE, start = d(1, 1))
    private val bike = rule(3, "Bike", 12_000, 14, Fixtures.SNACKS)
    private val gym = rule(4, "Gym", 3_000, 20, Fixtures.SNACKS)
    private val pay = rule(5, "Pay", 300_000, 25, Fixtures.SALARY, kind = TransactionKind.INCOME)

    private fun analytics(vararg rules: RecurringRule, periodRule: PeriodRule = PeriodRule()) =
        Analytics(Fixtures.config().copy(recurringRules = rules.toList()), periodRule)

    private fun plain(amount: Long, date: LocalDate, category: String = Fixtures.GROCERIES) = Fixtures.expense(amount, date, category = category)

    @Test
    fun the_fixed_costs_still_to_come_are_read_off_the_recurring_items_exactly() {
        val a = analytics(rent, car, bike, gym)
        val rows = listOf(written(rent, d(1)), written(car, d(7), amount = 36_000)) // the car went up a little
        val f = a.forecast(rows, jan, today = d(10), deleted = setOf(DeterministicId.occurrence(gym.id, d(20))))!!
        assertEquals(listOf("Rent", "Car", "Bike", "Gym"), f.fixed.map { it.name })
        assertEquals(listOf(FixedStatus.PAID, FixedStatus.PAID, FixedStatus.PENDING, FixedStatus.SKIPPED), f.fixed.map { it.status })
        assertEquals(80_000 + 36_000, f.fixedPaidMinor, "what was really charged")
        assertEquals(12_000, f.fixedToComeMinor)
        assertEquals(128_000, f.fixedTotalMinor, "a skipped one is not counted")
        assertEquals(10, f.day)
        assertEquals(31, f.length)
    }

    @Test
    fun a_new_recurring_item_counts_from_the_day_it_is_created_even_for_a_period_it_has_not_touched_yet() {
        val f = analytics(car).forecast(emptyList(), jan, today = d(3))!!
        assertEquals(35_000, f.fixedToComeMinor)
        assertEquals(d(7), f.fixed.single().date)
    }

    @Test
    fun recurring_items_that_do_not_write_themselves_are_not_waited_for() {
        val f = analytics(rule(9, "Maybe", 5_000, 12, Fixtures.LEISURE, auto = false), car.copy(active = false)).forecast(emptyList(), jan, today = d(3))!!
        assertEquals(emptyList(), f.fixed)
    }

    @Test
    fun a_charge_written_by_hand_stands_for_the_occurrence_paid_and_not_charged_twice() {
        val a = analytics(rent)
        val mine = plain(80_000, d(2), category = Fixtures.GROCERIES) // the rent, typed in a day late
        val f = a.forecast(listOf(mine), jan, today = d(10))!!
        val item = f.fixed.single()
        assertEquals(FixedStatus.PAID, item.status)
        assertTrue(item.byHand)
        assertEquals(false, item.overdue)
        assertEquals(0, f.fixedToComeMinor, "it is on the books already")
        assertEquals(80_000, f.fixedPaidMinor)
        assertEquals(80_000, f.spentMinor, "and counted once")
        // Another category is another payment: the rent is still to come.
        val other = a.forecast(listOf(plain(80_000, d(2), category = Fixtures.LEISURE)), jan, today = d(10))!!
        assertEquals(FixedStatus.PENDING, other.fixed.single().status)
        assertEquals(80_000, other.fixedToComeMinor)
    }

    @Test
    fun a_charge_whose_day_has_come_and_that_nothing_has_written_is_overdue_one_that_has_not_come_is_not() {
        val f = analytics(rent, car).forecast(emptyList(), jan, today = d(5))!!
        assertEquals(listOf(true, false), f.fixed.map { it.overdue })
        assertEquals(listOf(FixedStatus.PENDING, FixedStatus.PENDING), f.fixed.map { it.status })
        assertEquals(115_000, f.fixedToComeMinor, "overdue or not, it is still to be charged")
        // Written, it is neither.
        val written = analytics(rent, car).forecast(listOf(written(rent, d(1))), jan, today = d(5))!!
        assertEquals(listOf(FixedStatus.PAID, FixedStatus.PENDING), written.fixed.map { it.status })
        assertEquals(listOf(false, false), written.fixed.map { it.overdue })
        // A charge somebody deleted is skipped, whatever is near it.
        val skipped = analytics(rent).forecast(listOf(plain(80_000, d(2))), jan, today = d(5), deleted = setOf(DeterministicId.occurrence(rent.id, d(1))))!!
        assertEquals(FixedStatus.SKIPPED, skipped.fixed.single().status)
    }

    @Test
    fun a_financing_says_which_instalment_each_charge_is_and_a_fixed_cost_that_never_ends_says_nothing() {
        val sofa = rent.copy(id = "00000000-0000-4000-8000-000000000477", name = "Sofa", startDate = d(1, 11, 2029), endDate = d(1, 3))
        val f = analytics(sofa, car).forecast(listOf(written(sofa, d(1))), jan, today = d(5))!!
        val s = f.fixed.first { it.name == "Sofa" }
        assertEquals(3 to 5, s.installment!! to s.installments!!, "Nov, Dec, Jan, Feb, Mar: January is the third of five")
        assertNull(f.fixed.first { it.name == "Car" }.installment)
    }

    @Test
    fun a_charge_s_bar_fills_as_its_day_comes_closer_over_the_period_and_is_full_once_paid() {
        val f = analytics(rent, bike, gym).forecast(listOf(written(rent, d(1))), jan, today = d(2))!!
        val by = f.fixed.associateBy { it.name }
        assertEquals(1f, by.getValue("Rent").countdown(d(2), f.length), "paid")
        // Bike on the 14th, today the 2nd: 12 days left of 31.
        assertEquals((31 - 12) / 31f, by.getValue("Bike").countdown(d(2), f.length), 0.0001f)
        // Gym on the 20th: further away, emptier.
        assertTrue(by.getValue("Gym").countdown(d(2), f.length) < by.getValue("Bike").countdown(d(2), f.length))
        // The day itself, and after it with nothing written: full.
        assertEquals(1f, by.getValue("Bike").countdown(d(14), f.length))
        assertEquals(1f, by.getValue("Bike").countdown(d(20), f.length))
        assertEquals(0f, by.getValue("Bike").copy(status = FixedStatus.SKIPPED).countdown(d(2), f.length))
    }

    @Test
    fun income_somebody_wrote_down_by_hand_is_not_expected_a_second_time() {
        val a = analytics(pay)
        assertEquals(300_000, a.forecast(emptyList(), jan, today = d(10))!!.expectedIncomeMinor)
        val mine = Fixtures.income(300_000, d(24)).copy(categoryId = Fixtures.SALARY)
        assertEquals(0, a.forecast(listOf(mine), jan, today = d(26))!!.expectedIncomeMinor)
    }

    @Test
    fun without_an_earlier_period_the_everyday_part_is_left_out_and_the_fixed_part_stays_exact() {
        val f = analytics(car).forecast(listOf(plain(4_000, d(2))), jan, today = d(3))!!
        assertNull(f.everydayRestMinor)
        assertNull(f.spentEndMinor)
        assertNull(f.keptMinor)
        assertEquals(35_000, f.fixedToComeMinor)
        assertEquals(4_000, f.spentMinor)
    }

    private fun history(perMonth: (YearMonth) -> List<Transaction>) =
        (1..3).flatMap { perMonth(jan.minusMonths(it.toLong())) }

    @Test
    fun the_everyday_part_is_what_earlier_periods_spent_from_this_day_on_with_a_range() {
        // Each earlier period spent 100 a day after day 10; this one is on its usual pace.
        val rows = history { m -> listOf(plain(1_000, m.atDay(5)), plain(if (m.lengthOfMonth() == 30) 2_000 else 2_100, m.atDay(20))) } +
            plain(1_000, d(5))
        val f = analytics().forecast(rows, jan, today = d(10))!!
        assertEquals(2_100, f.everydayRestMinor)
        assertEquals(1_260.0, f.everydayLowMinor!!.toDouble(), 1.0)
        assertEquals(3_360.0, f.everydayHighMinor!!.toDouble(), 1.0)
        assertEquals(3_100, f.spentEndMinor, "what was spent, plus the rest")
        assertTrue(f.spentEndLowMinor!! < f.spentEndMinor!! && f.spentEndMinor!! < f.spentEndHighMinor!!)
    }

    @Test
    fun a_period_going_faster_than_usual_pushes_the_estimate_up_gently_at_first() {
        val usual = history { m -> listOf(plain(1_000, m.atDay(5)), plain(if (m.lengthOfMonth() == 30) 2_000 else 2_100, m.atDay(20))) }
        val double = analytics().forecast(usual + plain(2_000, d(5)), jan, today = d(10))!!
        assertEquals(3_150, double.everydayRestMinor, "twice the usual, weighed by how many days have gone: 1 + 10/20")
        val early = analytics().forecast(usual + plain(2_000, d(1)), jan, today = d(2))!!
        assertTrue(early.everydayRestMinor!! < double.everydayRestMinor!! + 2_000, "one day says little")
    }

    @Test
    fun recurring_items_trips_and_things_like_a_recurring_item_are_not_everyday_spending() {
        val a = analytics(car)
        val noise = history { m ->
            listOf(
                plain(1_000, m.atDay(5)), plain(if (m.lengthOfMonth() == 30) 2_000 else 2_100, m.atDay(20)),
                written(car, m.atDay(20)),                                              // written by the recurring item
                plain(30_000, m.atDay(21)).copy(tripId = "trip"),                       // a trip
                plain(35_100, m.atDay(22), category = Fixtures.LEISURE),                // typed by hand before the rule existed
            )
        }
        val f = a.forecast(noise + plain(1_000, d(5)), jan, today = d(10))!!
        assertEquals(2_100, f.everydayRestMinor)
    }

    @Test
    fun one_odd_period_barely_moves_the_estimate() {
        val normal = { m: YearMonth -> listOf(plain(1_000, m.atDay(5)), plain(if (m.lengthOfMonth() == 30) 2_000 else 2_100, m.atDay(20))) }
        val rows = normal(jan.minusMonths(1)) + normal(jan.minusMonths(3)) +
            listOf(plain(4_000, jan.minusMonths(2).atDay(5)), plain(8_400, jan.minusMonths(2).atDay(20))) + plain(1_000, d(5))
        assertEquals(2_100, analytics().forecast(rows, jan, today = d(10))!!.everydayRestMinor)
    }

    @Test
    fun nothing_to_forecast_before_the_period_starts() {
        assertNull(analytics().forecast(emptyList(), jan, today = d(31, 12, 2029)))
        val open = Analytics(Fixtures.config(), PeriodRule(anchors = listOf(d(1))))
        assertNotNull(open.forecast(emptyList(), jan, today = d(20)))
    }

    @Test
    fun an_open_period_past_its_length_still_lists_the_fixed_costs_and_forecasts_nothing() {
        val open = analytics(rent, car, pay, periodRule = PeriodRule(anchors = listOf(d(1))))
        val rows = listOf(written(rent, d(1)), written(car, d(7)), plain(5_000, d(10)))
        // Jan 1 opens the period; on Feb 5 no salary has closed it yet.
        val f = open.forecast(rows, jan, today = d(5, 2))!!
        assertTrue(f.waiting)
        assertEquals(false, f.known)
        assertEquals(null, f.spentEndMinor)
        assertEquals(null, f.perDayMinor)
        // Charges that fell due after the usual end and before today are listed too: the Feb 1 rent, not written yet.
        assertEquals(listOf(FixedStatus.PAID, FixedStatus.PAID, FixedStatus.PENDING), f.fixed.map { it.status })
        assertEquals(d(1, 2), f.fixed.last().date)
        assertEquals(195_000, f.fixedTotalMinor)
        assertEquals(false, open.forecast(rows, jan, today = d(20))!!.waiting)
    }

    @Test
    fun what_is_left_after_the_fixed_costs_and_per_day() {
        val a = analytics(rent, car, pay)
        val rows = listOf(written(rent, d(1)), plain(5_000, d(4)))
        val f = a.forecast(rows, jan, today = d(10))!!
        assertEquals(300_000, f.expectedIncomeMinor, "the salary due on the 25th")
        assertEquals(300_000, f.totalIncomeMinor)
        assertEquals(85_000, f.spentMinor)
        assertEquals(35_000, f.fixedToComeMinor)
        assertEquals(300_000 - 115_000, f.afterFixedMinor)
        assertEquals((300_000 - 85_000 - 35_000) / 21, f.perDayMinor)
        assertEquals(300_000 - 85_000 - 35_000, f.leftToSpendMinor, "income, less what was spent, less the fixed costs still to come")
        assertEquals(f.leftToSpendMinor!! / 21, f.perDayMinor, "and the per day is that over the days left")
        assertTrue(f.afterFixedMinor!! > f.leftToSpendMinor!!, "after the fixed costs alone it is more: it does not count what was spent")
        assertTrue(!f.early, "the 10th of 31 days is not early")
        assertTrue(analytics(rent).forecast(emptyList(), jan, today = d(5))!!.early, "the 5th is")
        assertNull(analytics(rent).forecast(rows, jan, today = d(10))!!.afterFixedMinor, "no income known")
        assertNull(analytics(rent).forecast(rows, jan, today = d(10))!!.perDayMinor)
        val written25 = a.forecast(rows + written(pay, d(25)), jan, today = d(26))!!
        assertEquals(0, written25.expectedIncomeMinor, "already written")
    }

    // A month of a made-up household, from a seeded generator: four fixed costs, groceries every two or three
    // days, small purchases, weekend meals and, some months, one big purchase.
    private class Household(val seed: Long) {
        val rnd = Random(seed)
        private fun Random.nextGaussian(): Double = sqrt(-2 * ln(1 - nextDouble())) * cos(2 * PI * nextDouble())
        fun month(m: YearMonth, rules: List<RecurringRule>, write: (RecurringRule, LocalDate) -> Transaction, plain: (Long, LocalDate) -> Transaction): List<Transaction> {
            val out = mutableListOf<Transaction>()
            for (r in rules) out += write(r, m.atDay(r.schedule.byMonthDay!!))
            var day = 1 + rnd.nextInt(3)
            while (day <= m.lengthOfMonth()) { out += plain(maxOf(500L, (3_500 + rnd.nextGaussian() * 1_200).toLong()), m.atDay(day)); day += 2 + rnd.nextInt(2) }
            for (day2 in 1..m.lengthOfMonth()) {
                if (rnd.nextDouble() < 0.5) out += plain(maxOf(100L, (600 + rnd.nextGaussian() * 300).toLong()), m.atDay(day2))
                if (m.atDay(day2).dayOfWeek.value >= 5 && rnd.nextDouble() < 0.3) out += plain(maxOf(500L, (4_000 + rnd.nextGaussian() * 1_500).toLong()), m.atDay(day2))
            }
            if (rnd.nextDouble() < 0.35) out += plain(maxOf(3_000L, (25_000 + rnd.nextGaussian() * 10_000).toLong()), m.atDay(1 + rnd.nextInt(m.lengthOfMonth())))
            return out
        }
    }

    @Test
    fun backtest_across_many_made_up_periods_the_estimate_lands_close_and_the_range_holds_about_four_in_five() {
        val rules = listOf(rent, car.copy(startDate = d(1, 6, 2029)), bike, gym)
        val a = analytics(*rules.toTypedArray())
        val day = 10
        val errors = mutableListOf<Double>()
        var inside = 0
        val runs = 150
        for (seed in 1..runs) {
            val h = Household(seed.toLong())
            val all = (4 downTo 0).flatMap { back ->
                val m = jan.minusMonths(back.toLong())
                h.month(m, rules, { r, date -> written(r, date) }, { amount, date -> plain(amount, date) })
                    .map { it.copy(id = it.id + "-$seed-$back") }.map { t -> if (t.recurringRuleId != null) t.copy(id = DeterministicId.occurrence(t.recurringRuleId!!, t.date)) else t }
            }
            val actual = a.forecast(all, jan, today = d(31))!!.spentMinor
            val asOf = all.filter { it.date <= d(day) }
            val f = a.forecast(asOf, jan, today = d(day))!!
            errors += abs(f.spentEndMinor!! - actual).toDouble() / actual
            if (actual in f.spentEndLowMinor!!..f.spentEndHighMinor!!) inside++
        }
        val median = errors.sorted()[errors.size / 2]
        assertTrue(median < 0.10, "the median error at day $day was ${(kotlin.math.round(median * 1000) / 10)} %")
        val coverage = inside.toDouble() / runs
        assertTrue(coverage in 0.70..0.99, "the range held ${kotlin.math.round(coverage * 100).toInt()} % of the time")
    }
}
