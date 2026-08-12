package com.geovault.common.files

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultIncomingFileStagerTest {

    @Test
    fun `stage copies bytes into an isolated incoming directory`() {
        val app = RuntimeEnvironment.getApplication()
        val source = File.createTempFile("survey", ".kml").apply {
            writeText("<kml>ok</kml>")
        }
        val metadata = GeoVaultOpenableUriMetadata(app.contentResolver)
        val stager = GeoVaultIncomingFileStager(cacheDir = app.cacheDir, metadata = metadata)

        val staged = stager.stage(app.contentResolver, Uri.fromFile(source))

        assertEquals(source.name, staged.displayName)
        val dest = File(staged.path)
        assertTrue(dest.isFile)
        assertEquals("<kml>ok</kml>", dest.readText())
        assertTrue(dest.parentFile!!.path.contains(GeoVaultIncomingFileStager.INCOMING_SUBDIR))

        stager.delete(staged)
        assertFalse(dest.exists())
        assertFalse(dest.parentFile!!.exists())
    }

    @Test
    fun `sanitizeFileName strips path separators`() {
        assertEquals("job.kml", GeoVaultIncomingFileStager.sanitizeFileName("/tmp/job.kml"))
        assertEquals("incoming", GeoVaultIncomingFileStager.sanitizeFileName("   "))
    }
}
