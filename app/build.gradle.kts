plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.codex.tvremote"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.codex.tvremote"
        minSdk = 26
        targetSdk = 35
        val buildNumber = providers.gradleProperty("buildNumber").orElse("1").get().toInt()
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }
    signingConfigs {
        create("release") {
            val storePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47")
}
