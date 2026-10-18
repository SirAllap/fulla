// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.analytics.DailyBasis
import io.github.sirallap.fulla.core.analytics.DayMark
import io.github.sirallap.fulla.core.analytics.SpendingCalendar
import io.github.sirallap.fulla.core.design.CalendarColors
import io.github.sirallap.fulla.ui.HouseholdView
import io.github.sirallap.fulla.ui.components.CardGroup
import io.github.sirallap.fulla.ui.components.Section
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The period day by day: a square per day, green when it spent less than there was per day, orange when more
 * (Analytics.calendar). Weeks start on Monday; the line under it says what "per day" is.
 */
@Composable
fun CalendarSection(view: HouseholdView, calendar: SpendingCalendar, today: LocalDate) {
    val c = FullaTheme.colors
    val f = view.formats
    val summary = if (calendar.dailyMinor != null) stringResource(R.string.calendar_summary, calendar.under, calendar.over) else null
    val trailing: (@Composable RowScope.() -> Unit)? = if (summary == null) null else {
        { Text(summary, style = FullaType.label, color = c.inkMuted, textAlign = TextAlign.End) }
    }
    Section(stringResource(R.string.calendar_title), trailing = trailing)
    CardGroup {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in DayOfWeek.values()) {
                    Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = FullaType.label, color = c.inkMuted,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            val days = calendar.days
            val lead = days.first().date.dayOfWeek.value - 1
            val cells: List<io.github.sirallap.fulla.core.analytics.CalendarDay?> = List(lead) { null } + days
            for (week in cells.chunked(7)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0 until 7) {
                        val day = week.getOrNull(i)
                        if (day == null) {
                            Spacer(Modifier.weight(1f))
                            continue
                        }
                        val fill = when (day.mark) {
                            DayMark.UNDER -> Color(CalendarColors.under(c.isDark))
                            DayMark.OVER -> Color(CalendarColors.over(c.isDark))
                            DayMark.TO_COME, DayMark.NONE -> Color.Transparent
                        }
                        val what = when (day.mark) {
                            DayMark.UNDER -> stringResource(R.string.calendar_day_under)
                            DayMark.OVER -> stringResource(R.string.calendar_day_over)
                            else -> null
                        }
                        val description = listOfNotNull(f.day(day.date), f.money(day.spentMinor), what).joinToString(", ")
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(fill)
                                .then(if (day.date == today) Modifier.border(2.dp, c.highlight, RoundedCornerShape(10.dp)) else Modifier)
                                .semantics { contentDescription = description },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(day.date.dayOfMonth.toString(), style = FullaType.label,
                                color = if (day.mark == DayMark.TO_COME || day.mark == DayMark.NONE) c.inkMuted else c.ink)
                        }
                    }
                }
            }
            val legend = when (calendar.basis) {
                DailyBasis.AFTER_FIXED -> stringResource(R.string.calendar_basis_after_fixed, f.money(calendar.dailyMinor ?: 0))
                DailyBasis.USUAL -> stringResource(R.string.calendar_basis_usual, f.money(calendar.dailyMinor ?: 0))
                null -> stringResource(R.string.calendar_no_basis)
            }
            Text(legend, style = FullaType.secondary, color = c.inkMuted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
