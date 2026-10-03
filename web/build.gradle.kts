// SPDX-License-Identifier: GPL-3.0-or-later
//
// Fulla in a browser: a progressive web app, installable on an iPhone's home
// screen. Kotlin/JS on top of core and client, so every rule, every sum and
// the backup format are the very ones the Android app runs. It keeps its
// household in the browser's own storage; it decides nothing a test in core
// or client could hold instead.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Not used here: asking for the same plugins as core and client lets Gradle load the Kotlin plugin once.
    alias(libs.plugins.kover)
}

// The strings come from the Android resources: same words, same six languages.
val generatedStrings = layout.buildDirectory.dir("generated/strings")
val generateStrings by tasks.registering(Exec::class) {
    inputs.dir("src/jsMain/kotlin")
    inputs.dir(rootProject.file("app/src/main/res"))
    inputs.file(rootProject.file("tools/web/strings.mjs"))
    outputs.dir(generatedStrings)
    workingDir = rootProject.projectDir
    commandLine("node", "tools/web/strings.mjs", "web/src/jsMain/kotlin", generatedStrings.get().file("GeneratedStrings.kt").asFile.absolutePath)
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "fulla.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain {
            kotlin.srcDir(generatedStrings)
            dependencies {
                implementation(project(":client"))
                implementation(libs.ktor.client.js)
            }
        }
    }
}

tasks.matching { it.name.startsWith("compileKotlinJs") || it.name.endsWith("SourcesJar") || it.name == "compileProductionExecutableKotlinJs" || it.name == "compileDevelopmentExecutableKotlinJs" }.configureEach {
    dependsOn(generateStrings)
}
