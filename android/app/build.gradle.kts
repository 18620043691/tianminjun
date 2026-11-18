plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bitchat.droid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bitchat.droid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "BLE_SERVICE_UUID", "\"F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5A\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "BLE_SERVICE_UUID", "\"F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    testImplementation("junit:junit:4.13.2")
}
