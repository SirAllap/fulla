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
    inputs.dir(rootProject.file("web/strings"))
    inputs.file(rootProject.file("tools/web/strings.mjs"))
    outputs.dir(generatedStrings)
    workingDir = rootProject.projectDir
    commandLine("node", "tools/web/strings.mjs", "web/src/jsMain/kotlin", generatedStrings.get().file("GeneratedStrings.kt").asFile.absolutePath)
}

// The project the Android build is made for (CI secrets, never written in the repository): with them set, people
// only sign in, exactly as in the app. Without them, the page asks for a project of their own.
val generatedHosted = layout.buildDirectory.dir("generated/hosted")
val generateHosted by tasks.registering {
    val url = providers.environmentVariable("FULLA_PROJECT_URL").orElse("")
    val key = providers.environmentVariable("FULLA_ANON_KEY").orElse("")
    inputs.property("url", url)
    inputs.property("key", key)
    outputs.dir(generatedHosted)
    doLast {
        fun lit(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val file = generatedHosted.get().file("HostedConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package io.github.sirallap.fulla.web\n\ninternal object HostedConfig {\n    const val URL = ${lit(url.get())}\n    const val KEY = ${lit(key.get())}\n}\n")
    }
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
            kotlin.srcDir(generatedHosted)
            dependencies {
                implementation(project(":client"))
                implementation(libs.ktor.client.js)
                // The invite's QR code (MIT).
                implementation(npm("qrcode-generator", "1.4.4"))
            }
        }
    }
}

tasks.matching { it.name.startsWith("compileKotlinJs") || it.name.endsWith("SourcesJar") || it.name == "compileProductionExecutableKotlinJs" || it.name == "compileDevelopmentExecutableKotlinJs" }.configureEach {
    dependsOn(generateStrings, generateHosted)
}
