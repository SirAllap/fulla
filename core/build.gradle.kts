// SPDX-License-Identifier: GPL-3.0-or-later
//
// Pure Kotlin on the JVM: every rule, decision and calculation, with no
// Android dependency, so it compiles and tests on any machine with a JDK.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
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
