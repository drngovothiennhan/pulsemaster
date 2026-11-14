plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.yhct.pulsemaster.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yhct.pulsemaster"
        minSdk = 26           // VibrationEffect.createWaveform / hasAmplitudeControl cần API 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Nguồn dữ liệu duy nhất: ../data/tcm_28_pulses_haptic.json -> assets/
    sourceSets["main"].assets.srcDir("../../data")
}

dependencies {
    implementation(project(":core"))
}
