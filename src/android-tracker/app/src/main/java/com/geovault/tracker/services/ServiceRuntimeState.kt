package com.geovault.tracker.services
import com.geovault.tracker.positioning.config.GpsRuntimeState

import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.TrackingLifecycleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrackingMotionMode {
    WALKING,
    BIKING,
    DRIVING,
}

enum class TrackingUiStatus {
    NOT_TRACKING,
    WAITING_FOR_GPS,
    LOCKING,
    PAUSED_FOR_MOTION,
    TRACKING_ACTIVE
}

object TrackingUiStatusResolver {
    @JvmStatic
    fun resolve(
        isRunning: Boolean,
        gpsProviderEnabled: Boolean,
        gpsPaused: Boolean,
        lastAccuracyMeters: Float?,
        effectiveAccuracyThresholdMeters: Float,
        activeAccuracyBlockedEmission: Boolean = false,
    ): TrackingUiStatus {
        if (!isRunning) return TrackingUiStatus.NOT_TRACKING
        if (!gpsProviderEnabled) return TrackingUiStatus.WAITING_FOR_GPS
        if (activeAccuracyBlockedEmission) return TrackingUiStatus.LOCKING
        if (gpsPaused) return TrackingUiStatus.PAUSED_FOR_MOTION
        val noGoodFix = lastAccuracyMeters == null || lastAccuracyMeters > effectiveAccuracyThresholdMeters
        return if (noGoodFix) TrackingUiStatus.LOCKING else TrackingUiStatus.TRACKING_ACTIVE
    }

    @JvmStatic
    fun resolveForGpsState(
        isRunning: Boolean,
        gpsProviderEnabled: Boolean,
        gpsState: GpsRuntimeState,
        lastAccuracyMeters: Float?,
        effectiveAccuracyThresholdMeters: Float,
        activeAccuracyBlockedEmission: Boolean = false,
    ): TrackingUiStatus {
        if (!isRunning) return TrackingUiStatus.NOT_TRACKING
        if (
            !gpsProviderEnabled ||
            gpsState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
            gpsState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return TrackingUiStatus.WAITING_FOR_GPS
        }
        return when (gpsState) {
            GpsRuntimeState.LOCKING,
            GpsRuntimeState.FALLBACK_PENDING,
            GpsRuntimeState.RUNNING,
            GpsRuntimeState.PAUSED_FOR_MOTION ->
                resolve(
                    isRunning = true,
                    gpsProviderEnabled = true,
                    gpsPaused = gpsState == GpsRuntimeState.PAUSED_FOR_MOTION,
                    lastAccuracyMeters = lastAccuracyMeters,
                    effectiveAccuracyThresholdMeters = effectiveAccuracyThresholdMeters,
                    activeAccuracyBlockedEmission = activeAccuracyBlockedEmission,
                )
            GpsRuntimeState.INACTIVE -> TrackingUiStatus.LOCKING
            GpsRuntimeState.WAITING_FOR_PROVIDER -> TrackingUiStatus.WAITING_FOR_GPS
            GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED -> TrackingUiStatus.WAITING_FOR_GPS
        }
    }
}

data class RecordingRuntime(
    val sessionActive: Boolean = false,
    val startupActive: Boolean = false,
    val gpsCollecting: Boolean = false,
    val pausedForMotion: Boolean = false,
    val waitingForProviderWhilePaused: Boolean = false,
    val selectedTrackerId: String = "",
) {
    val localRecordingActive: Boolean
        get() = (sessionActive || startupActive) && selectedTrackerId.trim().isNotEmpty()
}

sealed class RecordingRuntimeEvent {
    data class SessionStateChanged(
        val sessionActive: Boolean,
        val startupActive: Boolean,
    ) : RecordingRuntimeEvent()

    data class GpsStateChanged(
        val gpsState: GpsRuntimeState,
        val gpsProviderEnabled: Boolean,
    ) : RecordingRuntimeEvent()

    data class SelectedTrackerChanged(val trackerId: String) : RecordingRuntimeEvent()
    data object Stopped : RecordingRuntimeEvent()
}

object RecordingRuntimeReducer {
    @JvmStatic
    fun reduce(current: RecordingRuntime, event: RecordingRuntimeEvent): RecordingRuntime {
        return when (event) {
            is RecordingRuntimeEvent.SessionStateChanged -> current.copy(
                sessionActive = event.sessionActive,
                startupActive = event.startupActive,
            ).withGpsCollectingResolved()

            is RecordingRuntimeEvent.GpsStateChanged -> current.copy(
                gpsCollecting = current.sessionActive && event.gpsProviderEnabled && event.gpsState.collectsLocationUpdates,
                pausedForMotion = event.gpsState == GpsRuntimeState.PAUSED_FOR_MOTION,
                waitingForProviderWhilePaused = event.gpsState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED,
            )

            is RecordingRuntimeEvent.SelectedTrackerChanged -> current.copy(
                selectedTrackerId = event.trackerId.trim(),
            )

            RecordingRuntimeEvent.Stopped -> RecordingRuntime(
                selectedTrackerId = current.selectedTrackerId.trim(),
            )
        }
    }

