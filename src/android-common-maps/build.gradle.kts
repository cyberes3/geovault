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

tasks.matching {
    it.name.matches(Regex("compile.*(Kotlin|JavaWithJavac)$"))
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
    implementation("com.google.android.gms:play-services-location:21.3.0")

    api("org.maplibre.gl:android-sdk:12.3.1")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
