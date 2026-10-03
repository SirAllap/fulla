// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.platform

import java.security.SecureRandom

actual object Random {
    private val secure = SecureRandom()
    actual fun bytes(count: Int): ByteArray = ByteArray(count).also(secure::nextBytes)
}
