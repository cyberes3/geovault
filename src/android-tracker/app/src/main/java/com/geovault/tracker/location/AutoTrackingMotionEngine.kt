package com.geovault.tracker.location

import com.geovault.tracker.services.TrackingMotionMode
import kotlin.math.exp
import kotlin.math.max

/**
 * [consecutiveAboveUpper] counts consecutive samples strictly above the upper threshold
 * for the current mode (promotion evidence).
 *
 * [consecutiveBelowLower] counts consecutive samples strictly below the lower threshold
 * for the current mode (demotion evidence).
 *
 * Both counters are intentionally **preserved** across [onTick] calls so that streaks
 * accumulate even when GPS is quiet (e.g. distance-filter gaps in DRIVING mode or
 * speed-cap-rejected fixes during mode-deadlock in WALKING mode). They reset naturally
 * through [selectMode] when a sample arrives on the opposite side of the hysteresis band,
 * and on any rejected fix via [onRejectedFix].
 */
data class AutoTrackingMotionState(
    val mode: TrackingMotionMode = TrackingMotionMode.WALKING,
    val smoothedSpeedMps: Float = 0f,
    val lastEvidenceAtMs: Long = 0L,
    val isGpsPaused: Boolean = false,
    val consecutiveAboveUpper: Int = 0,
    val consecutiveBelowLower: Int = 0,
    val lastObservedSpeedMps: Float = 0f,
)

data class AutoTrackingEngineOutput(
    val state: AutoTrackingMotionState,
    val modeChanged: Boolean,
    /**
     * Diagnostic tag describing which selectMode branch drove the most
     * recent transition. Useful in field logs to tell a normal
     * WALKING -> BIKING -> DRIVING ladder promotion apart from the
     * fast WALKING -> DRIVING skip. [TransitionPath.NONE] when no mode
     * change occurred on this call.
     */
    val transitionPath: TransitionPath = TransitionPath.NONE,
)

