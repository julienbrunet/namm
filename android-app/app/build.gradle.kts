plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Version injected by CI (build number), 1 locally.
// The last digit stays within 0..99: beyond that, the middle digit is incremented
// (build 99 -> 0.1.99, build 100 -> 0.2.0, build 205 -> 0.3.5). versionCode stays the build number.
val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
val appVersionName = "0.${1 + buildNumber / 100}.${buildNumber % 100}"

tasks.register("printVersionName") {
    doLast { println(appVersionName) }
}

android {
    namespace = "fr.jbrunet.yama"
    compileSdk = 37

    defaultConfig {
        applicationId = "fr.jbrunet.yama"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = appVersionName
    }

    // Release key provided by CI through secrets; otherwise the debug key (installable APK, but not official).
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }
    // English (default) and French only: drops the other translations shipped by libraries.
    androidResources {
        localeFilters += listOf("en", "fr")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Optional Maven mirror for Robolectric (see CLAUDE.md, ~/.gradle/gradle.properties).
            providers.gradleProperty("robolectricRepo").orNull?.let { repo ->
                it.systemProperty("robolectric.dependency.repo.url", repo)
            }
        }
    }
}

// AGP 9: Kotlin is built into the Android plugin (no more org.jetbrains.kotlin.android plugin).
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

roborazzi {
    outputDir.set(file("screenshots"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.osmdroid)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
