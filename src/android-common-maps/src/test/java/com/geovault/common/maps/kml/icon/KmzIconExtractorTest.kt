package com.geovault.common.maps.kml.icon

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KmzIconExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun extractsExactMember() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val kmz = zipFile("icon.png" to payload)
        assertArrayEquals(payload, KmzIconExtractor.extract(kmz, "icon.png"))
    }

    @Test
    fun extractsFilesPrefixedMemberFromBareName() {
        val payload = byteArrayOf(9, 8, 7)
        val kmz = zipFile("files/pin.png" to payload)
        assertArrayEquals(payload, KmzIconExtractor.extract(kmz, "pin.png"))
    }

    @Test
    fun extractsCaseInsensitiveMember() {
        val payload = byteArrayOf(5, 5, 5)
        val kmz = zipFile("Files/Icon.PNG" to payload)
        assertArrayEquals(payload, KmzIconExtractor.extract(kmz, "files/icon.png"))
    }

    @Test
    fun stripsColonSlashPrefix() {
        val payload = byteArrayOf(3, 1, 4)
        val kmz = zipFile("files/icon.png" to payload)
        assertArrayEquals(payload, KmzIconExtractor.extract(kmz, ":/files/icon.png"))
    }

    @Test
    fun missingMember_returnsNull() {
        val kmz = zipFile("other.png" to byteArrayOf(1))
        assertNull(KmzIconExtractor.extract(kmz, "icon.png"))
    }

    @Test
    fun candidatePaths_matchBackendFallbacks() {
        assertEquals(
            listOf("icon.png", "files/icon.png"),
            KmzIconExtractor.candidatePaths("icon.png"),
        )
        assertEquals(
            listOf("files/icon.png", "icon.png"),
            KmzIconExtractor.candidatePaths("files/icon.png"),
        )
        assertEquals(
            listOf(":/files/icon.png", "files/icon.png"),
            KmzIconExtractor.candidatePaths(":/files/icon.png"),
        )
    }

    private fun zipFile(vararg entries: Pair<String, ByteArray>): File {
        val file = tempFolder.newFile("overlay.kmz")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
