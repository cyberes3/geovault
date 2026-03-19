package com.geovault.tracker

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MainActivityErrorReceiverContractTest {
    @Test
    fun extractStreamingErrorMessage_returnsMessageForStreamingErrorAction() {
        val intent = Intent(LiveTrackStreamingService.ACTION_STREAMING_ERROR).apply {
            putExtra(LiveTrackStreamingService.EXTRA_STREAMING_ERROR_MESSAGE, "stream failed")
        }
        assertEquals("stream failed", MainActivity.extractStreamingErrorMessage(intent))
    }

    @Test
    fun extractStreamingErrorMessage_ignoresBlankOrWrongAction() {
        val wrongAction = Intent("other.action").apply {
            putExtra(LiveTrackStreamingService.EXTRA_STREAMING_ERROR_MESSAGE, "stream failed")
        }
        val blankMessage = Intent(LiveTrackStreamingService.ACTION_STREAMING_ERROR).apply {
            putExtra(LiveTrackStreamingService.EXTRA_STREAMING_ERROR_MESSAGE, " ")
        }
        assertNull(MainActivity.extractStreamingErrorMessage(wrongAction))
        assertNull(MainActivity.extractStreamingErrorMessage(blankMessage))
    }
}
