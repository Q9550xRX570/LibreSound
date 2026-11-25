// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

val releaseStoreFilePath: String? =
    System.getenv("LIBREOPUS_KEYSTORE_PATH") ?: keystoreProps.getProperty("storeFile")
val releaseStoreFile = releaseStoreFilePath?.let { file(it) }?.takeIf { it.exists() }
val releaseStorePassword: String? =
    System.getenv("LIBREOPUS_KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
val releaseKeyAlias: String? =
    System.getenv("LIBREOPUS_KEYSTORE_ALIAS") ?: keystoreProps.getProperty("keyAlias")
val releaseKeyPassword: String? =
    System.getenv("LIBREOPUS_KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("keyPassword")

val hasReleaseSigning = releaseStoreFile != null &&
    !releaseStorePassword.isNullOrEmpty() &&
    !releaseKeyAlias.isNullOrEmpty() &&
    !releaseKeyPassword.isNullOrEmpty()

android {
    namespace = "com.libreopus.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.libreopus.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val requestedTasks = gradle.startParameter.taskNames
    val isLiteOnlyBuild = requestedTasks.isNotEmpty() && requestedTasks.all {
        it.contains("Lite", ignoreCase = true) && !it.contains("Full", ignoreCase = true)
    }

    flavorDimensions += "engine"
    productFlavors {
        create("lite") {
            dimension = "engine"
            buildConfigField("String", "AUDIO_ENGINE_FLAVOR", "\"lite\"")
            buildConfigField("boolean", "HAS_YTDLP", "false")
        }
        create("full") {
            dimension = "engine"
            buildConfigField("String", "AUDIO_ENGINE_FLAVOR", "\"full\"")
            buildConfigField("boolean", "HAS_YTDLP", "true")
            if (isLiteOnlyBuild) {
                ndk {
                    abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                }
            }
        }
    }

    if (!isLiteOnlyBuild) {
        splits {
            abi {
                isEnable = true
                reset()
                include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                isUniversalApk = true
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            vcsInfo {
                include = false
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // Required for F-Droid Reproducible Builds: strip proprietary dependency metadata & VCS block
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/*.version",
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core:audio-api"))
    implementation(project(":core:dsp"))
    "liteImplementation"(project(":engine:lite"))
    "fullImplementation"(project(":engine:lite"))
    "fullImplementation"(libs.youtubedl.library)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
