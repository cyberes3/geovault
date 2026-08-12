package com.geovault.common.intent

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultIncomingFileIntentsTest {

    @Test
    fun `view action reads content data`() {
        val uri = Uri.parse("content://test/file.kml")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        assertTrue(GeoVaultIncomingFileIntents.isIncomingFileAction(intent))
        assertEquals(listOf(uri), GeoVaultIncomingFileIntents.urisFrom(intent))
    }

    @Test
    fun `view action ignores oauth custom scheme`() {
        val uri = Uri.parse("com.geovault.survey://oauth/callback")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        assertFalse(GeoVaultIncomingFileIntents.isIncomingFileAction(intent))
        assertEquals(emptyList<Uri>(), GeoVaultIncomingFileIntents.urisFrom(intent))
    }

    @Test
    fun `send action reads extra stream`() {
        val uri = Uri.parse("content://test/file.kml")
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        assertEquals(listOf(uri), GeoVaultIncomingFileIntents.urisFrom(intent))
    }

    @Test
    fun `send action falls back to clipData`() {
        val uri = Uri.parse("content://test/clip.kml")
        val intent = Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("file", uri)
        }
        assertEquals(listOf(uri), GeoVaultIncomingFileIntents.urisFrom(intent))
    }

    @Test
    fun `send multiple reads extra stream list`() {
        val first = Uri.parse("content://test/one.kml")
        val second = Uri.parse("content://test/two.gpx")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
        }
        assertEquals(listOf(first, second), GeoVaultIncomingFileIntents.urisFrom(intent))
    }

    @Test
    fun `duplicates are dropped preserving order`() {
        val uri = Uri.parse("content://test/file.kml")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri, uri))
        }
        assertEquals(listOf(uri), GeoVaultIncomingFileIntents.urisFrom(intent))
    }

    @Test
    fun `main and null are ignored`() {
        assertFalse(GeoVaultIncomingFileIntents.isIncomingFileAction(null))
        assertEquals(emptyList<Uri>(), GeoVaultIncomingFileIntents.urisFrom(null))
        val main = Intent(Intent.ACTION_MAIN)
        assertFalse(GeoVaultIncomingFileIntents.isIncomingFileAction(main))
        assertEquals(emptyList<Uri>(), GeoVaultIncomingFileIntents.urisFrom(main))
    }

    @Test
    fun `consume clears action data stream and clip`() {
        val uri = Uri.parse("content://test/file.kml")
        val intent = Intent(Intent.ACTION_SEND).apply {
            data = uri
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("file", uri)
        }
        GeoVaultIncomingFileIntents.consume(intent)
        assertNull(intent.action)
        assertNull(intent.data)
        assertNull(intent.clipData)
        assertNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertFalse(GeoVaultIncomingFileIntents.isIncomingFileAction(intent))
        assertEquals(emptyList<Uri>(), GeoVaultIncomingFileIntents.urisFrom(intent))
    }
}
