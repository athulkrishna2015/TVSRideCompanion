import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 moved Compose compilation into a compiler plugin; the old
    // `kotlinCompilerExtensionVersion` string is rejected outright.
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing, when a key has been provided.
 *
 * `key.properties` is **not in the repo and must never be**. CI writes it from
 * GitHub Actions secrets immediately before building; a local build can write it
 * by hand. When it is absent — a fork, a contributor without the secrets,
 * anyone cloning to read the source — this is null and every build falls back to
 * the ordinary debug key, so the build never depends on a secret existing.
 *
 * The reason for having a key at all: each CI runner generates its *own* debug
 * keystore, so without this every build was signed differently and
 * `adb install -r` failed with INSTALL_FAILED_UPDATE_INCOMPATIBLE. One key,
 * held only as a secret, makes an installed app upgradeable in place.
 */
val keyProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keyProps.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

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

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Signed with the project key when one exists, debug-signed otherwise.
        // `assembleDebug` stays the shipped variant and stays debuggable, so
        // `adb shell run-as` can still pull the on-device log without root,
        // which is the entire debugging story for this project.
        debug {
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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

    // Pure-JVM tests for the protocol layer. No Android, no device: the packet
    // builders and the frame decoder are plain arithmetic on byte arrays, and
    // the double-division bug that made the odometer read 10x low was invisible
    // to the compiler and to every smoke test on a disconnected phone.
    testImplementation("junit:junit:4.13.2")
}

// CPU budget: see AGENTS.md and gradle.properties — workers.max=2, daemon off.
// No extra compiler flags are needed; `-Xjvm-default=all` is the default from
// Kotlin 2.0 and referencing it here broke the script.