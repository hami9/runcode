import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
    id("com.chaquo.python")
}

// Release signing material never lives in the repo. Supply it either through an untracked
// keystore.properties next to the root build file, or through environment variables in CI.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, envName: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(envName)

val releaseStorePath: String? = signingValue("storeFile", "RUNCODE_KEYSTORE")

android {
    namespace = "com.runcode.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.runcode.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Chaquopy ships a native CPython, so the APK must be limited to real ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        // Registered only when signing material is actually present, so a plain clone still
        // builds — the release APK simply comes out unsigned in that case.
        if (releaseStorePath != null) {
            create("release") {
                val store = signingValue("storePassword", "RUNCODE_KEYSTORE_PASSWORD")
                storeFile = file(releaseStorePath)
                storePassword = store
                keyAlias = signingValue("keyAlias", "RUNCODE_KEY_ALIAS")
                // PKCS12 keystores — what keytool produces by default since JDK 9 — have no
                // separate key password, so fall back to the store password when none is given.
                keyPassword = signingValue("keyPassword", "RUNCODE_KEY_PASSWORD") ?: store

                // v2 alone is enough to install on minSdk 26, but v3 is what makes key
                // rotation possible later, and it costs nothing to include now.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"

        // pip runs on the build machine, not the device, and Chaquopy needs a matching
        // CPython 3.12 to run it. Point RUNCODE_BUILD_PYTHON at one if it is not on PATH.
        System.getenv("RUNCODE_BUILD_PYTHON")?.let { buildPython(it) }

        // Wheels are resolved at build time and packaged into the APK; there is no pip on
        // the device. Anything a project imports has to be listed here.
        pip {
            install("python-telegram-bot==21.9")
            install("requests==2.32.3")
        }
    }
}

secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
