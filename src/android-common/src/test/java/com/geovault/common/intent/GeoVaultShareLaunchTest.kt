package com.geovault.common.intent

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultShareLaunchTest {

    @Before
    fun resetSession() {
        GeoVaultShareLaunch.resetStandaloneSession()
    }

    @Test
    fun `incoming file not task root relocates`() {
        val decision = GeoVaultShareLaunch.decide(
            isTaskRoot = false,
            intent = sendIntent(),
            savedInstanceState = null,
        )
        assertEquals(GeoVaultShareLaunchDecision.RelocateToStandaloneTask, decision)
    }

    @Test
    fun `incoming file at task root continues as share started`() {
        val decision = GeoVaultShareLaunch.decide(
            isTaskRoot = true,
            intent = sendIntent(),
            savedInstanceState = null,
        )
        assertEquals(GeoVaultShareLaunchDecision.Continue(startedFromShare = true), decision)
    }

    @Test
    fun `launcher main does not relocate even when not task root`() {
        val decision = GeoVaultShareLaunch.decide(
            isTaskRoot = false,
            intent = Intent(Intent.ACTION_MAIN),
            savedInstanceState = null,
        )
        assertEquals(GeoVaultShareLaunchDecision.Continue(startedFromShare = false), decision)
    }

    @Test
    fun `restored share session wins over consumed intent`() {
        val outState = Bundle()
        GeoVaultShareLaunch.persist(outState, startedFromShare = true)
        val consumed = sendIntent()
        GeoVaultIncomingFileIntents.consume(consumed)

        val decision = GeoVaultShareLaunch.decide(
            isTaskRoot = true,
            intent = consumed,
            savedInstanceState = outState,
        )
        assertEquals(GeoVaultShareLaunchDecision.Continue(startedFromShare = true), decision)
    }

    @Test
    fun `relaunch intent keeps payload and adds standalone flags`() {
        val uri = Uri.parse("content://earth/exports/job.kml")
        val source = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.google-earth.kml+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val component = ComponentName("com.geovault.survey", "com.geovault.survey.MainActivity")

        val relaunch = GeoVaultShareLaunch.relaunchStandaloneIntent(source, component)

        assertEquals(Intent.ACTION_SEND, relaunch.action)
        assertEquals(uri, relaunch.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(component, relaunch.component)
        assertTrue(relaunch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(relaunch.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(relaunch.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK == 0)
        assertTrue(relaunch.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(
            relaunch.getBooleanExtra(
                GeoVaultShareLaunch.EXTRA_STANDALONE_SESSION_ALREADY_RUNNING,
                true,
            ),
        )
    }

    @Test
    fun `cold start does not return to sender`() {
        assertFalse(GeoVaultShareLaunch.shouldReturnToSender(sendIntent()))
    }

    @Test
    fun `established standalone session returns to sender`() {
        GeoVaultShareLaunch.markStandaloneSessionEstablished()
        assertTrue(GeoVaultShareLaunch.shouldReturnToSender(sendIntent()))
    }

    @Test
    fun `relocate extra marks an already running session`() {
        GeoVaultShareLaunch.markStandaloneSessionEstablished()
        val relaunch = GeoVaultShareLaunch.relaunchStandaloneIntent(
            sendIntent(),
            ComponentName("com.geovault.survey", "com.geovault.survey.MainActivity"),
        )
        GeoVaultShareLaunch.resetStandaloneSession()
        assertTrue(GeoVaultShareLaunch.shouldReturnToSender(relaunch))
    }

    @Test
    fun `persist writes a restoreable flag`() {
        val outState = Bundle()
        GeoVaultShareLaunch.persist(outState, startedFromShare = false)
        val decision = GeoVaultShareLaunch.decide(
            isTaskRoot = true,
            intent = sendIntent(),
            savedInstanceState = outState,
        )
        assertEquals(GeoVaultShareLaunchDecision.Continue(startedFromShare = false), decision)
    }

    private fun sendIntent(): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://earth/exports/job.kml"))
        }
    }
}
