plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.directaccess.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.directaccess.app"
        minSdk = 29       // Android 7.0 Nougat (lowest clean floor for Go 1.24 / Xray / VpnService)
        targetSdk = 36    // Android 16 (Mandatory for Google Play 2026)
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // For GitHub CI testing; configure signingConfig with your Play Upload Keystore for Play Store!
            signingConfig = signingConfigs.getByName("debug")
        }
    }

  splits {
    abi {
        // تشخیص خودکار برای غیرفعال کردن splits در زمان ساخت AAB
        val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
        isEnable = !isBuildingBundle
        reset()
        include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        isUniversalApk = true
    }
}

    packaging {
        jniLibs {
            // Mandatory for Android 15/16 16 KB page-size alignment
            useLegacyPackaging = false
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
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
