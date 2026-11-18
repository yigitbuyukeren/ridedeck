import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from keystore.properties (storeFile, storePassword, keyAlias, keyPassword),
// which is never committed. Without it the release build is simply left unsigned.
val keystoreProps: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

// Per-test process isolation is opt-in. On the local API 35 x86_64 emulator the
// orchestrator doubled a 12-test subset (18s -> 36s) and the full 174-test run stalled
// after 12 tests versus 77 tests without it, so CI keeps the faster single-process run.
// Opt in when debugging a crashing test:
//   ./gradlew connectedDebugAndroidTest -PrdOrchestrator=true \
//     -Pandroid.testInstrumentationRunnerArguments.clearPackageData=true
val useOrchestrator = providers.gradleProperty("rdOrchestrator").orNull?.toBoolean() == true

android {
    namespace = "com.ridedeck.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ridedeck.app"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "com.ridedeck.app.RideDeckTestRunner"
        versionCode = 8
        versionName = "0.7.0"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // x86_64 lets the instrumentation suite run on the standard GitHub-hosted
            // x86_64 emulator; release stays ARM-only to keep the shipped APK small.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        // Compose timing assertions depend on the system animation scale.
        animationsDisabled = true
        if (useOrchestrator) execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestUtil("androidx.test:orchestrator:1.5.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
