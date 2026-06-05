package com.geovault.tracker.aar

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityRecognitionHintPolicyTest {

    private val enter = ActivityTransition.ACTIVITY_TRANSITION_ENTER
    private val exit = ActivityTransition.ACTIVITY_TRANSITION_EXIT

    @Test
    fun `IN_VEHICLE ENTER activates hint`() {
        assertTrue(ActivityRecognitionHintPolicy.hintActive(DetectedActivity.IN_VEHICLE, enter))
        assertTrue(ActivityRecognitionHintPolicy.isMovingEnter(DetectedActivity.IN_VEHICLE, enter))
        assertFalse(ActivityRecognitionHintPolicy.isClearingTransition(DetectedActivity.IN_VEHICLE, enter))
    }

    @Test
    fun `ON_BICYCLE ENTER activates hint`() {
        assertTrue(ActivityRecognitionHintPolicy.hintActive(DetectedActivity.ON_BICYCLE, enter))
    }

    @Test
    fun `WALKING ENTER activates hint`() {
        assertTrue(ActivityRecognitionHintPolicy.hintActive(DetectedActivity.WALKING, enter))
    }

    @Test
    fun `ON_FOOT ENTER activates hint`() {
        assertTrue(ActivityRecognitionHintPolicy.hintActive(DetectedActivity.ON_FOOT, enter))
    }

    @Test
    fun `RUNNING ENTER activates hint`() {
        assertTrue(ActivityRecognitionHintPolicy.hintActive(DetectedActivity.RUNNING, enter))
    }

    @Test
    fun `STILL ENTER is a clearing transition, not an activation`() {
        assertFalse(ActivityRecognitionHintPolicy.hintActive(DetectedActivity.STILL, enter))
        assertFalse(ActivityRecognitionHintPolicy.isMovingEnter(DetectedActivity.STILL, enter))
        assertTrue(ActivityRecognitionHintPolicy.isClearingTransition(DetectedActivity.STILL, enter))
    }

    @Test
    fun `IN_VEHICLE EXIT clears hint`() {
        assertFalse(ActivityRecognitionHintPolicy.hintActive(DetectedActivity.IN_VEHICLE, exit))
        assertFalse(ActivityRecognitionHintPolicy.isMovingEnter(DetectedActivity.IN_VEHICLE, exit))
        assertTrue(ActivityRecognitionHintPolicy.isClearingTransition(DetectedActivity.IN_VEHICLE, exit))
    }

    @Test
    fun `STILL EXIT clears hint`() {
        assertFalse(ActivityRecognitionHintPolicy.hintActive(DetectedActivity.STILL, exit))
        assertTrue(ActivityRecognitionHintPolicy.isClearingTransition(DetectedActivity.STILL, exit))
    }

    @Test
    fun `WALKING EXIT clears hint`() {
        assertFalse(ActivityRecognitionHintPolicy.hintActive(DetectedActivity.WALKING, exit))
        assertTrue(ActivityRecognitionHintPolicy.isClearingTransition(DetectedActivity.WALKING, exit))
    }

    @Test
    fun `hint duration is 45 seconds`() {
        assertTrue(ActivityRecognitionHintPolicy.HINT_DURATION_MS == 45_000L)
    }
}
