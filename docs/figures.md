# The figures

Every number Fulla shows, what it means, which rows it counts and where it is worked out. All of
it is `core` (the Android app and the web app only draw it), and each line has a test; the ones
that exist in the database too pass the same vectors (`testdata/vectors/`). If you add a figure,
add it here and give it an identity in `FiguresAddUpTest`: a figure that cannot be added up
against another one is a figure nobody can check.

## What counts

A row counts when it is **active** (not deleted) and **not refused by the server**
(`SyncEngine.counted`): a row the household does not hold is not in its totals, so every phone
adds up the same. Rows waiting to be sent count (they are on their way). Refused ones are kept
and shown in History › Not sent, and the overview says how many there are.

Only income, expenses and refunds are money in or out. **Transfers** move money between the
household's own accounts and **settlements** between people: neither is income or spending.
**Spending** is expenses minus refunds. A **period** is the one the household chose (calendar
month, a start day, fixed income shifted, or the months its marked salaries start:
`PeriodRule`). A row counts in the period its date falls in, a refund in the period of the
refund. A row written down with a **future date** is in its period's totals (it is written
down) but is never "spent so far" and never a pace (`PeriodForecast.bookedAheadMinor`).

## Overview

| Figure | Is | Source |
| --- | --- | --- |
| The jar, "In", "Out" | income and spending of the period, drawn to scale; the figure in the jar is income − spending | `Analytics.summary`, `Hero` |
| "Left" / "Saved" % | (income − spending) / income, rounded, "—" with no income. **Left** while the period runs, **Saved** once it is over | `PeriodSummary.savingsRate` |
| "At the end of the period" | what would be kept: income and income still due, less what was spent, less the fixed costs still to come, less the everyday spending expected (a range). Without the estimate (too early, or no income), what would be left if nothing more is spent | `Analytics.forecast`, `PeriodForecast` |
| Accounts total | opening balances plus everything since each opening date up to today, archived accounts out | `Analytics.accountsTotal` |
| Budget of the period | what the budgeted categories used (subcategories included, trips with a jar of their own out) against what they allow; left or over, never a negative left | `Budgets.status` |
| Trip | spent, left or over the budget | `Trips.totals`, `Trips.forHome` |
| Fixed costs | each charge due in the period: charged (written by the recurring item, or by hand and standing for it) or still to come; skipped ones are not waited for | `Analytics.forecast`, `Coverage` |
| Where it went | spending per category, subcategories rolled up; "usually" is the average of the up to three earlier periods that have anything written down | `Analytics.byCategory` |

## Analysis

| Figure | Is | Source |
| --- | --- | --- |
| The sums | income − spent so far − fixed to come = what would be left if nothing more is spent (exact); less the everyday spending expected = what would be kept (a range) | `PeriodForecast` |
| Everyday spending expected | from the earlier periods that had everyday spending (their median pace from this day on, adjusted to how this one goes); with none, this period's own pace from day 7 with five expenses (only what is marked variable, one big purchase counts for at most five usual rows, a wider range) | `Analytics.forecast`; backtested in `ForecastTest` |
| Spent | spending of the period; while it runs, compared with the same days of the period before | `PeriodReport.previousSpentMinor` |
| Left / Saved % | as on the overview | `PeriodReport.savingsRate` |
| Daily average | everyday (variable) spending over the days gone, today included; the fixed costs are their own figure | `PeriodReport.dailyMinor` |
| Expenses | how many expenses (refunds apart) and their average | `PeriodReport` |
| Fixed and variable | split by the Fixed / Variable mark on each row | `PeriodReport.fixedMinor` |
| Days without spending | days that are over (today is not, until it ends) with no variable expense | `Analytics.noSpendDays` |
| Budgets over | budgets whose category used more than its limit | `PeriodReport.budgetsOver` |
| You can spend per day | what is left (income − spent − fixed to come) over the days to go, today included | `PeriodForecast.perDayMinor` |
| Where it went, which day | the five largest categories, and variable spending by weekday; whole percentages that add up to exactly 100 | `Analytics.topCategories`, `Percent.split` |
| Against the usual | categories at least 20 % and 10 units away from the average of the earlier periods; while the period runs, the same days of those periods | `Analytics.trends` |
| Who paid | what each member paid and their share, refunds taken off | `Analytics.byMember` |
| Period by period | income, spending and what is left of the last periods; the one running says so | `Analytics.series` |
| Looks recurring | the same note in three of the last six months for about the same amount, and not already a recurring item | `Analytics.detectedRecurring` |

## Balances

| Figure | Is | Source |
| --- | --- | --- |
| Between you | what each member paid minus their share, settlements included; always adds up to zero | `Balances` (also `fulla.member_balances`) |
| To settle | the fewest payments that bring every balance to zero | `SettlementPlanner` |
| Accounts | as on the overview | `Analytics.accountBalances` |

## Writing the same thing twice

Money reads the same on every platform (`MoneyFormatterTest`: a four-digit amount is grouped on a
phone and in a browser alike). The day moves on by itself at midnight on every screen that works
from it. Percentages are rounded the same everywhere, and those of one chart add up to 100.
