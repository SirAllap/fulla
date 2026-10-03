// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

/** A file of the repository (testdata/…), as text: the vectors every platform must pass. */
expect fun readRepoFile(path: String): String
