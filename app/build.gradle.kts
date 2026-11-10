import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Release signing (never commit the key) ----
// Either signing/keystore.properties (storeFile, storePassword, keyAlias, keyPassword; storeFile relative to signing/),
// or the environment variables MHP_KEYSTORE_FILE, MHP_KEYSTORE_PASSWORD, MHP_KEY_ALIAS, MHP_KEY_PASSWORD (used by CI).
// Without either, release builds are signed with the debug key (fine for testing, not for publishing).
val keystorePropsFile = rootProject.file("signing/keystore.properties")
val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) } }
val envKeystore: String? = System.getenv("MHP_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }

// ---- Optional, for your OWN private build only: a built-in YouTube Data API key ----
// signing/youtube.properties with youtubeKey=AIza... . Public releases have NO key: every user adds their own free key
// in Settings (see docs/YOUTUBE-KEY.md). Never commit a key or publish an APK that contains yours.
val youtubePropsFile = rootProject.file("signing/youtube.properties")
val youtubeKey: String = Properties().apply { if (youtubePropsFile.exists()) youtubePropsFile.inputStream().use { load(it) } }
    .getProperty("youtubeKey", "").trim()
require(youtubeKey.isEmpty() || Regex("^AIza[0-9A-Za-z_-]{35}$").matches(youtubeKey)) { "signing/youtube.properties: youtubeKey is not a YouTube API key" }

android {
    namespace = "app.mediahome.tablet"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.pureeye108.mediahomepure"
        minSdk = 30          // Android 11
        targetSdk = 34       // Android 14
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "YT_BUILTIN_KEY", "\"$youtubeKey\"")
        buildConfigField("String", "MANUAL_URL", "\"https://github.com/PureEye108/media-home-pure/blob/main/docs/MANUAL.md\"")
        buildConfigField("String", "YOUTUBE_KEY_URL", "\"https://github.com/PureEye108/media-home-pure/blob/main/docs/YOUTUBE-KEY.md\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        create("release") {
            if (envKeystore != null) {
                storeFile = file(envKeystore)
                storePassword = System.getenv("MHP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MHP_KEY_ALIAS")
                keyPassword = System.getenv("MHP_KEY_PASSWORD")
            } else if (keystorePropsFile.exists()) {
                storeFile = rootProject.file("signing/" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (envKeystore != null || keystorePropsFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"   // installs next to the release build
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.environment("LC_ALL", "C.UTF-8") }   // UTF-8 file names in tests, as on Android
            // Optional mirror for Robolectric's Android jars (e.g. -ProbolectricRepo=https://...); default: Maven Central.
            providers.gradleProperty("robolectricRepo").orNull?.let { url -> all { it.systemProperty("robolectric.dependency.repo.url", url) } }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        textReport = true
    }
}

dependencies {
    // Player Pure (the music player) lives in the :player module and ships inside this one APK.
    implementation(project(":player"))
    // QR codes for "scan to open the web remote" (Apache 2.0).
    implementation("com.google.zxing:core:3.5.3")
    // Test-only: unit tests and screen renders on the build machine (not included in the APK).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
