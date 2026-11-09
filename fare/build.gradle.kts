// v1.5 – 2026-11-09: "TV Gönder Fare" 1.5 — anahtar doğrulaması düzeldi (Android düz HTTP engeli); dinleyici takılmaz; yalnız TV/tablete kurulur
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.onur.tvgonder.fare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.onur.tvgonder.fare"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
