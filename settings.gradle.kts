// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fulla"

include(":core", ":client")

// The Android app joins the build only where an Android SDK exists. Declaring
// the Android plugins unconditionally makes Gradle resolve them at
// configuration time, which breaks `:core:test` on a machine that only wants
// the domain tests.
val androidSdk = providers.environmentVariable("ANDROID_HOME").orNull
    ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
    ?: file("local.properties").takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("=")

if (androidSdk != null && file("app/build.gradle.kts").exists()) {
    include(":app")
}
