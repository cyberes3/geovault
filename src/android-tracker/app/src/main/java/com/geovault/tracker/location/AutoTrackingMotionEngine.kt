package com.geovault.tracker.location

import com.geovault.tracker.sensor.ImuClassification
import com.geovault.tracker.sensor.ImuMotionContext
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
    /**
     * Non-null when [onImuClassification] changed the IMU constraint bounds
     * ([imuModeCeiling] or [imuModeFloor]). Carries the new constraint state
     * and the streak counters at the moment of the change so callers can log
     * the event without querying engine internals.
     */
    val imuConstraintSnapshot: ImuConstraintSnapshot? = null,
)

/**
 * Snapshot of IMU constraint state at the moment the constraints changed.
 * Emitted as part of [AutoTrackingEngineOutput.imuConstraintSnapshot] exclusively
 * when [AutoTrackingMotionEngine.onImuClassification] modifies the bounds.
 */
data class ImuConstraintSnapshot(
    val ceiling: TrackingMotionMode?,
    val floor: TrackingMotionMode?,
    val pedestrianStreak: Int,
    val vehicularStreak: Int,
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
        // IMU constraints require sustained evidence before taking effect.
        // Each IMU emission is stable for 15 s (classifier guarantee), so:
        //   PEDESTRIAN_REQUIRED = 3 → 45 s of confirmed foot motion before ceiling applied
        //   VEHICULAR_REQUIRED  = 2 → 30 s of confirmed vehicle motion before floor applied
        internal const val IMU_PEDESTRIAN_REQUIRED = 3
        internal const val IMU_VEHICULAR_REQUIRED  = 2
    }

    private var state = AutoTrackingMotionState()

    // IMU constraint state — intentionally outside AutoTrackingMotionState so that
    // onRejectedFix() never touches these fields. Constraints persist through GPS-quiet
    // and GPS-rejecting periods, which is exactly when the deadlocks occur.
    private var imuModeCeiling: TrackingMotionMode? = null
    private var imuModeFloor: TrackingMotionMode? = null
    private var imuPedestrianStreak: Int = 0
    private var imuVehicularStreak: Int = 0

    fun snapshot(): AutoTrackingMotionState = state

    fun reset(nowMs: Long): AutoTrackingEngineOutput {
        imuModeCeiling = null
        imuModeFloor = null
        imuPedestrianStreak = 0
        imuVehicularStreak = 0
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

    /**
     * Integrates a stable IMU classification into the constraint bounds.
     *
     * The IMU does not inject synthetic speed evidence. It asserts which range of modes is
     * physically consistent with the observed sensor data, and the engine enforces those
     * bounds on every subsequent [setStateWithTransition] call via [clampModeToImuBounds].
     *
     * Streak counters require [IMU_PEDESTRIAN_REQUIRED] / [IMU_VEHICULAR_REQUIRED] consecutive
     * stable emissions before a constraint is applied, providing a second layer of debounce on
     * top of the 15 s stability gate in the classifier itself:
     * - PEDESTRIAN × 3 → 45 s → ceiling = WALKING (BIKING/DRIVING physically impossible)
     * - VEHICULAR  × 2 → 30 s → floor  = BIKING  (WALKING profile physically wrong)
     *
     * STATIONARY and UNKNOWN clear all constraints and reset all streaks (fresh evidence
     * required to reestablish bounds after the user's context changes).
     *
     * The returned [AutoTrackingEngineOutput.imuConstraintSnapshot] is non-null only when
     * the constraint bounds actually changed, enabling callers to log the event once.
     *
     * IMU fields are intentionally NOT part of [AutoTrackingMotionState] and are NOT touched
     * by [onRejectedFix], so constraints persist through GPS-quiet or GPS-rejecting periods.
     */
    fun onImuClassification(ctx: ImuMotionContext): AutoTrackingEngineOutput {
        val prevCeiling = imuModeCeiling
        val prevFloor = imuModeFloor

        when (ctx.classification) {
            ImuClassification.PEDESTRIAN -> {
                imuVehicularStreak = 0
                imuPedestrianStreak++
                if (imuPedestrianStreak >= IMU_PEDESTRIAN_REQUIRED) {
                    imuModeCeiling = TrackingMotionMode.WALKING
                    imuModeFloor = null
                }
            }
            ImuClassification.VEHICULAR -> {
                imuPedestrianStreak = 0
                imuVehicularStreak++
                if (imuVehicularStreak >= IMU_VEHICULAR_REQUIRED) {
                    imuModeFloor = TrackingMotionMode.BIKING
                    imuModeCeiling = null
                }
            }
            ImuClassification.STATIONARY, ImuClassification.UNKNOWN -> {
                imuPedestrianStreak = 0
                imuVehicularStreak = 0
                imuModeCeiling = null
                imuModeFloor = null
            }
        }

        val constraintChanged = prevCeiling != imuModeCeiling || prevFloor != imuModeFloor
        val output = applyImuConstraints()
        val snapshot = if (constraintChanged) {
            ImuConstraintSnapshot(
                ceiling = imuModeCeiling,
                floor = imuModeFloor,
                pedestrianStreak = imuPedestrianStreak,
                vehicularStreak = imuVehicularStreak,
            )
        } else {
            null
        }
        return output.copy(imuConstraintSnapshot = snapshot)
    }

    /**
     * Clamps the current [state.mode] to the active IMU bounds immediately — used when
     * [onImuClassification] changes the bounds and the current mode already violates them.
     * GPS streak counters are reset on forced mode changes so the next GPS samples
     * re-accumulate evidence from the constrained starting point.
     */
    private fun applyImuConstraints(): AutoTrackingEngineOutput {
        val clampedMode = clampModeToImuBounds(state.mode)
        if (clampedMode == state.mode) {
            return AutoTrackingEngineOutput(state = state, modeChanged = false)
        }
        state = state.copy(
            mode = clampedMode,
            consecutiveAboveUpper = 0,
            consecutiveBelowLower = 0,
        )
        return AutoTrackingEngineOutput(state = state, modeChanged = true, transitionPath = TransitionPath.LADDER)
    }

    /**
     * Clamps [mode] to the range [imuModeFloor, imuModeCeiling]. When no IMU constraint is
     * active the mode is returned unchanged. Ordinal comparison relies on the
     * [TrackingMotionMode] declaration order: WALKING(0) < BIKING(1) < DRIVING(2).
     */
    private fun clampModeToImuBounds(mode: TrackingMotionMode): TrackingMotionMode {
        var result = mode
        imuModeFloor?.let { floor -> if (result.ordinal < floor.ordinal) result = floor }
        imuModeCeiling?.let { ceiling -> if (result.ordinal > ceiling.ordinal) result = ceiling }
        return result
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
        // Apply IMU bounds after GPS-derived selection so GPS evidence can never push the
        // mode outside the physically validated range. When the clamp overrides the GPS
        // decision, GPS streak counters are reset (they accumulated toward the wrong mode).
        val clampedMode = clampModeToImuBounds(decision.mode)
        val imuOverride = clampedMode != decision.mode
        val resolved = nextState.copy(
            mode = clampedMode,
            consecutiveAboveUpper = if (imuOverride) 0 else decision.aboveStreak,
            consecutiveBelowLower = if (imuOverride) 0 else decision.belowStreak,
        )
        val changed = state.mode != resolved.mode
        state = resolved
        val path = when {
            !changed -> TransitionPath.NONE
            imuOverride -> TransitionPath.LADDER
            else -> decision.path
        }
        return AutoTrackingEngineOutput(
            state = state,
            modeChanged = changed,
            transitionPath = path,
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
