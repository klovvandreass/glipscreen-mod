plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glipscreen.mod"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glipscreen.mod"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        if (System.getenv("KLOVV_KEYSTORE_FILE") != null) {
            create("release") {
                storeFile = file(System.getenv("KLOVV_KEYSTORE_FILE"))
                storePassword = System.getenv("KLOVV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KLOVV_KEY_ALIAS")
                keyPassword = System.getenv("KLOVV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (System.getenv("KLOVV_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
