plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 moved Compose compilation into a compiler plugin; the old
    // `kotlinCompilerExtensionVersion` string is rejected outright.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.tvsopen"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.tvsopen"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Jetpack Compose. BOM 2024.09.03 (Compose 1.7.2) is the last line that
    // compiles against compileSdk 34. The 2024.12 BOM and core-ktx 1.15 both
    // hard-require compileSdk 35. Raise compileSdk to 35 and these together.
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    // collectAsStateWithLifecycle, used by Session-backed screens.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// CPU budget: see AGENTS.md and gradle.properties — workers.max=2, daemon off.
// No extra compiler flags are needed; `-Xjvm-default=all` is the default from
// Kotlin 2.0 and referencing it here broke the script.