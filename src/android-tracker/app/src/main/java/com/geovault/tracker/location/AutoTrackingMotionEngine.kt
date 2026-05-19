package com.geovault.tracker.location

import com.geovault.tracker.services.TrackingMotionMode
import kotlin.math.exp
import kotlin.math.max

data class AutoTrackingMotionState(
    val mode: TrackingMotionMode = TrackingMotionMode.WALKING,
    val smoothedSpeedMps: Float = 0f,
    val lastEvidenceAtMs: Long = 0L,
    val isGpsPaused: Boolean = false,
    val consecutiveAboveUpper: Int = 0,
)

data class AutoTrackingEngineOutput(
    val state: AutoTrackingMotionState,
    val modeChanged: Boolean
)

enum class AutoTrackingMotionEvidenceConfidence {
    High,
}

/**
 * Classifies the user's motion mode (WALKING / BIKING / DRIVING) from a
 * stream of *vetted* speed samples and drives the [LocationRequest]
 * cadence.
 *
 * The engine never sees raw chipset speed. Callers are expected to feed
 * [onAcceptedFix] with the position filter's effective-distance / dt
 * speed, so a phantom multipath burst that the filter has already
 * rejected cannot push the smoother across a hysteresis threshold.
 *
 * Promotion (WALKING -> BIKING -> DRIVING) requires
 * [PROMOTE_CONSECUTIVE_REQUIRED] consecutive accepted samples above the
 * upper threshold. A single high sample is held in a counter and decays
 * naturally if the next sample is below the upper threshold. Demotion
 * is single-sample so we drop back to lower-power tracking quickly when
 * motion stops.
 */
class AutoTrackingMotionEngine(
    private val speedSmoothingAlpha: Float = 0.30f,
    private val decayHalfLifeMs: Long = 45_000L,
    private val decayGraceMs: Long = 15_000L
) {
    companion object {
        private const val WALKING_TO_BIKING_UPPER_MPS = 2.5f
        private const val BIKING_TO_WALKING_LOWER_MPS = 1.2f
        private const val BIKING_TO_DRIVING_UPPER_MPS = 9.0f
        private const val DRIVING_TO_BIKING_LOWER_MPS = 5.5f
        private const val PROMOTE_CONSECUTIVE_REQUIRED = 2
    }

    private var state = AutoTrackingMotionState()

    fun snapshot(): AutoTrackingMotionState = state

    fun reset(nowMs: Long): AutoTrackingEngineOutput {
        val next = AutoTrackingMotionState(
            mode = TrackingMotionMode.WALKING,
            smoothedSpeedMps = 0f,
            lastEvidenceAtMs = nowMs,
            isGpsPaused = false,
            consecutiveAboveUpper = 0,
        )
        val changed = state.mode != next.mode
        state = next
        return AutoTrackingEngineOutput(state = state, modeChanged = changed)
    }

    /**
     * Feed the smoother with the speed of an accepted fix. [speedMps]
     * MUST come from a vetted source (the position filter's effective
     * distance over dt), never directly from `Location.speed`.
     */
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

    /**
     * Feed high-confidence movement evidence that was not persisted as a
     * track point. This is intentionally separate from [onRejectedFix]: only
     * callers that have already applied strict continuity/accuracy gates may
     * use it to break a filter/profile deadlock.
     */
    fun onMotionEvidence(
        speedMps: Float,
        eventTimeMs: Long,
        confidence: AutoTrackingMotionEvidenceConfidence,
    ): AutoTrackingEngineOutput {
        return when (confidence) {
            AutoTrackingMotionEvidenceConfidence.High -> onAcceptedFix(
                speedMps = speedMps,
                eventTimeMs = eventTimeMs,
            )
        }
    }

    /**
     * A rejected fix is by definition not motion evidence: the position
     * filter has already classified it as a teleport, low-accuracy, or
     * stale. We update [AutoTrackingMotionState.lastEvidenceAtMs] so the
     * decay grace period restarts (we did receive *something* from the
     * chipset), but the smoothed speed itself is not mutated.
     */
    fun onRejectedFix(eventTimeMs: Long): AutoTrackingEngineOutput {
        state = state.copy(
            lastEvidenceAtMs = eventTimeMs,
            consecutiveAboveUpper = 0,
        )
        return AutoTrackingEngineOutput(state = state, modeChanged = false)
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
        val (nextMode, nextStreak) = selectMode(
            current = nextState.mode,
            speedMps = nextState.smoothedSpeedMps,
            consecutiveAboveUpper = nextState.consecutiveAboveUpper,
        )
        val resolved = nextState.copy(mode = nextMode, consecutiveAboveUpper = nextStreak)
        val changed = state.mode != resolved.mode
        state = resolved
        return AutoTrackingEngineOutput(state = state, modeChanged = changed)
    }

    /**
     * Returns the next mode and updated streak counter.
     *
     * Promotion requires [PROMOTE_CONSECUTIVE_REQUIRED] consecutive
     * samples strictly above the upper threshold. A single noisy sample
     * is absorbed without flipping the mode. Demotion is single-sample.
     */
    private fun selectMode(
        current: TrackingMotionMode,
        speedMps: Float,
        consecutiveAboveUpper: Int,
    ): Pair<TrackingMotionMode, Int> {
        return when (current) {
            TrackingMotionMode.WALKING -> {
                if (speedMps > WALKING_TO_BIKING_UPPER_MPS) {
                    val streak = consecutiveAboveUpper + 1
                    if (streak >= PROMOTE_CONSECUTIVE_REQUIRED) {
                        TrackingMotionMode.BIKING to 0
                    } else {
                        TrackingMotionMode.WALKING to streak
                    }
                } else {
                    TrackingMotionMode.WALKING to 0
                }
            }
            TrackingMotionMode.BIKING -> {
                when {
                    speedMps > BIKING_TO_DRIVING_UPPER_MPS -> {
                        val streak = consecutiveAboveUpper + 1
                        if (streak >= PROMOTE_CONSECUTIVE_REQUIRED) {
                            TrackingMotionMode.DRIVING to 0
                        } else {
                            TrackingMotionMode.BIKING to streak
                        }
                    }
                    speedMps < BIKING_TO_WALKING_LOWER_MPS -> TrackingMotionMode.WALKING to 0
                    else -> TrackingMotionMode.BIKING to 0
                }
            }
            TrackingMotionMode.DRIVING -> {
                if (speedMps < DRIVING_TO_BIKING_LOWER_MPS) {
                    TrackingMotionMode.BIKING to 0
                } else {
                    TrackingMotionMode.DRIVING to 0
                }
            }
        }
    }

    private fun smoothSpeed(previous: Float, observed: Float, alpha: Float = speedSmoothingAlpha): Float {
        val boundedAlpha = alpha.coerceIn(0f, 1f)
        return ((1f - boundedAlpha) * previous) + (boundedAlpha * observed.coerceAtLeast(0f))
    }
}
