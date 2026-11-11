// v1.6 – 2026-11-11: "TV Gönder Fare" 1.6 — tıklama düzeltmesi (düğmeye doğrudan basma), TV Gönder kapalıyken de çalışır; yalnız TV/tablete kurulur
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
        versionCode = 7
        versionName = "1.6"
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
