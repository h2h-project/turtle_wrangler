// AGP 9 compiles Kotlin itself (built-in Kotlin support), so there is no
// org.jetbrains.kotlin.android plugin — only the Compose compiler plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.hopeturtles.wrangler"
    compileSdk = 37

    defaultConfig {
        // Matches the OAuth redirect scheme reserved for the Buwana login
        // (docs/wrangler/02_android_app.md, Phase 8). Don't change after
        // the first install that people keep.
        applicationId = "org.hopeturtles.wrangler"
        minSdk = 31      // Android 12: BLUETOOTH_SCAN/CONNECT permission model only
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.maplibre.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
