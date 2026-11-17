plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "com.jerreader.shared"
        compileSdk = 37
        minSdk = 24

        withHostTestBuilder {}.configure {}
    }

    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64()
    )
    iosTargets.forEach { target ->
        target.binaries.framework {
            baseName = "JerreaderShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The parity tests read shared/parity/*.json. The android.jar stub
        // returns defaults for org.json, so host tests need the real library.
        getByName("androidHostTest").dependencies {
            implementation(libs.json)
        }
    }
}

// The fixtures are test inputs: editing one must rerun the host tests even
// when no Kotlin source changed.
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("parity"))
        .withPropertyName("parityFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
