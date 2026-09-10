package com.geovault.common.files

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultSafExportSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: GeoVaultSafPendingExportStore
    private lateinit var writer: RecordingWriter
    private val successes = mutableListOf<Pair<Uri, GeoVaultStagedSafExport>>()
    private val failures = mutableListOf<Pair<String, ((String) -> Unit)?>>()

    @Before
    fun setUp() {
        store = GeoVaultSafPendingExportStore(tmp.newFolder("saf-pending"))
        writer = RecordingWriter()
    }

    @Test
    fun complete_writesStagedPayload() = runTest {
        val session = createSession()
        session.stage(sampleRequest(byteArrayOf(1, 2)), "Export failed")
        val uri = Uri.parse("content://export/out.kml")
        session.complete(uri)
        advanceUntilIdle()
        assertEquals(1, writer.writes.size)
        assertEquals(uri, writer.writes[0].first)
        assertArrayEquals(byteArrayOf(1, 2), writer.writes[0].second)
        assertEquals(uri, successes[0].first)
        assertNull(store.consume())
        assertTrue(writer.deletes.isEmpty())
    }

    @Test
    fun cancel_clearsSlotAndDropsListener() = runTest {
        val session = createSession()
        var called = false
        session.stage(sampleRequest(byteArrayOf(3)), "Export failed") { called = true }
        session.cancel()
        assertNull(store.consume())
        session.complete(Uri.parse("content://export/out.kml"))
        advanceUntilIdle()
        assertEquals(1, writer.deletes.size)
        assertEquals(false, called)
        assertEquals("Export failed", failures[0].first)
    }

    @Test
    fun complete_emptyStore_deletesOrphanAndFails() = runTest {
        val session = createSession()
        val uri = Uri.parse("content://export/empty.kml")
        session.complete(uri)
        advanceUntilIdle()
        assertEquals(listOf(uri), writer.deletes)
        assertTrue(writer.writes.isEmpty())
        assertEquals(GeoVaultSafExportSession.DEFAULT_WRITE_FAILED_MESSAGE, failures[0].first)
    }

    @Test
    fun complete_writeFailure_deletesOrphanAndReportsPersistedMessage() = runTest {
        writer.writeError = IllegalStateException("disk full")
        val session = createSession()
        session.stage(sampleRequest(byteArrayOf(8)), "Could not write KML")
        val uri = Uri.parse("content://export/fail.kml")
        session.complete(uri)
        advanceUntilIdle()
        assertEquals(listOf(uri), writer.deletes)
        assertEquals("Could not write KML", failures[0].first)
        assertNull(store.consume())
    }

    @Test
    fun complete_nullUri_cancelsWithoutFailure() = runTest {
        val session = createSession()
        session.stage(sampleRequest(byteArrayOf(1)), "Export failed")
        session.complete(null)
        advanceUntilIdle()
        assertNull(store.consume())
        assertTrue(writer.writes.isEmpty())
        assertTrue(writer.deletes.isEmpty())
        assertTrue(failures.isEmpty())
    }

    private fun TestScope.createSession(): GeoVaultSafExportSession {
        val dispatcher = UnconfinedTestDispatcher()
        return GeoVaultSafExportSession(
            store = store,
            writer = writer,
            resolver = RuntimeEnvironment.getApplication().contentResolver,
            ioScope = backgroundScope,
            mainDispatcher = dispatcher,
            notifySuccess = { uri, staged -> successes += uri to staged },
            notifyFailure = { message, listener -> failures += message to listener },
        )
    }

    private fun sampleRequest(bytes: ByteArray) = GeoVaultSafExportRequest(
        bytes = bytes,
        suggestedFileName = "job.kml",
        fallbackBaseName = "job",
        extensionWithoutDot = "kml",
        mimeType = "application/vnd.google-earth.kml+xml",
    )

    private class RecordingWriter : GeoVaultSafDocumentWriter() {
        val writes = mutableListOf<Pair<Uri, ByteArray>>()
        val deletes = mutableListOf<Uri>()
        var writeError: Throwable? = null

        override fun write(resolver: ContentResolver, uri: Uri, payload: File) {
            writeError?.let { throw it }
            writes += uri to payload.readBytes()
        }

        override fun deleteDocument(resolver: ContentResolver, uri: Uri) {
            deletes += uri
        }
    }
}
