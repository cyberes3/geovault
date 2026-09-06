package com.geovault.common.intent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Share-sheet session: relocate if embedded, extract URIs, consume leftovers, finish cleanly.
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
            if (startedFromShare) {
                GeoVaultShareLaunch.markStandaloneSessionEstablished()
            }
        }
        return decision
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
}
