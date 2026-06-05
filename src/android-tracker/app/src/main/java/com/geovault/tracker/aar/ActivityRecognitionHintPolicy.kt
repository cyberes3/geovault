package com.geovault.tracker.aar

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity

/**
 * Maps GMS activity-transition events to hint actions.
 *
 * Moving activities (IN_VEHICLE, ON_BICYCLE, RUNNING, WALKING, ON_FOOT) arriving as ENTER
 * transitions activate a hint for [HINT_DURATION_MS]. STILL or any EXIT transition clears it.
 */
internal object ActivityRecognitionHintPolicy {

    const val HINT_DURATION_MS = 45_000L

    private val movingActivities = setOf(
        DetectedActivity.IN_VEHICLE,
        DetectedActivity.ON_BICYCLE,
        DetectedActivity.RUNNING,
        DetectedActivity.WALKING,
        DetectedActivity.ON_FOOT,
    )

    fun isMovingEnter(activityType: Int, transitionType: Int): Boolean {
        return transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            && activityType in movingActivities
    }

    fun isClearingTransition(activityType: Int, transitionType: Int): Boolean {
        return transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT
            || (activityType == DetectedActivity.STILL && transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
    }

    fun hintActive(activityType: Int, transitionType: Int): Boolean {
        return isMovingEnter(activityType, transitionType)
    }
}
