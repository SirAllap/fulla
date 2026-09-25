// SPDX-License-Identifier: GPL-3.0-or-later
//
// The Kotlin and Android plugins are declared here, with versions, and not in
// the root build file: see the comment there.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Signing keys never live in the repository. CI provides them as files and
// passwords through the environment; a local build without them uses the
// SDK's own debug key.
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.sirallap.fulla"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sirallap.fulla"
        minSdk = 26
        targetSdk = 35
        // Set by the release workflow from the tag; local builds are 0.0.0 (1).
        versionCode = env("FULLA_VERSION_CODE")?.toInt() ?: 1
        versionName = env("FULLA_VERSION_NAME") ?: "0.0.0"
        // The household's server and Google sign-in, from the build's
        // environment (CI secrets); never written in the repository. Empty
        // values give a build where people bring their own Supabase project.
        buildConfigField("String", "PROJECT_URL", "\"${env("FULLA_PROJECT_URL") ?: ""}\"")
        buildConfigField("String", "ANON_KEY", "\"${env("FULLA_ANON_KEY") ?: ""}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${env("FULLA_GOOGLE_CLIENT_ID") ?: ""}\"")
        resourceConfigurations += setOf("en", "es", "fr", "de", "it", "pt")
    }

    signingConfigs {
        getByName("debug") {
            env("FULLA_DEBUG_KEYSTORE")?.let {
                storeFile = file(it)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            env("FULLA_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = env("FULLA_KEYSTORE_PASSWORD")
            keyAlias = env("FULLA_KEY_ALIAS")
            keyPassword = env("FULLA_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (env("FULLA_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // No dependency metadata blob in the APK: it is only readable by Google.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/*.version")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":client"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.zxing.android) { isTransitive = false }
    implementation(libs.zxing.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Screenshots of the screens with demo data (ScreenshotTest) are written only
// when FULLA_SCREENSHOTS=true, by the Screenshots workflow; ordinary test runs
// render them without writing anything.
// The database setup script, bundled from the migrations, goes inside the app:
// setting up a household's own Supabase project from the phone installs it.
val setupSqlDir = layout.buildDirectory.dir("generated/setupSql")
val bundleSetupSql by tasks.registering(Exec::class) {
    val script = rootProject.file("supabase/scripts/bundle.js")
    inputs.file(script)
    inputs.dir(rootProject.file("supabase/migrations"))
    outputs.dir(setupSqlDir)
    commandLine("node", script.absolutePath, setupSqlDir.get().file("setup.sql").asFile.absolutePath)
}
android.sourceSets.getByName("main").assets.srcDir(setupSqlDir)
tasks.named("preBuild") { dependsOn(bundleSetupSql) }

tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", System.getenv("FULLA_SCREENSHOTS") ?: "false")
    systemProperty("fulla.screenshots.dir", rootProject.file("screenshots").absolutePath)
    // Rendering with native graphics is slow; ordinary runs skip it.
    if (System.getenv("FULLA_SCREENSHOTS") != "true") exclude("**/Screenshot*")
}