enum class TransitionPath {
    NONE,
    LADDER,
    SKIP_TO_DRIVING,
}

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
 * [PROMOTE_CONSECUTIVE_REQUIRED] consecutive accepted samples above the upper
 * threshold. Demotion (DRIVING -> BIKING -> WALKING) is asymmetric: it requires
 * [DEMOTE_CONSECUTIVE_REQUIRED] consecutive samples (a higher bar than promotion)
 * so that brief decelerations — traffic lights, junctions, stationary GPS probes —
 * do not trigger a spurious mode drop. A single noisy sample is absorbed without
 * flipping the mode in either direction.
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
        private const val DEMOTE_CONSECUTIVE_REQUIRED = 3
        // Skip the WALKING->BIKING->DRIVING ladder when the *observed*
        // speed clearly exceeds the BIKING upper. The threshold is
        // intentionally well above the per-sample upper so a phantom
        // multipath burst cannot trigger a skip; the 2-sample streak
        // requirement still applies, matching the standard promotion
        // guard.
        private const val WALKING_SKIP_TO_DRIVING_MPS =
            1.5f * BIKING_TO_DRIVING_UPPER_MPS
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
            consecutiveBelowLower = 0,
            lastObservedSpeedMps = 0f,
        )
        val changed = state.mode != next.mode
        state = next
        return AutoTrackingEngineOutput(state = state, modeChanged = changed)
    }

    /**
     * Overrides the current mode without going through promotion logic. Only valid immediately
     * after [reset] — for example to restore a known mode when replaying a mid-session window.
     * Callers must not use this to bypass the normal evidence accumulation path.
     */
    internal fun overrideInitialMode(mode: TrackingMotionMode) {
        state = state.copy(mode = mode)
    }

    /**
     * Feed the smoother with the speed of an accepted fix. [speedMps]
     * MUST come from a vetted source (the position filter's effective
     * distance over dt), never directly from `Location.speed`.
     */
    fun onAcceptedFix(speedMps: Float, eventTimeMs: Long): AutoTrackingEngineOutput {
        val observed = speedMps.coerceAtLeast(0f)
        val nextSpeed = smoothSpeed(state.smoothedSpeedMps, observed)
        return setStateWithTransition(
            state.copy(
                smoothedSpeedMps = nextSpeed,
                lastObservedSpeedMps = observed,
                lastEvidenceAtMs = eventTimeMs,
                isGpsPaused = false
            ),
            decisionSpeedMps = max(nextSpeed, observed),
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
            AutoTrackingMotionEvidenceConfidence.High -> {
                val observed = speedMps.coerceAtLeast(0f)
                val nextSpeed = smoothSpeed(state.smoothedSpeedMps, observed)
                setStateWithTransition(
                    state.copy(
                        smoothedSpeedMps = nextSpeed,
                        lastObservedSpeedMps = observed,
                        lastEvidenceAtMs = eventTimeMs,
                        isGpsPaused = false,
                    ),
                    decisionSpeedMps = max(nextSpeed, observed),
                    drivingEvidence = true,
                )
            }
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
            consecutiveBelowLower = 0,
            lastObservedSpeedMps = 0f,
        )
        return AutoTrackingEngineOutput(state = state, modeChanged = false)
    }

    /**
     * GPS transitioning to a stationary pause is zero-speed evidence and
     * should contribute to the demotion streak just like any other slow
     * sample. Routing through [setStateWithTransition] lets three pause
     * events (possibly interleaved with actual slow fixes) accumulate the
     * required [DEMOTE_CONSECUTIVE_REQUIRED] count and trigger a demotion.
     */
    fun onGpsPaused(nowMs: Long): AutoTrackingEngineOutput {
        return setStateWithTransition(
            nextState = state.copy(
                isGpsPaused = true,
                lastEvidenceAtMs = max(state.lastEvidenceAtMs, nowMs),
                lastObservedSpeedMps = 0f,
                consecutiveAboveUpper = 0,
            ),
            decisionSpeedMps = 0f,
        )
    }

    fun onGpsResumed(nowMs: Long): AutoTrackingEngineOutput {
        val next = state.copy(
            isGpsPaused = false,
            lastEvidenceAtMs = max(state.lastEvidenceAtMs, nowMs)
        )
        state = next
        return AutoTrackingEngineOutput(state = state, modeChanged = false)
    }

    /**
     * Periodic speed decay. Decays [smoothedSpeedMps] toward zero when no
     * evidence has arrived for longer than [decayGraceMs].
     *
     * Neither [consecutiveBelowLower] nor [consecutiveAboveUpper] is reset
     * here. Resetting either counter would erase legitimate evidence accumulated
     * across GPS-quiet gaps — for example:
     * - A device parked in DRIVING mode may only receive a fix every 100 m of
     *   distance-filtered movement; demotion streaks must survive the gaps.
     * - A device accelerating from WALKING receives speed-cap-rejected fixes
     *   that feed [onMotionEvidence] / the HANDSHAKE path; promotion streaks
     *   that survive a quiet tick cannot be allowed to reset or the mode will
     *   never leave WALKING during the filter deadlock period.
     *
     * Both counters reset naturally through [selectMode] the next time a sample
     * falls on the opposite side of the hysteresis threshold.
     */
    fun onTick(nowMs: Long): AutoTrackingEngineOutput {
        val elapsedMs = nowMs - state.lastEvidenceAtMs
        if (elapsedMs <= decayGraceMs) {
            return AutoTrackingEngineOutput(state = state, modeChanged = false)
        }
        val decayWindowMs = elapsedMs - decayGraceMs
        val decayFactor = exp(-decayWindowMs.toDouble() / decayHalfLifeMs.toDouble()).toFloat()
        val decayedSpeed = (state.smoothedSpeedMps * decayFactor).coerceAtLeast(0f)
        state = state.copy(
            smoothedSpeedMps = decayedSpeed,
            lastObservedSpeedMps = 0f,
        )
        return AutoTrackingEngineOutput(state = state, modeChanged = false)
    }

    private fun setStateWithTransition(
        nextState: AutoTrackingMotionState,
        decisionSpeedMps: Float,
        drivingEvidence: Boolean = false,
    ): AutoTrackingEngineOutput {
        val decision = selectMode(
            current = nextState.mode,
            speedMps = decisionSpeedMps,
            consecutiveAboveUpper = nextState.consecutiveAboveUpper,
            consecutiveBelowLower = nextState.consecutiveBelowLower,
            drivingEvidence = drivingEvidence,
        )
        val resolved = nextState.copy(
            mode = decision.mode,
            consecutiveAboveUpper = decision.aboveStreak,
            consecutiveBelowLower = decision.belowStreak,
        )
        val changed = state.mode != resolved.mode
        state = resolved
        return AutoTrackingEngineOutput(
            state = state,
            modeChanged = changed,
            transitionPath = decision.path,
        )
    }

    private data class ModeDecision(
        val mode: TrackingMotionMode,
        val aboveStreak: Int,
        val belowStreak: Int,
        val path: TransitionPath,
    )

    /**
     * Returns the next mode and updated streak counters.
     *
     * Promotion requires [PROMOTE_CONSECUTIVE_REQUIRED] consecutive samples strictly
     * above the upper threshold. Demotion requires [DEMOTE_CONSECUTIVE_REQUIRED]
     * consecutive samples strictly below the lower threshold; the higher demotion bar
     * absorbs brief decelerations without flipping the mode.
     *
     * [consecutiveAboveUpper] tracks the upward (promotion) streak.
     * [consecutiveBelowLower] tracks the downward (demotion) streak.
     * Both reset when a sample falls in the neutral band or on a mode change.
     */
    private fun selectMode(
        current: TrackingMotionMode,
        speedMps: Float,
        consecutiveAboveUpper: Int,
        consecutiveBelowLower: Int,
        drivingEvidence: Boolean,
    ): ModeDecision {
        return when (current) {
            TrackingMotionMode.WALKING -> {
                if (speedMps > WALKING_TO_BIKING_UPPER_MPS) {
                    val streak = consecutiveAboveUpper + 1
                    if (streak >= PROMOTE_CONSECUTIVE_REQUIRED) {
                        val skip = if (drivingEvidence) {
                            speedMps > BIKING_TO_DRIVING_UPPER_MPS
                        } else {
                            speedMps > WALKING_SKIP_TO_DRIVING_MPS
                        }
                        val target = if (skip) {
                            TrackingMotionMode.DRIVING
                        } else {
                            TrackingMotionMode.BIKING
                        }
                        val path = if (skip) TransitionPath.SKIP_TO_DRIVING else TransitionPath.LADDER
                        ModeDecision(target, 0, 0, path)
                    } else {
                        ModeDecision(TrackingMotionMode.WALKING, streak, 0, TransitionPath.NONE)
                    }
                } else {
                    ModeDecision(TrackingMotionMode.WALKING, 0, 0, TransitionPath.NONE)
                }
            }
            TrackingMotionMode.BIKING -> {
                when {
                    speedMps > BIKING_TO_DRIVING_UPPER_MPS -> {
                        if (drivingEvidence) {
                            return ModeDecision(TrackingMotionMode.DRIVING, 0, 0, TransitionPath.SKIP_TO_DRIVING)
                        }
                        val streak = consecutiveAboveUpper + 1
                        if (streak >= PROMOTE_CONSECUTIVE_REQUIRED) {
                            ModeDecision(TrackingMotionMode.DRIVING, 0, 0, TransitionPath.LADDER)
                        } else {
                            ModeDecision(TrackingMotionMode.BIKING, streak, 0, TransitionPath.NONE)
                        }
                    }
                    speedMps < BIKING_TO_WALKING_LOWER_MPS -> {
                        val streak = consecutiveBelowLower + 1
                        if (streak >= DEMOTE_CONSECUTIVE_REQUIRED) {
                            ModeDecision(TrackingMotionMode.WALKING, 0, 0, TransitionPath.LADDER)
                        } else {
                            ModeDecision(TrackingMotionMode.BIKING, 0, streak, TransitionPath.NONE)
                        }
                    }
                    else -> ModeDecision(TrackingMotionMode.BIKING, 0, 0, TransitionPath.NONE)
                }
            }
            TrackingMotionMode.DRIVING -> {
                if (speedMps < DRIVING_TO_BIKING_LOWER_MPS) {
                    val streak = consecutiveBelowLower + 1
                    if (streak >= DEMOTE_CONSECUTIVE_REQUIRED) {
                        ModeDecision(TrackingMotionMode.BIKING, 0, 0, TransitionPath.LADDER)
                    } else {
                        ModeDecision(TrackingMotionMode.DRIVING, 0, streak, TransitionPath.NONE)
                    }
                } else {
                    ModeDecision(TrackingMotionMode.DRIVING, 0, 0, TransitionPath.NONE)
                }
            }
        }
    }

    private fun smoothSpeed(previous: Float, observed: Float, alpha: Float = speedSmoothingAlpha): Float {
        val boundedAlpha = alpha.coerceIn(0f, 1f)
        return ((1f - boundedAlpha) * previous) + (boundedAlpha * observed.coerceAtLeast(0f))
    }
}
