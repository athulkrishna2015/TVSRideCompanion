// `pluginManagement.repositories` is separate from dependency repositories and is
// NOT inherited from `dependencyResolutionManagement`. Without this block Gradle
// only searches the plugin portal, and the Android plugin fails to resolve.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

// Plugin versions are declared here, once. `app/build.gradle.kts` applies them
// without repeating a version.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

rootProject.name = "TVSRideCompanion"
include(":app")