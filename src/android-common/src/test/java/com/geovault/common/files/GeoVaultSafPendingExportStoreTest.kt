package com.geovault.common.files

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeoVaultSafPendingExportStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var slotDir: File
    private lateinit var store: GeoVaultSafPendingExportStore

    @Before
    fun setUp() {
        slotDir = tmp.newFolder("saf-pending")
        store = GeoVaultSafPendingExportStore(slotDir)
    }

    @Test
    fun stageThenConsume_returnsPayloadAndMeta() {
        store.stage(sampleRequest(byteArrayOf(1, 2, 3)), "Write failed")
        val staged = store.consume()!!
        assertArrayEquals(byteArrayOf(1, 2, 3), staged.payloadFile.readBytes())
        assertEquals("job.kml", staged.suggestedFileName)
        assertEquals("job", staged.fallbackBaseName)
        assertEquals("kml", staged.extensionWithoutDot)
        assertEquals("application/vnd.google-earth.kml+xml", staged.mimeType)
        assertEquals("Write failed", staged.writeFailedMessage)
    }

    @Test
    fun consumeAfterNewInstance_seesSameSlot() {
        store.stage(sampleRequest(byteArrayOf(9)), "Export failed")
        val restored = GeoVaultSafPendingExportStore(slotDir)
        val staged = restored.consume()!!
        assertArrayEquals(byteArrayOf(9), staged.payloadFile.readBytes())
        assertEquals("job.kml", staged.suggestedFileName)
    }

    @Test
    fun stageReplacesPreviousSlot() {
        store.stage(sampleRequest(byteArrayOf(1)), "first")
        store.stage(sampleRequest(byteArrayOf(2, 2)), "second")
        val staged = store.consume()!!
        assertArrayEquals(byteArrayOf(2, 2), staged.payloadFile.readBytes())
        assertEquals("second", staged.writeFailedMessage)
    }

    @Test
    fun clear_dropsSlot() {
        store.stage(sampleRequest(byteArrayOf(1)), "Export failed")
        store.clear()
        assertNull(store.consume())
    }

    @Test
    fun consume_incompleteSlot_returnsNullAndClears() {
        slotDir.mkdirs()
        File(slotDir, GeoVaultSafPendingExportStore.PAYLOAD_NAME).writeBytes(byteArrayOf(1))
        assertNull(store.consume())
        assertEquals(false, File(slotDir, GeoVaultSafPendingExportStore.PAYLOAD_NAME).exists())
    }

    private fun sampleRequest(bytes: ByteArray) = GeoVaultSafExportRequest(
        bytes = bytes,
        suggestedFileName = "job.kml",
        fallbackBaseName = "job",
        extensionWithoutDot = "kml",
        mimeType = "application/vnd.google-earth.kml+xml",
    )
}
