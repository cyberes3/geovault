package com.geovault.common.maps.kml.icon

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class KmlIconColorResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun remoteHref_usesFetchedBytes() {
        val png = solidPng(255, 0, 0)
        val resolver = KmlIconColorResolver(
            remoteFetcher = { url -> png.takeIf { url.endsWith("red.png") } },
        )
        val colors = resolver.resolve(listOf("https://example.com/red.png", "https://example.com/red.png"))
        assertEquals(1, colors.size)
        assertEquals("#f00000", colors.getValue("https://example.com/red.png"))
    }

    @Test
    fun fetchFailureAndRelativeKmlHref_areOmitted() {
        val resolver = KmlIconColorResolver(remoteFetcher = { null })
        val colors = resolver.resolve(
            listOf("https://example.com/missing.png", "files/icon.png", "file:///tmp/x.png"),
        )
        assertTrue(colors.isEmpty())
    }

    @Test
    fun kmzRelativeHref_extractsEmbeddedIcon() {
        val png = solidPng(0, 0, 255)
        val kmz = tempFolder.newFile("overlay.kmz")
        ZipOutputStream(kmz.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("files/pin.png"))
            zip.write(png)
            zip.closeEntry()
        }
        val resolver = KmlIconColorResolver(remoteFetcher = { error("must not fetch") })
        val colors = resolver.resolve(listOf("files/pin.png"), kmzFile = kmz)
        assertEquals("#0000f0", colors.getValue("files/pin.png"))
    }

    private fun solidPng(red: Int, green: Int, blue: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(255, red, green, blue))
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        bitmap.recycle()
        return out.toByteArray()
    }
}
