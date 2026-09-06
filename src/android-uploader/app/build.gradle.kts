plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

private val fullShaRegex = Regex("^[0-9a-f]{40}$")

fun gitCommitShaOverride(): String? {
    val raw = (project.findProperty("GIT_COMMIT_SHA_OVERRIDE") ?: "").toString().trim().lowercase()
    return raw.takeIf { fullShaRegex.matches(it) }
}

fun gitCommitFullForBuild(): String {
    return gitCommitShaOverride() ?: runGit("rev-parse", "HEAD")
}

private fun commitFragmentFromFullHex(full: String): String =
    when {
        full == "unknown" || full.isBlank() -> "unknown"
        full.length <= 10 -> full
        else -> full.take(10)
    }

fun versionNameForBuild(): String {
    val full = gitCommitFullForBuild()
    val date =
        if (gitCommitShaOverride() != null) {
            runGit("show", "-s", "--format=%cd", "--date=short", full)
        } else {
            runGit("log", "-1", "--format=%cd", "--date=short")
        }
    return "$date-${commitFragmentFromFullHex(full)}"
}

fun epochVersionCode(): Int {
    return (System.currentTimeMillis() / 1000L).toInt()
}

/**
 * With [gitCommitShaOverride] (--old-version), use that commit's Unix time as [versionCode] so the
 * APK is not stamped with "now" and blocked as a downgrade when installing a newer release APK.
 */
fun versionCodeForBuild(): Int {
    val sha = gitCommitShaOverride() ?: return epochVersionCode()
    val ct = runGit("show", "-s", "--format=%ct", sha).trim()
    val unix = ct.toLongOrNull() ?: return epochVersionCode()
    return unix.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

android {
    namespace = "com.geovault.uploader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.geovault.uploader"
        minSdk = 34
        targetSdk = 36
        versionCode = versionCodeForBuild()
        versionName = versionNameForBuild()
        buildConfigField("String", "GIT_COMMIT_SHA", "\"${gitCommitFullForBuild()}\"")

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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":android-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}