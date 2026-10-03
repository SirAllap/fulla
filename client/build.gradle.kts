// SPDX-License-Identifier: GPL-3.0-or-later
//
// The phone's side of the protocol, in plain Kotlin shared by the JVM and
// JavaScript: the wire format, the Supabase transport and the sync loop. The
// Android app and the web app each provide storage and call it. It lives
// outside the apps so that it compiles and is tested on any machine with a
// JDK, like core.
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
        commonMain.dependencies {
            api(project(":core"))
            api(libs.ktor.client.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.zxing.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            implementation(libs.zxing.core)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    environment("FULLA_ROOT", rootProject.projectDir.absolutePath)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed") }
    // For SchemaVersionTest, which reads the migration SQL straight off disk
    // to check it against EXPECTED_SCHEMA_VERSION -- the two must never drift
    // apart silently. Same property and default as core/build.gradle.kts.
    systemProperty("fulla.root", rootProject.projectDir.absolutePath)
}

kover {
    reports {
        verify {
            rule {
                minBound(85)
            }
        }
    }
}
