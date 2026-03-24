plugins {
}

android {
    namespace = "com.cyberes.geovaultuploader"

    defaultConfig {
        applicationId = "com.cyberes.geovaultuploader"
        minSdk = 24
        targetSdk = 36
        versionName = "1.0"
        versionCode = (System.currentTimeMillis() / 1000L).toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {
}