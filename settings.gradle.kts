pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // DJI publishes MSDK V5 artifacts to Maven Central under the com.dji group,
        // but pin the exact version in app/build.gradle.kts and verify against
        // https://developer.dji.com/mobile-sdk/downloads before first build.
    }
}

rootProject.name = "wingman"
include(":app")
