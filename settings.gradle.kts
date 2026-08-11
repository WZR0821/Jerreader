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
        // Readium's optional PDFium adapter publishes its native viewer
        // dependencies through JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "JerreaderUnified"

include(":core")
include(":ui")
include(":androidApp")
