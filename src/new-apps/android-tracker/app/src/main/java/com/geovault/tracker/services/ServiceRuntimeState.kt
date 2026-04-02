package com.geovault.tracker.services

import com.geovault.tracker.location.TrackingLifecycleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrackingMotionMode(val profileIndex: Int) {
    WALKING(0),
    BIKING(1),
    DRIVING(2);

    companion object {
        @JvmStatic
        fun fromProfileIndex(profileIndex: Int): TrackingMotionMode {
            return when (profileIndex) {
                WALKING.profileIndex -> WALKING
                BIKING.profileIndex -> BIKING
                DRIVING.profileIndex -> DRIVING
                else -> WALKING
            }
        }
    }
}

enum class TrackingUiStatus {
    NOT_TRACKING,
    WAITING_FOR_GPS,
    LOCKING,
    TRACKING_ACTIVE
}

object TrackingUiStatusResolver {
    @JvmStatic
    fun resolve(
        isRunning: Boolean,
        gpsProviderEnabled: Boolean,
        gpsPaused: Boolean,
        lastAccuracyMeters: Float?,
        effectiveAccuracyThresholdMeters: Float
    ): TrackingUiStatus {
        if (!isRunning) return TrackingUiStatus.NOT_TRACKING
        if (!gpsProviderEnabled) return TrackingUiStatus.WAITING_FOR_GPS
        if (gpsPaused) return TrackingUiStatus.TRACKING_ACTIVE
        val noGoodFix = lastAccuracyMeters == null || lastAccuracyMeters > effectiveAccuracyThresholdMeters
        return if (noGoodFix) TrackingUiStatus.LOCKING else TrackingUiStatus.TRACKING_ACTIVE
    }
}

data class TrackingRuntimeSnapshot(
    val isRunning: Boolean = false,
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
    val sessionStartTimeMs: Long = 0L,
    val pointsSentThisSession: Int = 0,
    val lastPointSentAtMs: Long = 0L,
    val queuedPointsVisible: Int = 0,
    val sessionTotalDistanceMeters: Float = 0f,
    val lastAccuracyMeters: Float? = null,
    val lastTrackedLatitude: Double? = null,
    val lastTrackedLongitude: Double? = null,
    val lastTrackedTimestampMs: Long = 0L,
    val lastTrackedPropsJson: String? = null
)

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
