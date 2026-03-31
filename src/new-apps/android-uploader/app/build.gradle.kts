plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun runGit(vararg args: String): String {
    return try {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(rootProject.projectDir.parentFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        output.ifBlank { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}

fun epochVersionCode(): Int {
    return (System.currentTimeMillis() / 1000L).toInt()
}

android {
    namespace = "com.geovault.uploader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.geovault.uploader"
        minSdk = 34
        targetSdk = 36
        versionCode = epochVersionCode()
        versionName = "${runGit("log", "-1", "--format=%cd", "--date=short")}-${runGit("rev-parse", "--short=10", "HEAD")}"
        buildConfigField("String", "GIT_COMMIT_SHA", "\"${runGit("rev-parse", "HEAD")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = !project.hasProperty("SKIP_MINIFY")
            isShrinkResources = !project.hasProperty("SKIP_MINIFY")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    signingConfigs {
        create("release") {
            val storeFileProp = project.findProperty("RELEASE_STORE_FILE")?.toString() ?: "keystore.jks"
            val keyAliasProp = project.findProperty("RELEASE_KEY_ALIAS")?.toString() ?: "upload"
            storeFile = file(storeFileProp)
            keyAlias = keyAliasProp

            val storePasswordProp = project.findProperty("RELEASE_STORE_PASSWORD")?.toString()
            val keyPasswordProp = project.findProperty("RELEASE_KEY_PASSWORD")?.toString()
            if (storePasswordProp != null) {
                storePassword = storePasswordProp
            }
            if (keyPasswordProp != null) {
                keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes.named("release") {
        signingConfig = signingConfigs.getByName("release")
        isDebuggable = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":android-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}