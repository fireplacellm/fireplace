plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fireplace.engine"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }  // 12 GB Pixel-class only (ADR 0001)
        externalNativeBuild {
            cmake {
                // CRITICAL: force an optimized (-O3) native build even for the
                // DEBUG apk. A debug-mode llama.cpp build is 10-50x slower, which
                // makes on-device inference feel broken. Release gives GGML's
                // optimized kernels + runtime ARM CPU-feature detection (dotprod
                // etc.) that make quantized inference fast on real hardware.
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags += listOf("-O3")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // Backend plugin .so files must be extracted so ggml can scan them (BACKEND_DL).
    packaging { jniLibs.useLegacyPackaging = true }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // No third-party runtime deps in the engine module.
}
