import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// This module is included from multiple root projects; keep build outputs under each root
// to avoid cross-project stale artifacts and duplicate class packaging collisions.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("external-modules/${project.name}"))

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.geovault.common.maps"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    resourcePrefix = "gv_common_"
}

dependencies {
    api(project(":android-common"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    api("org.maplibre.gl:android-sdk:11.3.0")
    api("org.maplibre.gl:android-plugin-annotation-v9:3.0.2")
    api("com.google.android.gms:play-services-location:21.0.1")
}
