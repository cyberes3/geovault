package com.geovault.common.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeoVaultSafDocumentWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val writer = GeoVaultSafDocumentWriter()

    @Test
    fun copyPayload_writesAndFlushes() {
        val payload = tmp.newFile("payload.bin")
        payload.writeBytes(byteArrayOf(4, 5, 6, 7))
        val out = FlushRecordingStream()
        writer.copyPayload(payload, out)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), out.toByteArray())
        assertEquals(1, out.flushCount)
    }

    @Test(expected = IOException::class)
    fun copyPayload_missingFile_fails() {
        writer.copyPayload(File(tmp.root, "missing.bin"), ByteArrayOutputStream())
    }

    private class FlushRecordingStream : ByteArrayOutputStream() {
        var flushCount: Int = 0
        override fun flush() {
            flushCount += 1
            super.flush()
        }
    }
}
