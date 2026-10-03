// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.time

import kotlin.js.Date

internal actual object Clock {
    actual fun nowMillis(): Long = Date.now().toLong()
    actual fun today(): LocalDate = Date().let { LocalDate.of(it.getFullYear(), it.getMonth() + 1, it.getDate()) }
}
