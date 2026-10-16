plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Version injectée par la CI (numéro de build), 1 en local.
// Le dernier chiffre reste dans 0..99 : au-delà, on incrémente le chiffre du milieu
// (build 99 -> 0.1.99, build 100 -> 0.2.0, build 205 -> 0.3.5). versionCode reste le numéro de build.
val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
val appVersionName = "0.${1 + buildNumber / 100}.${buildNumber % 100}"

tasks.register("printVersionName") {
    doLast { println(appVersionName) }
}

android {
    namespace = "com.namm.meteo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.namm.meteo"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = appVersionName
    }

    signingConfigs {
        // Clé de release fournie par la CI via secrets ; sinon, clé debug (APK installable mais non officiel).
        create("release") {
            val ks = System.getenv("SIGNING_KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Miroir Maven optionnel pour Robolectric (voir CLAUDE.md, ~/.gradle/gradle.properties).
            providers.gradleProperty("robolectricRepo").orNull?.let { repo ->
                it.systemProperty("robolectric.dependency.repo.url", repo)
            }
        }
    }
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
