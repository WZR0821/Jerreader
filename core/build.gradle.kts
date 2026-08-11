plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * The reader's decisions, with no UI toolkit attached.
 *
 * Keeping this module free of Compose is what lets it target `iosX64` as well
 * as the arm64 slices: Compose Multiplatform no longer publishes Intel
 * simulator artifacts, so a UI-bearing module cannot even be *built* on an
 * Intel Mac, let alone tested there. Splitting the algorithms out means the
 * selection merger, the palette and the placement solver run under
 * Kotlin/Native on any developer machine — and it means the iOS app can adopt
 * this module from SwiftUI today without committing to Compose UI first.
 */
kotlin {
    android {
        namespace = "com.jerreader.unified.core"
        compileSdk = 36
        minSdk = 23

        withHostTestBuilder {}.configure {}
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "JerreaderCore"
            isStatic = true
        }
    }

    // The device the Kotlin/Native test executable is launched on. Override with
    // `-Pjerreader.iosSimulator="iPhone 16"` when the default is unavailable.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithSimulatorTests>()
        .configureEach {
            testRuns.configureEach {
                deviceId = providers.gradleProperty("jerreader.iosSimulator")
                    .getOrElse("iPhone 16 Pro")
            }
        }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
