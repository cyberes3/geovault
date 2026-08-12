package com.geovault.common.files

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultOutgoingShareTest {

    @Test
    fun `createFileShareIntent is ACTION_SEND with stream and grant`() {
        val uri = Uri.parse("content://app/exports/a.kmz")
        val intent = GeoVaultOutgoingShare.createFileShareIntent(uri, "application/vnd.google-earth.kmz")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/vnd.google-earth.kmz", intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `createTextShareIntent is text plain EXTRA_TEXT`() {
        val intent = GeoVaultOutgoingShare.createTextShareIntent("https://example.test/share")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("https://example.test/share", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `createViewIntent is ACTION_VIEW with grant and optional new task`() {
        val uri = Uri.parse("content://app/pdfs/a.pdf")
        val withTask = GeoVaultOutgoingShare.createViewIntent(uri, "application/pdf", newTask = true)
        assertEquals(Intent.ACTION_VIEW, withTask.action)
        assertEquals(uri, withTask.data)
        assertEquals("application/pdf", withTask.type)
        assertTrue(withTask.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(withTask.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        val noTask = GeoVaultOutgoingShare.createViewIntent(uri, "application/pdf", newTask = false)
        assertTrue(noTask.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0)
    }

    @Test
    fun `writeCacheFile writes under the requested subdir`() {
        val app = RuntimeEnvironment.getApplication()
        val file = GeoVaultOutgoingShare.writeCacheFile(
            context = app,
            fileName = "places_export.kmz",
            bytes = byteArrayOf(1, 2, 3),
        )
        assertTrue(file.isFile)
        assertEquals(byteArrayOf(1, 2, 3).toList(), file.readBytes().toList())
        assertTrue(file.parentFile!!.path.endsWith(GeoVaultOutgoingShare.DEFAULT_CACHE_SUBDIR))
        assertEquals(
            "${app.packageName}${GeoVaultOutgoingShare.DEFAULT_FILE_PROVIDER_SUFFIX}",
            GeoVaultOutgoingShare.fileProviderAuthority(app),
        )
    }
}
