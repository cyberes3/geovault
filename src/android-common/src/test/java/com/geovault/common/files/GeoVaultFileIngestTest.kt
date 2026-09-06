package com.geovault.common.files

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoVaultFileIngestTest {

    @Test
    fun ingest_empty_isEmpty() {
        val ingest = GeoVaultFileIngest(
            context = RuntimeEnvironment.getApplication(),
            catalog = GeoVaultUploadFileTypes.catalog,
        )
        val result = ingest.ingest(emptyList(), GeoVaultFileRef.Source.Picker)
        assertTrue(result.accepted.isEmpty())
        assertTrue(result.rejectedFileNames.isEmpty())
    }

    @Test
    fun ingest_rejectsUnsupportedExtension() {
        val ingest = GeoVaultFileIngest(
            context = RuntimeEnvironment.getApplication(),
            catalog = GeoVaultUploadFileTypes.catalog,
        )
        val uri = Uri.parse("content://test/notes.txt")
        val result = ingest.ingest(listOf(uri), GeoVaultFileRef.Source.Picker)
        assertTrue(result.accepted.isEmpty())
        assertEquals(listOf("notes.txt"), result.rejectedFileNames)
    }
}
