package com.geovault.uploader.domain

import android.net.Uri
import com.geovault.common.files.GeoVaultFileRef
import com.geovault.common.net.GeoVaultApiFailure
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class ImportUploadEngineTest {

    @Test
    fun allSuccess_finishesWithNoWorkLeft() = runTest {
        val uploader = ScriptedUploader(ImportUploadOutcome.Success, ImportUploadOutcome.Success)
        val engine = ImportUploadEngine(uploader)
        val emissions = engine.run(sessionOf("a.kml", "b.kml"), suffixEnabled = true).toList()
        val last = emissions.last()
        val finished = last.phase as UploadSessionPhase.Finished
        assertEquals(2, finished.succeeded)
        assertEquals(0, finished.failed)
        assertTrue(last.workItems().isEmpty())
        assertEquals(listOf("a_android_upload.kml", "b_android_upload.kml"), uploader.filenames)
    }

    @Test
    fun partialFail_leavesFailedRetryable() = runTest {
        val uploader = ScriptedUploader(
            ImportUploadOutcome.Success,
            ImportUploadOutcome.Failed(GeoVaultApiFailure(500, "nope", "importUpload")),
        )
        val last = ImportUploadEngine(uploader).run(sessionOf("a.kml", "b.kml"), suffixEnabled = false).toList().last()
        val finished = last.phase as UploadSessionPhase.Finished
        assertEquals(1, finished.succeeded)
        assertEquals(1, finished.failed)
        assertEquals(listOf(UploadItemId("1")), last.workItems())
        assertEquals(listOf("a.kml", "b.kml"), uploader.filenames)
    }

    @Test
    fun cancel_stopsAndRequeuesCurrent() = runTest {
        val uploader = ScriptedUploader(
            ImportUploadOutcome.Success,
            ImportUploadOutcome.Cancelled,
            ImportUploadOutcome.Success,
        )
        val last = ImportUploadEngine(uploader).run(sessionOf("a.kml", "b.kml", "c.kml"), suffixEnabled = false).toList().last()
        assertEquals(UploadSessionPhase.Idle, last.phase)
        assertEquals(UploadItemState.Succeeded, last.items.getValue(UploadItemId("0")).state)
        assertEquals(UploadItemState.Queued, last.items.getValue(UploadItemId("1")).state)
        assertEquals(UploadItemState.Queued, last.items.getValue(UploadItemId("2")).state)
        assertEquals(2, uploader.filenames.size)
    }

    @Test
    fun emptyWork_emitsIdleWithoutUploading() = runTest {
        val uploader = ScriptedUploader()
        val succeeded = sessionOf("done.kml").let { session ->
            session.copy(
                items = session.items.mapValues { it.value.copy(state = UploadItemState.Succeeded) },
            )
        }
        val emissions = ImportUploadEngine(uploader).run(succeeded, suffixEnabled = false).toList()
        assertEquals(1, emissions.size)
        assertEquals(UploadSessionPhase.Idle, emissions.single().phase)
        assertTrue(uploader.filenames.isEmpty())
    }

    private fun sessionOf(vararg names: String): UploadSession {
        val items = names.mapIndexed { index, name ->
            UploadItem(
                id = UploadItemId(index.toString()),
                file = GeoVaultFileRef(
                    uri = Uri.parse("content://geovault.test/$index"),
                    displayName = name,
                    mimeType = "application/vnd.google-earth.kml+xml",
                    extension = "kml",
                    sizeBytes = 1L,
                    source = GeoVaultFileRef.Source.Picker,
                ),
                displayName = name,
                sizeBytes = 1L,
                modifiedAtMs = null,
            )
        }
        return UploadSession().reduce(UploadEvent.ItemsAppended(items))
    }

    private class ScriptedUploader(
        private vararg val outcomes: ImportUploadOutcome,
    ) : ImportFileUploader {
        val filenames = mutableListOf<String>()
        private var index = 0

        override suspend fun upload(uri: Uri, finalFilename: String, generation: Long): ImportUploadOutcome {
            filenames.add(finalFilename)
            return outcomes[index++]
        }

        override fun cancelActiveUpload(generation: Long) = Unit
    }
}
