package com.geovault.common.intent

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle

/**
 * Outcome of [GeoVaultShareLaunch.decide] for an activity that accepts VIEW/SEND files.
 */
sealed interface GeoVaultShareLaunchDecision {
    /** The activity is sitting in another app's task and must hop to its own. */
    data object RelocateToStandaloneTask : GeoVaultShareLaunchDecision

    /**
     * Continue composing this instance.
     *
     * [startedFromShare] is true only when *this* instance was created from a file intent,
     * including after a relocate hop. A later [Activity.onNewIntent] share does not change it.
     */
    data class Continue(val startedFromShare: Boolean) : GeoVaultShareLaunchDecision
}

/**
 * Shared launch/task policy for apps that receive files from other apps (share sheet, VIEW).
 *
 * Senders such as Google Earth can start the target activity inside their own task even when
 * the target uses `singleTask`. This object:
 *  - relocates an embedded incoming file into a standalone task before the host ingests it,
 *  - remembers whether the instance started from that share across process death,
 *  - finishes a share-started session without taking the sender's task down,
 *  - or backgrounds this task so the sender is visible again.
 */
object GeoVaultShareLaunch {
    const val EXTRA_STANDALONE_SESSION_ALREADY_RUNNING =
        "com.geovault.common.intent.EXTRA_STANDALONE_SESSION_ALREADY_RUNNING"

    private const val STATE_STARTED_FROM_SHARE =
        "com.geovault.common.intent.STATE_STARTED_FROM_SHARE"

    @Volatile
    private var standaloneSessionEstablished = false

    fun decide(
        isTaskRoot: Boolean,
        intent: Intent?,
        savedInstanceState: Bundle?,
    ): GeoVaultShareLaunchDecision {
        val incoming = GeoVaultIncomingFileIntents.isIncomingFileAction(intent)
        if (incoming && !isTaskRoot) {
            return GeoVaultShareLaunchDecision.RelocateToStandaloneTask
        }
        val startedFromShare = if (savedInstanceState?.containsKey(STATE_STARTED_FROM_SHARE) == true) {
            savedInstanceState.getBoolean(STATE_STARTED_FROM_SHARE)
        } else {
            incoming
        }
        return GeoVaultShareLaunchDecision.Continue(startedFromShare)
    }

    fun persist(outState: Bundle, startedFromShare: Boolean) {
        outState.putBoolean(STATE_STARTED_FROM_SHARE, startedFromShare)
    }

    fun relaunchStandaloneIntent(source: Intent, component: ComponentName): Intent {
        return Intent(source).apply {
            this.component = component
            // NEW_TASK + CLEAR_TOP reuses an existing singleTask instance via onNewIntent.
            // CLEAR_TASK would destroy that instance and start a fresh share-only session.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            putExtra(EXTRA_STANDALONE_SESSION_ALREADY_RUNNING, standaloneSessionEstablished)
        }
    }

    /**
     * True after this process has already shown standalone host UI. A share that hops out of
     * the sender's task still arrives as a new [android.app.Activity.onCreate]; this is what
     * distinguishes that hop from a cold start.
     */
    fun shouldReturnToSender(intent: Intent?): Boolean {
        if (standaloneSessionEstablished) {
            return true
        }
        return intent?.getBooleanExtra(EXTRA_STANDALONE_SESSION_ALREADY_RUNNING, false) == true
    }

    fun markStandaloneSessionEstablished() {
        standaloneSessionEstablished = true
    }

    internal fun resetStandaloneSession() {
        standaloneSessionEstablished = false
    }

    fun relocateToStandaloneTask(activity: Activity) {
        val component = checkNotNull(activity.componentName) {
            "GeoVaultShareLaunch.relocateToStandaloneTask requires a component name"
        }
        activity.startActivity(relaunchStandaloneIntent(activity.intent, component))
        activity.finish()
    }

    /**
     * Leaves this activity. [Activity.finishAndRemoveTask] when we own the task; [Activity.finish]
     * when still stacked on another app so the sender is not removed from recents.
     */
    fun finishShareSession(activity: Activity) {
        if (activity.isTaskRoot) {
            activity.finishAndRemoveTask()
        } else {
            activity.finish()
        }
    }

    /**
     * Sends this task to the background so the sharing app is visible again, without
     * destroying this instance.
     */
    fun returnToSender(activity: Activity) {
        activity.moveTaskToBack(true)
    }
}
