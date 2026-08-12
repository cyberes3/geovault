package com.geovault.common.files

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultOpenableUriMetadataTest {

    private val metadata = GeoVaultOpenableUriMetadata(
        RuntimeEnvironment.getApplication().contentResolver,
    )

    @Test
    fun `file uri display name uses the path leaf`() {
        val file = File.createTempFile("points", ".csv")
        val uri = Uri.fromFile(file)
        assertEquals(file.name, metadata.displayName(uri))
        assertEquals(file.name, metadata.displayNameOrNull(uri))
    }

    @Test
    fun `missing display name falls back to last path segment`() {
        val uri = Uri.parse("content://authority/document/leaf.kml")
        assertEquals("leaf.kml", metadata.displayName(uri))
    }

    @Test
    fun `file uri size matches the file length`() {
        val file = File.createTempFile("sized", ".txt")
        file.writeBytes(ByteArray(12) { 1 })
        val uri = Uri.fromFile(file)
        assertEquals(12L, metadata.sizeBytes(uri))
    }

    @Test
    fun `unknown content uri size is zero`() {
        val uri = Uri.parse("content://missing/none")
        assertEquals(0L, metadata.sizeBytes(uri))
        assertNull(metadata.displayNameOrNull(uri))
    }
}
