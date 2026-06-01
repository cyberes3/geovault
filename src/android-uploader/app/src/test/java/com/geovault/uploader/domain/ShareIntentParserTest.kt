package com.geovault.uploader.domain

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentParserTest {

    @Test
    fun sendAction_parsesSingleUri() {
        val uri = Uri.parse("content://test/file.kml")
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        val payload = ShareIntentParser.parse(intent)
        assertEquals(listOf(uri), payload.uris)
    }

    @Test
    fun sendMultipleAction_parsesAllUris() {
        val first = Uri.parse("content://test/one.kml")
        val second = Uri.parse("content://test/two.gpx")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
        }
        val payload = ShareIntentParser.parse(intent)
        assertEquals(listOf(first, second), payload.uris)
    }

    @Test
    fun rejectedNamesExtra_isParsed() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putStringArrayListExtra(
                ShareIntentParser.EXTRA_REJECTED_FILE_NAMES,
                arrayListOf("bad.pdf"),
            )
        }
        val payload = ShareIntentParser.parse(intent)
        assertEquals(listOf("bad.pdf"), payload.rejectedFileNames)
    }
}