    @JvmStatic
    fun fromInputs(
        previous: RecordingRuntime,
        sessionActive: Boolean,
        startupActive: Boolean,
        gpsState: GpsRuntimeState,
        gpsProviderEnabled: Boolean,
        selectedTrackerId: String,
    ): RecordingRuntime {
        return listOf(
            RecordingRuntimeEvent.SessionStateChanged(
                sessionActive = sessionActive,
                startupActive = startupActive,
            ),
            RecordingRuntimeEvent.GpsStateChanged(
                gpsState = gpsState,
                gpsProviderEnabled = gpsProviderEnabled,
            ),
            RecordingRuntimeEvent.SelectedTrackerChanged(selectedTrackerId),
        ).fold(previous, ::reduce)
    }

    private val GpsRuntimeState.collectsLocationUpdates: Boolean
        get() = when (this) {
            GpsRuntimeState.RUNNING,
            GpsRuntimeState.LOCKING,
            GpsRuntimeState.FALLBACK_PENDING -> true
            GpsRuntimeState.INACTIVE,
            GpsRuntimeState.PAUSED_FOR_MOTION,
            GpsRuntimeState.WAITING_FOR_PROVIDER,
            GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED -> false
        }

    private fun RecordingRuntime.withGpsCollectingResolved(): RecordingRuntime {
        return if (!sessionActive) {
            copy(gpsCollecting = false, pausedForMotion = false, waitingForProviderWhilePaused = false)
        } else {
            this
        }
    }
}

data class TrackingRuntimeSnapshot(
    val isRunning: Boolean = false,
    val recordingRuntime: RecordingRuntime = RecordingRuntime(),
    val lifecycleState: TrackingLifecycleState = TrackingLifecycleState.STOPPED,
    val failureReason: String? = null,
    val selectedTrackerId: String = "",
    val selectedTrackerName: String = "",
    val gpsProviderEnabled: Boolean = true,
    val autoTrackingEnabled: Boolean = false,
    val activeMotionMode: TrackingMotionMode = TrackingMotionMode.WALKING,
    val uiStatus: TrackingUiStatus = TrackingUiStatus.NOT_TRACKING,
    val gpsPaused: Boolean = false,
    val effectiveAccuracyThresholdMeters: Float = 0f,
    val sessionVisibleBoundaryId: Long = 0L,
    val sessionStartTimeMs: Long = 0L,
    val pointsSentThisSession: Int = 0,
    val lastPointSentAtMs: Long = 0L,
    val queuedPointsVisible: Int = 0,
    val sessionTotalDistanceMeters: Float = 0f,
    val lastAccuracyMeters: Float? = null,
    val lastGoodAccuracyMeters: Float? = null,
    val lastGoodAccuracyAtElapsedMs: Long = 0L,
    val currentFixAccuracyMeters: Float? = null,
    val activePointEmissionTrouble: Boolean = false,
    val activePointEmissionAccuracyTrouble: Boolean = false,
    val pointEmissionTroubleReason: String? = null,
    val providerHealthReason: String = "unknown",
    val lastLocalPointPersistedAtMs: Long = 0L,
    val lastUploadSucceededAtMs: Long = 0L,
    val uploadLastFailureClass: SyncFailureClass = SyncFailureClass.NONE,
    val uploadConsecutiveFailures: Int = 0,
    val currentSessionQueuedCount: Int = 0,
    val backlogQueuedCount: Int = 0,
    val lastTrackedLatitude: Double? = null,
    val lastTrackedLongitude: Double? = null,
    val lastTrackedTimestampMs: Long = 0L,
    val lastTrackedPropsJson: String? = null
) {
    val sessionActive: Boolean
        get() = recordingRuntime.sessionActive

    val startupActive: Boolean
        get() = recordingRuntime.startupActive

    val gpsCollecting: Boolean
        get() = recordingRuntime.gpsCollecting

    val pausedForMotion: Boolean
        get() = recordingRuntime.pausedForMotion

    val localRecordingActive: Boolean
        get() = recordingRuntime.localRecordingActive

    val locallyRecordedTrackerId: String
        get() = recordingRuntime.selectedTrackerId.trim().takeIf { localRecordingActive }.orEmpty()
}

object TrackingRuntimeStateStore {
    private val _state = MutableStateFlow(TrackingRuntimeSnapshot())
    val state: StateFlow<TrackingRuntimeSnapshot> = _state.asStateFlow()
    private val lock = Any()

    fun update(transform: (TrackingRuntimeSnapshot) -> TrackingRuntimeSnapshot) {
        synchronized(lock) {
            _state.value = transform(_state.value)
        }
    }
}
