// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * Today's date, and it changes by itself when midnight passes while a screen stays open (or the phone sleeps through
 * it): every figure that depends on the day (day 10 of 30, what is left per day, the period itself) is worked out again
 * instead of showing yesterday's. Used as a key by the screens that compute from the date.
 */
@Composable
fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            val now = LocalDate.now()
            if (now != today) today = now
        }
    }
    return today
}
