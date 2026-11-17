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
    // Readium 3.4, Compose 1.12 and androidx.core 1.19 require compileSdk 37
    // (AAR metadata). This only changes the APIs we compile against; runtime
    // behaviour follows targetSdk, which stays 36 until 37 has been reviewed
    // on a device.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jerreader.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 69
        versionName = "1.7.0"
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
            // R8 only shrinks here: proguard-rules.pro turns obfuscation off so
            // stack traces stay readable, and keeps the libraries that ship no
            // consumer rules of their own (Readium, the PDF renderer).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Phones need arm64-v8a only; that split is the deliverable. x86_64 is
    // built from the same DEX and resources so this Mac's emulator can install
    // and launch the Release candidate before delivery. 32-bit ABIs are not
    // shipped any more (they were about 53 MB of the old universal APK).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Both native apps ship the same original Japanese–Chinese vocabulary.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("shared/dictionaries"))

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
    testImplementation(libs.json)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
