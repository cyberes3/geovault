package com.geovault.tracker.location

import com.geovault.tracker.services.TrackingMotionMode
import kotlin.math.exp
import kotlin.math.max

data class AutoTrackingMotionState(
    val mode: TrackingMotionMode = TrackingMotionMode.WALKING,
    val smoothedSpeedMps: Float = 0f,
    val lastEvidenceAtMs: Long = 0L,
    val isGpsPaused: Boolean = false
)

data class AutoTrackingEngineOutput(
    val state: AutoTrackingMotionState,
    val modeChanged: Boolean
)

class AutoTrackingMotionEngine(
    private val speedSmoothingAlpha: Float = 0.30f,
    private val decayHalfLifeMs: Long = 45_000L,
    private val decayGraceMs: Long = 15_000L
) {
    companion object {
        private const val WALKING_TO_BIKING_UPPER_MPS = 2.0f
        private const val BIKING_TO_DRIVING_UPPER_MPS = 8.0f
        private const val DRIVING_TO_BIKING_LOWER_MPS = 6.0f
        private const val BIKING_TO_WALKING_LOWER_MPS = 1.5f
    }

    private var state = AutoTrackingMotionState()

    fun snapshot(): AutoTrackingMotionState = state

    fun reset(nowMs: Long): AutoTrackingEngineOutput {
        val next = AutoTrackingMotionState(
            mode = TrackingMotionMode.WALKING,
            smoothedSpeedMps = 0f,
            lastEvidenceAtMs = nowMs,
            isGpsPaused = false
        )
        val changed = state.mode != next.mode
        state = next
        return AutoTrackingEngineOutput(state = state, modeChanged = changed)
    }

    fun onAcceptedFix(speedMps: Float, eventTimeMs: Long): AutoTrackingEngineOutput {
        val nextSpeed = smoothSpeed(state.smoothedSpeedMps, speedMps)
        return setStateWithTransition(
            state.copy(
                smoothedSpeedMps = nextSpeed,
                lastEvidenceAtMs = eventTimeMs,
                isGpsPaused = false
            )
        )
    }

    fun onRejectedFix(speedMpsHint: Float?, eventTimeMs: Long): AutoTrackingEngineOutput {
        val nextSpeed = if (speedMpsHint == null) {
            state.smoothedSpeedMps
        } else {
            smoothSpeed(state.smoothedSpeedMps, speedMpsHint, alpha = speedSmoothingAlpha * 0.35f)
        }
        return setStateWithTransition(
            state.copy(
                smoothedSpeedMps = nextSpeed,
                lastEvidenceAtMs = eventTimeMs
            )
        )
    }

    fun onGpsPaused(nowMs: Long): AutoTrackingEngineOutput {
        val current = state
        val next = current.copy(
            isGpsPaused = true,
            lastEvidenceAtMs = max(current.lastEvidenceAtMs, nowMs)
        )
        state = next
        return AutoTrackingEngineOutput(state = state, modeChanged = false)
    }

    fun onGpsResumed(nowMs: Long): AutoTrackingEngineOutput {
        val next = state.copy(
            isGpsPaused = false,
            lastEvidenceAtMs = max(state.lastEvidenceAtMs, nowMs)
        )
        state = next
        return AutoTrackingEngineOutput(state = state, modeChanged = false)
    }

    fun onTick(nowMs: Long): AutoTrackingEngineOutput {
        val elapsedMs = nowMs - state.lastEvidenceAtMs
        if (elapsedMs <= decayGraceMs) {
            return AutoTrackingEngineOutput(state = state, modeChanged = false)
        }
        val decayWindowMs = elapsedMs - decayGraceMs
        val decayFactor = exp(-decayWindowMs.toDouble() / decayHalfLifeMs.toDouble()).toFloat()
        val decayedSpeed = (state.smoothedSpeedMps * decayFactor).coerceAtLeast(0f)
        return setStateWithTransition(
            state.copy(smoothedSpeedMps = decayedSpeed)
        )
    }

    private fun setStateWithTransition(nextState: AutoTrackingMotionState): AutoTrackingEngineOutput {
        val nextMode = selectMode(nextState.mode, nextState.smoothedSpeedMps)
        val resolved = nextState.copy(mode = nextMode)
        val changed = state.mode != resolved.mode
        state = resolved
        return AutoTrackingEngineOutput(state = state, modeChanged = changed)
    }

    private fun selectMode(current: TrackingMotionMode, speedMps: Float): TrackingMotionMode {
        return when (current) {
            TrackingMotionMode.WALKING -> {
                if (speedMps > WALKING_TO_BIKING_UPPER_MPS) TrackingMotionMode.BIKING else TrackingMotionMode.WALKING
            }
            TrackingMotionMode.BIKING -> {
                when {
                    speedMps > BIKING_TO_DRIVING_UPPER_MPS -> TrackingMotionMode.DRIVING
                    speedMps < BIKING_TO_WALKING_LOWER_MPS -> TrackingMotionMode.WALKING
                    else -> TrackingMotionMode.BIKING
                }
            }
            TrackingMotionMode.DRIVING -> {
                if (speedMps < DRIVING_TO_BIKING_LOWER_MPS) TrackingMotionMode.BIKING else TrackingMotionMode.DRIVING
            }
        }
    }

    private fun smoothSpeed(previous: Float, observed: Float, alpha: Float = speedSmoothingAlpha): Float {
        val boundedAlpha = alpha.coerceIn(0f, 1f)
        return ((1f - boundedAlpha) * previous) + (boundedAlpha * observed.coerceAtLeast(0f))
    }
}
