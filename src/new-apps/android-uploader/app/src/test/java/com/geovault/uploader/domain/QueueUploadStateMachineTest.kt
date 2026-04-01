package com.geovault.uploader.domain

import com.geovault.uploader.presentation.QueueUploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueUploadStateMachineTest {

    @Test
    fun startUpload_setsUploadingAndProgressBounds() {
        val initial = QueueUploadState()

        val updated = QueueUploadStateMachine.startUpload(initial, validCount = 3)

        assertTrue(updated.isUploading)
        assertFalse(updated.uploadCancelled)
        assertEquals(0, updated.progressCurrent)
        assertEquals(3, updated.progressMax)
        assertEquals("", updated.statusMessage)
    }

    @Test
    fun cancelUpload_resetsProgressAndSetsCancelledMessage() {
        val initial = QueueUploadState(isUploading = true, progressCurrent = 2, progressMax = 4)

        val updated = QueueUploadStateMachine.cancelUpload(initial)

        assertFalse(updated.isUploading)
        assertTrue(updated.uploadCancelled)
        assertEquals(0, updated.progressCurrent)
        assertEquals(0, updated.progressMax)
        assertEquals("Upload cancelled", updated.statusMessage)
    }

    @Test
    fun finishUpload_usesSummaryFormatterOutput() {
        val initial = QueueUploadState(isUploading = true, progressCurrent = 2, progressMax = 2)

        val updated = QueueUploadStateMachine.finishUpload(
            state = initial,
            items = emptyList(),
            succeeded = 2,
            failed = 0,
            cancelled = false
        )

        assertFalse(updated.isUploading)
        assertEquals(2, updated.progressCurrent)
        assertEquals(2, updated.progressMax)
        assertEquals("All 2 files uploaded successfully!", updated.statusMessage)
    }
}
