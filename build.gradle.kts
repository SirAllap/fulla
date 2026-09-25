// SPDX-License-Identifier: GPL-3.0-or-later
//
// Intentionally empty. Plugins are declared, with versions, in each module.
// Loading the Kotlin plugin here would hide the Android plugin from `:app`,
// and loading the Android plugin here would make `:core:test` need Google's
// Maven repository. Gradle's warning about the Kotlin plugin being loaded in
// several projects is expected.
