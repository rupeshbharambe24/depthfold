import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.depthfold.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.depthfold.android"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.1"
        buildConfigField("String", "ARCORE_SDK_VERSION", "\"${libs.versions.arcore.get()}\"")
    }

    signingConfigs {
        getByName("debug") {
            // A shared, deliberately public debug key, so every debug APK (local or CI) installs over the previous
            // one instead of needing an uninstall. It is not a secret and must never sign a store release.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No release signing config is checked in; sign release builds yourself.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.arcore)
    // JSON element builder/parser only; no serialization compiler plugin needed.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
