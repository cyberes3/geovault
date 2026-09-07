package com.geovault.common.intent

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultShareSessionTest {

    @Before
    fun resetSession() {
        GeoVaultShareLaunch.resetStandaloneSession()
    }

    @Test
    fun consumeIncoming_returnsUrisAndClearsIntent() {
        val uri = Uri.parse("content://test/file.kml")
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        val session = GeoVaultShareSession()
        val uris = session.consumeIncoming(intent)
        assertEquals(listOf(uri), uris)
        assertNull(intent.action)
        assertNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    }

    @Test
    fun onStandaloneUiReady_marks_established() {
        val session = GeoVaultShareSession()
        assertFalse(GeoVaultShareSession.keepHostOpen(deliveredToRunningInstance = false, intent = null))
        session.onStandaloneUiReady()
        assertTrue(GeoVaultShareSession.keepHostOpen(deliveredToRunningInstance = false, intent = null))
    }

    @Test
    fun keepHostOpen_is_true_for_onNewIntent_before_ui_ready() {
        assertTrue(GeoVaultShareSession.keepHostOpen(deliveredToRunningInstance = true, intent = null))
    }
}
