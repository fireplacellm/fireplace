import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is read from keystore.properties (gitignored) if present, so the
// release APK is properly signed without hardcoding secrets. Without that file the
// release build falls back to debug signing (fine for a local build / CI).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.fireplace"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    // The bundled starter pack is already a zip and the ONNX model is binary —
    // storing them uncompressed avoids double-compression bloat and lets them
    // stream-copy out of the APK on first run.
    androidResources { noCompress += listOf("fpack", "onnx") }

    defaultConfig {
        applicationId = "com.fireplace"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Code shrinking is off: the APK is dominated by the bundled packs +
            // native llama.cpp (R8 would save little and risks the JNI/Compose
            // reflection surface). The native lib is already built -O3/Release.
            isMinifyEnabled = false
            signingConfig = if (keystoreProps.isNotEmpty())
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":engine"))

    // Compose UI
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Bundled SQLite from Google (androidx.sqlite), which ships a current SQLite
    // with FTS5 enabled — the platform SQLite does NOT include FTS5 on all images.
    // From Google's maven, not jitpack (keeps the supply chain tight, ADR note).
    implementation("androidx.sqlite:sqlite:2.5.2")
    implementation("androidx.sqlite:sqlite-bundled:2.5.2")

    // Coroutines: used by the Compose UI (drawer/scope). Local only.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ONNX Runtime: run the all-MiniLM-L6-v2 sentence embedder on-device for
    // dense retrieval (ADR 0002). Local inference, no network.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // No network, analytics, or crash-reporting libraries. By design (ADR 0003).
}
