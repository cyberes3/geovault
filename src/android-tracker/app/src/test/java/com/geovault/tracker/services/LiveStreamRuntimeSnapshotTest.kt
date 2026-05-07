package com.geovault.tracker.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamRuntimeSnapshotTest {

    @Test
    fun wantsSubscription_trueForWantedIntentRegardlessOfHealth() {
        val starting = LiveStreamRuntimeSnapshot(
            intent = StreamingIntent.Wanted(setOf("remote")),
            health = StreamingHealth.Starting,
            activeTrackerIds = setOf("remote"),
        )
        val reconnecting = starting.copy(health = StreamingHealth.Reconnecting)
        val failedTransient = starting.copy(health = StreamingHealth.FailedTransient)
        val running = starting.copy(health = StreamingHealth.Running)

        assertTrue(starting.wantsSubscription)
        assertTrue(reconnecting.wantsSubscription)
        assertTrue(failedTransient.wantsSubscription)
        assertTrue(running.wantsSubscription)
    }

    @Test
    fun wantsSubscription_falseForIdleIntent() {
        val snapshot = LiveStreamRuntimeSnapshot(
            intent = StreamingIntent.Idle,
            health = StreamingHealth.Stopped,
        )
        assertFalse(snapshot.wantsSubscription)
    }

    @Test
    fun subscriptionHealthy_trueOnlyForRunningHealth() {
        val running = LiveStreamRuntimeSnapshot(
            intent = StreamingIntent.Wanted(setOf("a")),
            health = StreamingHealth.Running,
            activeTrackerIds = setOf("a"),
        )
        val starting = running.copy(health = StreamingHealth.Starting)
        val reconnecting = running.copy(health = StreamingHealth.Reconnecting)

        assertTrue(running.subscriptionHealthy)
        assertFalse(starting.subscriptionHealthy)
        assertFalse(reconnecting.subscriptionHealthy)
    }

    @Test
    fun subscriptionEnded_trueForStoppedAndFailedPermanent() {
        val stopped = LiveStreamRuntimeSnapshot(
            intent = StreamingIntent.Idle,
            health = StreamingHealth.Stopped,
        )
        val permanent = LiveStreamRuntimeSnapshot(
            intent = StreamingIntent.Wanted(setOf("a")),
            health = StreamingHealth.FailedPermanent,
        )
        val transient = LiveStreamRuntimeSnapshot(
            intent = StreamingIntent.Wanted(setOf("a")),
            health = StreamingHealth.FailedTransient,
        )

        assertTrue(stopped.subscriptionEnded)
        assertTrue(permanent.subscriptionEnded)
        assertFalse(transient.subscriptionEnded)
    }

    @Test
    fun shouldOwnForeground_trueWhenWantedAndNotStopped() {
        val starting = LiveStreamRuntimeSnapshot(
            intent = StreamingIntent.Wanted(setOf("a")),
            health = StreamingHealth.Starting,
        )
        val reconnecting = starting.copy(health = StreamingHealth.Reconnecting)
        val running = starting.copy(health = StreamingHealth.Running)
        val failedTransient = starting.copy(health = StreamingHealth.FailedTransient)
        val stopped = starting.copy(health = StreamingHealth.Stopped)

        assertTrue(starting.shouldOwnForeground)
        assertTrue(reconnecting.shouldOwnForeground)
        assertTrue(running.shouldOwnForeground)
        assertTrue(failedTransient.shouldOwnForeground)
        assertFalse(stopped.shouldOwnForeground)
    }

}
