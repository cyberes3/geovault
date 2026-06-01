package com.geovault.uploader.domain

import android.net.Uri
import com.geovault.uploader.model.FileQueueItem
import com.geovault.uploader.model.FileStatus
import com.geovault.uploader.model.ImportUploadOutcome
import com.geovault.uploader.presentation.QueueUploadState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportUploadQueueTest {

    @Test
    fun cancelOnSecondFile_restoresInFlightToPending() = runBlocking {
        val uris = listOf(
            Uri.parse("content://test/one.kml"),
            Uri.parse("content://test/two.kml"),
        )
        val items = uris.map { uri ->
            FileQueueItem(uri = uri, filename = uri.lastPathSegment!!, sizeBytes = 100L)
        }
        var uploadIndex = 0
        val uploader = object : ImportFileUploader {
            override suspend fun warmAccessToken(): ImportUploadOutcome? = null

            override suspend fun upload(uri: Uri, finalFilename: String): ImportUploadOutcome {
                uploadIndex++
                return if (uploadIndex == 2) {
                    ImportUploadOutcome.Cancelled
                } else {
                    ImportUploadOutcome.Success
                }
            }

            override fun cancelActiveUpload() = Unit
        }
        val queue = ImportUploadQueue(uploader)
        var latest = QueueUploadState(items = items)
        latest = queue.runBatch(latest, suffixEnabled = false) { latest = it }

        assertTrue(latest.uploadCancelled)
        assertFalse(latest.isUploading)
        assertEquals(FileStatus.PENDING, latest.items[1].status)
        assertEquals(FileStatus.SUCCESS, latest.items[0].status)
        assertEquals("Upload cancelled", latest.statusMessage)
    }
}
