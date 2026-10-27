plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.vaczi.myiohome"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.vaczi.myiohome"
        minSdk = 24
        targetSdk = 37
        versionCode = 11
        versionName = "0.2.0"
    }

    signingConfigs {
        // Release upload-kulcs (Play App Signing). Aktív, ha a VACZI_* property-k ki vannak töltve
        // (a fejlesztő gépén ~/.gradle/gradle.properties → Downloads/Keys/gradle.properties hardlinken át)
        // ÉS a keystore-fájl létezik. Más gépen / üres jelszónál a release a debug-kulcsra esik
        // vissza (lentebb), hogy a lokális :app:assembleRelease sose törjön el.
        create("release") {
            val storeFilePath = (project.findProperty("VACZI_STORE_FILE") as String?).orEmpty()
            if (storeFilePath.isNotEmpty() && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = (project.findProperty("VACZI_STORE_PASSWORD") as String?).orEmpty()
                keyAlias = (project.findProperty("VACZI_KEY_ALIAS") as String?).orEmpty()
                keyPassword = (project.findProperty("VACZI_KEY_PASSWORD") as String?).orEmpty()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Ha a release upload-kulcs konfigurálva van (keystore létezik + jelszó kitöltve),
            // azzal írunk alá (Play); különben fallback a gépi debug-kulcsra (sideload, CI).
            val vacziStore = (project.findProperty("VACZI_STORE_FILE") as String?).orEmpty()
            val vacziPass = (project.findProperty("VACZI_STORE_PASSWORD") as String?).orEmpty()
            signingConfig = if (vacziStore.isNotEmpty() && vacziPass.isNotEmpty() && file(vacziStore).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    // biometric 1.1.0 drags in fragment 1.2.x, which breaks registerForActivityResult (lint InvalidFragmentVersionForActivityResult).
    implementation(libs.androidx.fragment)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}
