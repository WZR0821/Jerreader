import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

// Signing credentials live outside the build script so the keystore password
// never lands in version control. Without this file the release build stays
// unsigned, which Android refuses to install.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// One version for both platforms, read from the repository root. See
// `version.properties` and `iosApp/Scripts/sync-version.sh`.
val jerreaderVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

android {
    // The Android host keeps its own package. `applicationId` in particular is
    // an installed identity: the user's phone already carries books and reading
    // positions under `com.jerreader.android`, and changing it would install a
    // second, empty app beside the real one.
    namespace = "com.jerreader.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jerreader.android"
        minSdk = 23
        targetSdk = 36
        versionCode = jerreaderVersion.getProperty("versionCode").toInt()
        versionName = jerreaderVersion.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets.named("main") {
        // One generated dictionary asset is consumed by both native hosts.
        // Keeping the bytes outside either platform tree prevents two releases
        // from silently shipping different JMdict snapshots.
        assets.directories.add(rootProject.file("sharedResources").absolutePath)
    }
    sourceSets.named("androidTest") {
        assets.directories.add("schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // `:ui` re-exports `:core`, but naming both keeps the dependency honest:
    // this module calls plenty of `:core` directly, and a future UI-free host
    // should not be able to lose it silently.
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    // Backup writes into a folder the user grants through the Storage
    // Access Framework, which is addressed by tree document rather than path.
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // The reading engine stays native: Readium's Kotlin toolkit renders the
    // publication, `:core` owns every decision above it.
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.pdfium)
    // Readium's PDF navigator embeds this viewer as a runtime dependency;
    // keep it explicit so tap translation can follow its zoom/scroll matrix.
    implementation(libs.android.pdf.viewer)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.translate)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    // The android.jar stub returns defaults for org.json, which makes a JSON
    // round trip untestable. A real implementation on the unit-test classpath
    // is what lets the manifest parsing be covered without an emulator.
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
