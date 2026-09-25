// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.home

import io.github.sirallap.fulla.core.model.Config
import java.time.YearMonth

object Budgets {
    /** The budget of each category for [period]: a budget set for that period wins over the default. */
    fun forPeriod(config: Config, period: YearMonth): Map<String, Long> {
        val key = period.toString()
        val defaults = config.budgets.filter { it.period == null }.associate { it.categoryId to it.amountMinor }
        val overrides = config.budgets.filter { it.period == key }.associate { it.categoryId to it.amountMinor }
        return (defaults + overrides).filterValues { it > 0 }
    }
}
