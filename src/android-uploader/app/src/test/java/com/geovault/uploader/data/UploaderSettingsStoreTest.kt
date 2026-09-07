package com.geovault.uploader.data

import android.app.Application
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class UploaderSettingsStoreTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun preloadOnLaunch_exposesDefaultThenWrites() = runBlocking {
        val store = UploaderSettingsStore(context, fileName = unique("preload"))
        store.preloadOnLaunch()
        assertEquals(true, store.snapshot().suffixEnabled)
        store.setSuffixEnabled(false)
        assertEquals(false, store.snapshot().suffixEnabled)
    }

    @Test
    fun clearAll_restoresDefault() = runBlocking {
        val store = UploaderSettingsStore(context, fileName = unique("clear"))
        store.preloadOnLaunch()
        store.setSuffixEnabled(false)
        store.clearAll()
        assertEquals(true, store.snapshot().suffixEnabled)
    }

    @Test
    fun legacyAddSuffixMap_isImported() {
        val legacyName = unique("legacy_prefs")
        val legacy = File(context.filesDir, "datastore/$legacyName")
        legacy.parentFile?.mkdirs()
        legacy.writeText(
            """
            {
              "schemaVersion": 1,
              "boolValues": {"add_suffix": false},
              "stringValues": {},
              "intValues": {},
              "longValues": {},
              "floatValues": {}
            }
            """.trimIndent(),
        )
        val store = UploaderSettingsStore(
            context,
            fileName = unique("legacy_options"),
            legacyFileName = legacyName,
        )
        store.preloadOnLaunch()
        assertEquals(false, store.snapshot().suffixEnabled)
    }

    private fun unique(label: String): String = "${label}_${System.nanoTime()}.settings"
}
