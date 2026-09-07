package com.geovault.common.intent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Share-sheet session: relocate if embedded, extract URIs, consume leftovers,
 * remember a standalone session, and finish or return to the sender.
 */
class GeoVaultShareSession {
    var startedFromShare: Boolean = false
        private set

    fun begin(activity: Activity, savedInstanceState: Bundle?): GeoVaultShareLaunchDecision {
        val decision = GeoVaultShareLaunch.decide(
            isTaskRoot = activity.isTaskRoot,
            intent = activity.intent,
            savedInstanceState = savedInstanceState,
        )
        if (decision is GeoVaultShareLaunchDecision.Continue) {
            startedFromShare = decision.startedFromShare
        }
        return decision
    }

    /**
     * Relocates an embedded share and returns false. True means this instance should compose.
     */
    fun beginOrRelocate(activity: Activity, savedInstanceState: Bundle?): Boolean {
        return when (begin(activity, savedInstanceState)) {
            GeoVaultShareLaunchDecision.RelocateToStandaloneTask -> {
                GeoVaultShareLaunch.relocateToStandaloneTask(activity)
                false
            }
            is GeoVaultShareLaunchDecision.Continue -> true
        }
    }

    /**
     * Call after the host has ingested the launch intent and is showing standalone UI.
     * Must not run before ingest: a cold-start share would then look already-running.
     */
    fun onStandaloneUiReady() {
        GeoVaultShareLaunch.markStandaloneSessionEstablished()
    }

    fun persist(outState: Bundle) {
        GeoVaultShareLaunch.persist(outState, startedFromShare)
    }

    fun consumeIncoming(intent: Intent?): List<Uri> {
        val uris = GeoVaultIncomingFileIntents.urisFrom(intent)
        GeoVaultIncomingFileIntents.consume(intent)
        return uris
    }

    fun finish(activity: Activity) {
        GeoVaultShareLaunch.finishShareSession(activity)
    }

    fun returnToSender(activity: Activity) {
        GeoVaultShareLaunch.returnToSender(activity)
    }

    companion object {
        fun keepHostOpen(deliveredToRunningInstance: Boolean, intent: Intent?): Boolean {
            return deliveredToRunningInstance || GeoVaultShareLaunch.shouldReturnToSender(intent)
        }
    }
}
