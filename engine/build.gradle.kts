plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tupicgames.takt.engine"
    compileSdk = 34

    defaultConfig {
        // AAudio требует API 26. Ниже остаётся только SoundPool с задержкой,
        // на которой ритм-игра не работает в принципе.
        minSdk = 26

        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
}
