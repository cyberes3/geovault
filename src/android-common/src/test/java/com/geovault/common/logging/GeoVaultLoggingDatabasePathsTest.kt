package com.geovault.common.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoVaultLoggingDatabasePathsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.cacheDir.resolve(GeoVaultCaptureLogStore.DB_NAME).delete()
        context.getDatabasePath(GeoVaultCaptureLogStore.DB_NAME).delete()
        context.getDatabasePath("geovault_other.sqlite").delete()
    }

    @Test
    fun storeInit_deletesLegacyDatabaseInDatabasesDir() {
        val legacyFile = context.getDatabasePath(GeoVaultCaptureLogStore.DB_NAME)
        legacyFile.parentFile?.mkdirs()
        assertTrue(legacyFile.createNewFile())

        val store = GeoVaultCaptureLogStore(context)
        store.readableDatabase.close()

        assertFalse(legacyFile.exists())
        assertTrue(File(context.cacheDir, GeoVaultCaptureLogStore.DB_NAME).exists())
    }

    @Test
    fun storeInit_doesNotSweepOtherGeovaultSqliteFiles() {
        val other = context.getDatabasePath("geovault_other.sqlite")
        other.parentFile?.mkdirs()
        assertTrue(other.createNewFile())

        val store = GeoVaultCaptureLogStore(context)
        store.readableDatabase.close()

        assertTrue(other.exists())
        other.delete()
    }
}
