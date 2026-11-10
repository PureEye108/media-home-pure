plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Player Pure: the bit-perfect music player (own USB Audio driver in C++), built into Media Home Pure's APK.
android {
    namespace = "app.playerpure"
    compileSdk = 34
    ndkVersion = "27.0.12077973"
    resourcePrefix = "pp_"   // its resources merge with the app's: keep the names apart

    defaultConfig {
        minSdk = 30
        // 64-bit and 32-bit ARM phones/tablets/TV boxes, plus x86_64 (emulators, some Chromebooks).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.environment("LC_ALL", "C.UTF-8") }
            // Optional mirror for Robolectric's Android jars (e.g. -ProbolectricRepo=https://...); default: Maven Central.
            providers.gradleProperty("robolectricRepo").orNull?.let { url -> all { it.systemProperty("robolectric.dependency.repo.url", url) } }
        }
    }

    lint {
        abortOnError = false
        textReport = true
    }
}

dependencies {
    // Test-only: unit tests and screen renders on the build machine (not included in the APK).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
