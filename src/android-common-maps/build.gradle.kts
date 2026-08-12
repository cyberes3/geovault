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
    namespace = "com.geovault.common.maps"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    resourcePrefix = "gv_common_"
}

dependencies {
    api(project(":android-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.material.icons)
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    api("org.maplibre.gl:android-sdk:12.3.1")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}
