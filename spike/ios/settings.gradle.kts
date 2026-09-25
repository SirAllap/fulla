// SPDX-License-Identifier: GPL-3.0-or-later
//
// Self-contained spike: proves a GitHub Actions macOS runner can build an
// unsigned .ipa from a Compose Multiplatform iOS app. Deliberately outside
// the root build (settings.gradle.kts at the repo root only includes
// :core, :client, :app) so it never touches the real Kotlin/Android build.
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fulla-ios-spike"

include(":shared")
