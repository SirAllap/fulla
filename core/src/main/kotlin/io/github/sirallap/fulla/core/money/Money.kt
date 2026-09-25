// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.money

/**
 * An amount of money: a whole number of the currency's minor unit.
 *
 * Never a Double. Binary floating point cannot represent most decimal amounts
 * exactly, and a ledger that adds up a year of small purchases drifts by a
 * unit here and there. Arithmetic is only defined between amounts of the same
 * currency.
 */
data class Money(val minor: Long, val currency: Currency) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(Math.addExact(minor, same(other).minor), currency)
    operator fun minus(other: Money): Money = Money(Math.subtractExact(minor, same(other).minor), currency)
    operator fun unaryMinus(): Money = Money(Math.negateExact(minor), currency)
    operator fun times(factor: Long): Money = Money(Math.multiplyExact(minor, factor), currency)

    override fun compareTo(other: Money): Int = minor.compareTo(same(other).minor)

    val isZero: Boolean get() = minor == 0L
    val isPositive: Boolean get() = minor > 0L
    val isNegative: Boolean get() = minor < 0L
    fun abs(): Money = if (minor < 0) -this else this

    private fun same(other: Money): Money {
        require(other.currency == currency) { "Cannot combine $currency and ${other.currency}" }
        return other
    }

    companion object {
        fun zero(currency: Currency) = Money(0, currency)
    }
}

fun Iterable<Money>.sum(currency: Currency): Money = fold(Money.zero(currency)) { acc, m -> acc + m }
