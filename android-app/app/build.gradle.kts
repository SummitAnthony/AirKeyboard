import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing key, kept outside the project: %USERPROFILE%\.airkeyboard\keystore.properties
// (storeFile, storePassword, keyAlias, keyPassword). Without it the release APK is unsigned.
val keystoreProps = File(System.getProperty("user.home"), ".airkeyboard/keystore.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "com.airkeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airkeyboard"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = File(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // -PtestBuildType=release builds the instrumented tests against the signed release build
    testBuildType = (project.findProperty("testBuildType") as String?) ?: "debug"

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // ChunkBuilder logs through android.util.Log, which is a stub in local unit tests
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.constraintlayout)

    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // Coroutines
    implementation(libs.kotlin.coroutines.android)
    implementation(libs.kotlin.coroutines.core)

    // QR Code
    implementation(libs.zxing.core)
    implementation(libs.zxing.android)

    // Logging
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}

