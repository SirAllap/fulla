// SPDX-License-Identifier: GPL-3.0-or-later
//
// Every rule, decision and calculation, with no Android dependency. Plain
// Kotlin, common to the JVM (the Android app, the tests) and to JavaScript
// (the web app), so a rule is written once and means the same on every phone
// and in every browser. Tests run on the JVM with the shared vectors on disk;
// the rules that matter to the browser also run as JavaScript.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    js(IR) {
        browser()
        nodejs {
            // The simulations take seconds, not milliseconds.
            testTask { useMocha { timeout = "180s" } }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    environment("FULLA_ROOT", rootProject.projectDir.absolutePath)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The shared vectors and defaults live at the repository root.
    systemProperty("fulla.root", rootProject.projectDir.absolutePath)
    testLogging { events("failed") }
}

kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

// The browser tests run on the Node already on the machine (the settings
// forbid the plugin's own download repository) and on npm, not yarn.
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().download = false
}
