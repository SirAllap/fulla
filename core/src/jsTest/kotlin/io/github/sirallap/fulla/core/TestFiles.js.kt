// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

actual fun readRepoFile(path: String): String {
    val root = js("process.env.FULLA_ROOT") as? String ?: ".."
    return js("require('fs')").readFileSync("$root/$path", "utf8") as String
}
