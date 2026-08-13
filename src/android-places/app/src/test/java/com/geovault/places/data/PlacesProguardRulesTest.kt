package com.geovault.places.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesProguardRulesTest {
    @Test
    fun keepsPlacesDataPackageForGsonFieldNames() {
        val rules = readProguardRules()
        val keepsDataPackage = rules.contains("-keep class com.geovault.places.data.** { *; }")
        val keepsWriteDtos =
            rules.contains("-keep class com.geovault.places.data.PlaceWriteBody { *; }") &&
                rules.contains("-keep class com.geovault.places.data.PlaceWriteGeometry { *; }") &&
                rules.contains("-keep class com.geovault.places.data.PlaceWriteProperties { *; }")
        assertTrue(
            "proguard-rules.pro must keep PlaceWriteBody field names for Gson",
            keepsDataPackage || keepsWriteDtos,
        )
    }

    private fun readProguardRules(): String {
        val cwd = File(".").canonicalFile
        val candidates = listOf(
            File(cwd, "proguard-rules.pro"),
            File(cwd, "app/proguard-rules.pro"),
            File(cwd, "src/android-places/app/proguard-rules.pro"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("proguard-rules.pro not found from $cwd")
        return file.readText()
    }
}
