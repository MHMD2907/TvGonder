// v0.29 – 2026-10-20: "TV Gönder Fare" 1.3 — TV Gönder adına ekran/uygulama açma; yalnız TV/tablete kurulur
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
        versionCode = 4
        versionName = "1.3"
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
