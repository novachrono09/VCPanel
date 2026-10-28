plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.squad.tacticalvoice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.squad.tacticalvoice"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.9.0"

        // Only bundle ARM64 native C++ libraries for modern Android phones to cut APK size in half
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore.jks")
            storePassword = "squadcomms"
            keyAlias = "squadcomms"
            keyPassword = "squadcomms"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
    // LiveKit native WebRTC wrapper + Coroutines (that's literally all we need)
    implementation("io.livekit:livekit-android:2.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
