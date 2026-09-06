package com.geovault.common.logging

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BufferedLogEngineTest {

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.cacheDir.resolve(GeoVaultCaptureLogStore.DB_NAME).delete()
        app.getDatabasePath(GeoVaultCaptureLogStore.DB_NAME).delete()
    }

    @Test
    fun exportToDownloads_returnsFalseWhenDisabled() {
        val engine = engine(enabled = false)
        assertFalse(engine.exportToDownloads(app))
    }

    @Test
    fun insertAndExport_shareOneStoreInstance() {
        val constructions = AtomicInteger(0)
        val exported = CountDownLatch(1)
        val engine = engine(
            enabled = true,
            createStore = {
                constructions.incrementAndGet()
                GeoVaultCaptureLogStore(it)
            },
            writeExport = { _, _, _ -> exported.countDown() },
        )
        engine.init(app)
        repeat(4) { index ->
            engine.enqueue(Log.INFO, "tag", "line-$index")
        }
        assertTrue(engine.exportToDownloads(app))
        assertTrue(exported.await(3, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, constructions.get())
    }

    private fun engine(
        enabled: Boolean,
        createStore: (Application) -> GeoVaultCaptureLogStore = { GeoVaultCaptureLogStore(it) },
        writeExport: (Context, GeoVaultCaptureLogStore, Long) -> Unit = { _, _, _ -> },
    ): BufferedLogEngine<GeoVaultCaptureLogStore> =
        BufferedLogEngine(
            isEnabled = { enabled },
            insertThreadName = "BufferedLogEngineTestInsert",
            exportThreadName = "BufferedLogEngineTestExport",
            tag = "BufferedLogEngineTest",
            eventPrefix = "test",
            createStore = createStore,
            writeExport = writeExport,
        )
}
