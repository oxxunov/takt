plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oxxunov.voiceenhance.ml"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        ndk {
            // DeepFilterNet (tract) собирается под 64-битные ABI
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cFlags += listOf("-O3")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":audio-engine"))
    // ONNX Runtime (MIT) — готовый движок для BandIt v2
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}
