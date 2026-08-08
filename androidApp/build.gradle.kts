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

android {
    namespace = "com.jerreader.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jerreader.android"
        minSdk = 23
        targetSdk = 36
        versionCode = 56
        versionName = "1.3.5"
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":shared"))
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
}
