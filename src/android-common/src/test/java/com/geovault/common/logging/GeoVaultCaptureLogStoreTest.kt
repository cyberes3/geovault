package com.geovault.common.logging

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoVaultCaptureLogStoreTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.cacheDir.resolve(GeoVaultCaptureLogStore.DB_NAME).delete()
    }

    @Test
    fun prune_dropsOldestWhenOverMax() {
        val store = GeoVaultCaptureLogStore(context, maxStoredBytes = 14_000)
        val chunk = "x".repeat(3_200)
        repeat(5) {
            store.insertLog(Log.INFO, "tag", chunk, null)
        }
        assertTrue(store.totalApproxBytes() <= 14_000L)
        val out = ByteArrayOutputStream()
        store.streamLogsAsText(out)
        val text = out.toString(StandardCharsets.UTF_8)
        val lines = text.lines().filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty())
        assertTrue("expected fewer than 5 lines after prune, got ${lines.size}", lines.size < 5)
    }

    @Test
    fun streamLogs_retainsChronologicalOrder() {
        val store = GeoVaultCaptureLogStore(context, maxStoredBytes = 500_000)
        store.insertLog(Log.WARN, "t1", "first-line", null)
        store.insertLog(Log.ERROR, "t2", "second-line", null)
        val out = ByteArrayOutputStream()
        store.streamLogsAsText(out)
        val s = out.toString(StandardCharsets.UTF_8)
        assertTrue(s.contains("first-line"))
        assertTrue(s.contains("second-line"))
        assertTrue(s.indexOf("first-line") < s.indexOf("second-line"))
    }
}
