package com.geovault.tracker.services

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingUiStatusResolverTest {

    @Test
    fun resolve_notRunning_returnsNotTracking() {
        assertEquals(
            TrackingUiStatus.NOT_TRACKING,
            TrackingUiStatusResolver.resolve(
                isRunning = false,
                gpsProviderEnabled = true,
                gpsPaused = false,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolve_providerDisabled_returnsWaitingForGps() {
        assertEquals(
            TrackingUiStatus.WAITING_FOR_GPS,
            TrackingUiStatusResolver.resolve(
                isRunning = true,
                gpsProviderEnabled = false,
                gpsPaused = false,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolve_badFix_returnsLocking() {
        assertEquals(
            TrackingUiStatus.LOCKING,
            TrackingUiStatusResolver.resolve(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsPaused = false,
                lastAccuracyMeters = 100f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolve_gpsPaused_returnsPausedForMotion() {
        assertEquals(
            TrackingUiStatus.PAUSED_FOR_MOTION,
            TrackingUiStatusResolver.resolve(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsPaused = true,
                lastAccuracyMeters = null,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolve_activeAccuracyBlockedEmission_returnsLockingEvenWithHeldGoodAccuracy() {
        assertEquals(
            TrackingUiStatus.LOCKING,
            TrackingUiStatusResolver.resolve(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsPaused = false,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 10f,
                activeAccuracyBlockedEmission = true,
            )
        )
    }

    @Test
    fun resolve_activeAccuracyBlockedEmission_providerDisabledStillWaitsForGps() {
        assertEquals(
            TrackingUiStatus.WAITING_FOR_GPS,
            TrackingUiStatusResolver.resolve(
                isRunning = true,
                gpsProviderEnabled = false,
                gpsPaused = false,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 10f,
                activeAccuracyBlockedEmission = true,
            )
        )
    }

    @Test
    fun resolveForGpsState_lockingState_returnsLocking() {
        assertEquals(
            TrackingUiStatus.LOCKING,
            TrackingUiStatusResolver.resolveForGpsState(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsState = GpsRuntimeState.LOCKING,
                lastAccuracyMeters = null,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolveForGpsState_lockingState_withDisplayedGoodAccuracy_returnsActive() {
        assertEquals(
            TrackingUiStatus.TRACKING_ACTIVE,
            TrackingUiStatusResolver.resolveForGpsState(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsState = GpsRuntimeState.LOCKING,
                lastAccuracyMeters = 8f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolveForGpsState_fallbackPending_withDisplayedGoodAccuracy_returnsActive() {
        assertEquals(
            TrackingUiStatus.TRACKING_ACTIVE,
            TrackingUiStatusResolver.resolveForGpsState(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsState = GpsRuntimeState.FALLBACK_PENDING,
                lastAccuracyMeters = 8f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolveForGpsState_fallbackPending_accuracyBlockedEmission_returnsLocking() {
        assertEquals(
            TrackingUiStatus.LOCKING,
            TrackingUiStatusResolver.resolveForGpsState(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsState = GpsRuntimeState.FALLBACK_PENDING,
                lastAccuracyMeters = 8f,
                effectiveAccuracyThresholdMeters = 10f,
                activeAccuracyBlockedEmission = true,
            )
        )
    }

    @Test
    fun resolveForGpsState_fallbackPending_withDisplayedBadAccuracy_returnsLocking() {
        assertEquals(
            TrackingUiStatus.LOCKING,
            TrackingUiStatusResolver.resolveForGpsState(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsState = GpsRuntimeState.FALLBACK_PENDING,
                lastAccuracyMeters = 80f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolveForGpsState_waitingProvider_returnsWaitingForGps() {
        assertEquals(
            TrackingUiStatus.WAITING_FOR_GPS,
            TrackingUiStatusResolver.resolveForGpsState(
                isRunning = true,
                gpsProviderEnabled = false,
                gpsState = GpsRuntimeState.WAITING_FOR_PROVIDER,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolveForGpsState_waitingProviderPaused_returnsWaitingForGps() {
        assertEquals(
            TrackingUiStatus.WAITING_FOR_GPS,
            TrackingUiStatusResolver.resolveForGpsState(
                isRunning = true,
                gpsProviderEnabled = false,
                gpsState = GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolveForGpsState_waitingProvider_withGpsEnabled_stillReturnsWaiting() {
        assertEquals(
            TrackingUiStatus.WAITING_FOR_GPS,
            TrackingUiStatusResolver.resolveForGpsState(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsState = GpsRuntimeState.WAITING_FOR_PROVIDER,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }
}
