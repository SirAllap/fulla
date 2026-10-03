// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import java.io.File

actual fun readRepoFile(path: String): String = File(System.getProperty("fulla.root") ?: "..", path).readText()
