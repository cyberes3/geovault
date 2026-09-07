package com.geovault.common.intent

import android.content.Intent
import android.net.Uri
import com.geovault.common.files.GeoVaultFileIngest
import com.geovault.common.files.GeoVaultFileRef
import com.geovault.common.files.GeoVaultUploadFileTypes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultIncomingFileIntakeTest {

    @Test
    fun `main intent is ignored and not consumed`() {
        val intent = Intent(Intent.ACTION_MAIN)
        val result = intake().ingest(intent)
        assertEquals(GeoVaultIncomingIntakeResult.Ignored, result)
        assertEquals(Intent.ACTION_MAIN, intent.action)
    }

    @Test
    fun `send intent accepts catalog files and consumes the payload`() {
        val kml = File.createTempFile("track", ".kml").apply { writeText("<kml/>") }
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(kml))
        }
        val result = intake().ingest(intent) as GeoVaultIncomingIntakeResult.Processed
        assertEquals(1, result.accepted.size)
        assertTrue(result.rejectedFileNames.isEmpty())
        assertEquals(GeoVaultFileRef.Source.Intent, result.accepted.single().source)
        assertNull(intent.action)
    }

    @Test
    fun `picker ingest keeps picker source`() {
        val kml = File.createTempFile("picked", ".kml").apply { writeText("<kml/>") }
        val result = intake().ingest(
            uris = listOf(Uri.fromFile(kml)),
            source = GeoVaultFileRef.Source.Picker,
        ) as GeoVaultIncomingIntakeResult.Processed
        assertEquals(GeoVaultFileRef.Source.Picker, result.accepted.single().source)
    }

    @Test
    fun `unsupported files are rejected`() {
        val pdf = File.createTempFile("notes", ".pdf").apply { writeText("x") }
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(pdf))
        }
        val result = intake().ingest(intent) as GeoVaultIncomingIntakeResult.Processed
        assertTrue(result.accepted.isEmpty())
        assertEquals(listOf(pdf.name), result.rejectedFileNames)
    }

    private fun intake(): GeoVaultIncomingFileIntake {
        return GeoVaultIncomingFileIntake(
            GeoVaultFileIngest(
                context = RuntimeEnvironment.getApplication(),
                catalog = GeoVaultUploadFileTypes.catalog,
                stageLongLivedGrants = false,
            )
        )
    }
}
