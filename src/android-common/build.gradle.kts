import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.file.RegularFileProperty

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

if (rootProject != project) {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("shared-modules/${project.name}"))
}

abstract class GeoVaultSharedModuleCompileLockService :
    BuildService<GeoVaultSharedModuleCompileLockService.Parameters>,
    AutoCloseable {

    interface Parameters : BuildServiceParameters {
        val lockFile: RegularFileProperty
    }

    private val channel: FileChannel
    private val lock: FileLock

    init {
        val file = parameters.lockFile.asFile.get()
        file.parentFile.mkdirs()
        channel = RandomAccessFile(file, "rw").channel
        lock = channel.lock()
    }

    override fun close() {
        lock.release()
        channel.close()
    }
}

val geoVaultCaptureLoggingEnabled =
    project.findProperty("GEOVAULT_ADD_LOGGING")?.toString() == "true"

val geoVaultSharedModuleCompileLock = gradle.sharedServices.registerIfAbsent(
    "geoVaultSharedModuleCompileLock",
    GeoVaultSharedModuleCompileLockService::class,
) {
    parameters.lockFile.set(
        project.layout.projectDirectory.file("../.gradle/geovault-shared-module-compile.lock"),
    )
}

// Serialize compilation *and* compile-jar packaging for this included project.
// Downstream :app KSP/JavaCompile can start as soon as compile*Kotlin finishes unless
// bundleLibCompileToJar* is also guarded; without it, parallel workers can read a
// missing `classes.jar` (NoSuchFileException) while the jar task is still running.
tasks.matching {
    it.name.matches(Regex("compile.*(Kotlin|JavaWithJavac)$")) ||
        it.name.startsWith("bundleLibCompileToJar")
}.configureEach {
    usesService(geoVaultSharedModuleCompileLock)
}

android {
    namespace = "com.geovault.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("proguard-rules.pro")
    }

    buildTypes.configureEach {
        buildConfigField(
            "boolean",
            "GEOVAULT_CAPTURE_LOGGING_ENABLED",
            if (geoVaultCaptureLoggingEnabled) "true" else "false",
        )
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api("androidx.core:core-splashscreen:1.2.0")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore)

    testImplementation(libs.junit)
    testImplementation("androidx.test:core:1.7.0")
    // Plain JVM unit tests hit android.util.Log from auth code; Robolectric provides a shadowed
    // Android environment (same pattern as :android-common-maps unit tests).
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
