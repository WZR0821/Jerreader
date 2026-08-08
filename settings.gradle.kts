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
        // Readium's optional PDFium adapter currently publishes its native
        // viewer dependencies through JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "JerreaderMobile"

include(":androidApp")
include(":shared")
