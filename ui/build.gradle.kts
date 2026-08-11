plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

/**
 * The one implementation of the reader's overlays, in Compose Multiplatform.
 *
 * Only arm64 iOS slices exist here, because Compose Multiplatform stopped
 * publishing `iosX64` artifacts — building or running this module's iOS side
 * needs an Apple Silicon Mac. `:core` carries no such restriction, which is why
 * every rule worth testing lives there.
 */
kotlin {
    android {
        namespace = "com.jerreader.unified.ui"
        compileSdk = 36
        minSdk = 23

        withHostTestBuilder {}.configure {}
    }

    val iosTargets = listOf(iosArm64(), iosSimulatorArm64())
    iosTargets.forEach { target ->
        target.binaries.framework {
            // What the Swift app links: the shared overlays plus, transitively,
            // every decision `:core` makes.
            baseName = "JerreaderShared"
            isStatic = true
            export(project(":core"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
