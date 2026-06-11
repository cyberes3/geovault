package com.geovault.tracker

import android.location.Location
import com.geovault.tracker.policy.filter.StationaryConfidence
import com.geovault.tracker.sensor.ImuClassification

/**
 * Pure logic for stationary detection and location request timing.
 * Extracted so it can be unit tested without Service/Context.
 */
object TrackingLocationPolicy {
    const val MIN_STATIONARY_RADIUS_METERS = 25f
    const val DEFAULT_STATIONARY_RADIUS_METERS = 50f

    /**
     * Maximum fix accuracy (meters) at which a paused-state freshness fix
     * may be committed to the database. Used by [com.geovault.tracker.location.PausedFreshnessPolicy]
     * to skip emit when the chipset is too noisy to draw a confident dot.
     */
    const val STATIONARY_ACCURACY_CEILING_METERS = 35f

    const val AUTO_START_PROFILE_INDEX = 0
    const val WALKING_INTERVAL_SEC = 20L
    const val WALKING_DISTANCE_FILTER_METERS = 0f
    const val BIKING_INTERVAL_SEC = 15L
    const val BIKING_DISTANCE_FILTER_METERS = 0f
    const val DRIVING_INTERVAL_SEC = 10L
    const val DRIVING_DISTANCE_FILTER_METERS = 0f

    /**
     * Outcome of a single [stationaryUpdate] call. [consecutive] is the next
     * counter value, [shouldPause] is true when GPS may be paused, and
     * [reason] is a stable identifier suitable for telemetry.
     *
     * "Hold" reasons leave the counter unchanged (neither resetting nor
     * advancing) - they describe situations where the fix is too noisy to
     * be evidence about stationarity in either direction.
     */
    data class StationaryDecision(
        val consecutive: Int,
        val shouldPause: Boolean,
        val reason: String,
    )

    /**
     * True when multi-modal sensor confidence clears the fast-advance threshold.
     * Encapsulates the threshold so it is defined once across all call sites.
     * Does NOT require a prior GPS anchor; use [confidenceCanFastAdvance] inside
     * [stationaryUpdate] where the anchor invariant must be enforced.
     */
    internal fun isHighConfidence(confidence: StationaryConfidence?): Boolean {
        if (confidence == null) return false
        return confidence.score > FAST_ADVANCE_SCORE ||
            (confidence.isOscillating && confidence.score > OSCILLATING_FAST_ADVANCE_SCORE)
    }

    /**
     * True when sensor-fusion or IMU evidence may accelerate the stationary evidence
     * counter inside [stationaryUpdate]. Requires [currentConsecutive] > 0:
     * confidence strengthens an anchor GPS has already confirmed; it cannot
     * establish the first anchor from scratch.
     *
     * [ImuClassification.STATIONARY] satisfies this gate independently of GPS
     * confidence — the inertial sensor has confirmed no motion without any GPS
     * measurement required.
     */
    private fun confidenceCanFastAdvance(
        currentConsecutive: Int,
        confidence: StationaryConfidence?,
        imuClassification: ImuClassification?,
    ): Boolean = currentConsecutive > 0 &&
        (isHighConfidence(confidence) || imuClassification == ImuClassification.STATIONARY)

    /**
     * Updates stationary state from a single accepted fix.
     *
     * The upstream [com.geovault.tracker.policy.filter.LocationFilter]
     * already enforces a single accuracy threshold
     * (`trackingAccuracyThresholdMeters`); a fix that survived that is
     * eligible for stationary detection regardless of its absolute
     * accuracy number. A 40 m indoor fix with `raw=0` for 21 seconds is
     * unambiguous evidence the device hasn't moved.
     *
     * Pause evidence:
     *
     *  1. The user has opted into GPS-pause behavior ([significantMotionOnly]).
     *  2. The fix was either accepted unchanged, or adjusted in a way that
     *     positively confirms stillness ([filterConfirmedStillness] -- e.g.
     *     `uncertainty-suppressed` snap to anchor). Generic filter
     *     intervention ([filterIntervened] without confirmed stillness)
     *     holds the counter rather than advancing it, unless (a) the counter
     *     has already reached the pause threshold — in which case the pause
     *     decision is still honoured — or (b) the IMU/barometer confidence
     *     signal independently indicates stillness (see rule 3).
     *  3. Multi-signal [confidence] can fast-advance the counter past the
     *     usual 3-tick floor: `score > 0.6` (confident stillness) or
     *     `isOscillating && score > 0.5` (confident rubber-banding) jump
     *     straight to the pause threshold so we don't waste a minute of
     *     polling proving what we already know. This also fires when
     *     [filterIntervened] is true: sensor-fusion evidence is
     *     GPS-independent and does not require a fresh committed trail.
     *     Requires [currentConsecutive] > 0: confidence accelerates
     *     existing GPS evidence; it cannot create the first anchor.
     *
     * IMU veto: [ImuClassification.PEDESTRIAN] and [ImuClassification.VEHICULAR]
     * both act as active-speed hints that reset the counter to zero (no pause)
     * whenever [filterConfirmedStillness] is false. The inertial sensor's
     * confirmed-motion evidence overrides GPS chipset stillness at red lights or
     * brief stops. [filterConfirmedStillness] retains final authority — GPS
     * geometry proving the device hasn't moved supersedes IMU hints.
     */
    fun stationaryUpdate(
        lastLocation: Location?,
        location: Location,
        stationaryRadiusMeters: Float,
        currentConsecutive: Int,
        significantMotionOnly: Boolean,
        activeSpeedHint: Boolean = false,
        filterIntervened: Boolean = false,
        filterConfirmedStillness: Boolean = false,
        confidence: StationaryConfidence? = null,
        imuClassification: ImuClassification? = null,
        inMotionCooldown: Boolean = false,
    ): StationaryDecision {
        // After a confirmed stationary-region exit, suppress all stillness evidence
        // for STATIONARY_REGION_EXIT_COOLDOWN_MS. This prevents UNCERTAINTY_SUPPRESSED
        // fixes near the previous parked location from re-establishing a stationary
        // region while the device is still departing.
        if (inMotionCooldown) {
            return StationaryDecision(consecutive = 0, shouldPause = false, reason = "motion_exit_cooldown")
        }
        if (!significantMotionOnly) {
            return StationaryDecision(consecutive = 0, shouldPause = false, reason = "disabled")
        }
        // `filterConfirmedStillness` is positive evidence the device hasn't
        // moved (filter snapped to anchor because the displacement was
        // inside the joint accuracy envelope). It supersedes both
        // `activeSpeedHint` (current observed chipset speed above the speed
        // floor) and `filterIntervened` (the same event viewed pessimistically).
        //
        // IMU PEDESTRIAN and VEHICULAR both act as guaranteed active-speed signals —
        // the inertial sensor has confirmed the device is in motion, so GPS pausing
        // must be suppressed. `filterConfirmedStillness` still takes precedence
        // (the filter proved lat/lon unchanged, which is stronger evidence than
        // the IMU alone — e.g. a genuine long stop after vehicular motion).
        val effectiveActiveSpeedHint = activeSpeedHint ||
            imuClassification == ImuClassification.PEDESTRIAN ||
            imuClassification == ImuClassification.VEHICULAR
        if (!filterConfirmedStillness) {
            if (effectiveActiveSpeedHint) {
                return StationaryDecision(consecutive = 0, shouldPause = false, reason = "active_speed_hint")
            }
            if (filterIntervened) {
                // Rule 2a: the counter already reached the threshold before this
                // filter-intervened fix arrived (e.g. a brief motion-resume while
                // the device is otherwise stationary).
                if (currentConsecutive >= PAUSE_THRESHOLD) {
                    return StationaryDecision(
                        consecutive = currentConsecutive,
                        shouldPause = true,
                        reason = "filter_intervened",
                    )
                }
                // Rule 3 / Rule 2b: sensor-fusion confidence is GPS-independent;
                // it can fast-advance the counter even when the GPS trail is stale.
                // IMU STATIONARY also satisfies this gate independently.
                if (confidenceCanFastAdvance(currentConsecutive, confidence, imuClassification)) {
                    val newConsecutive = maxOf(currentConsecutive + 1, PAUSE_THRESHOLD)
                    return StationaryDecision(
                        consecutive = newConsecutive,
                        shouldPause = true,
                        reason = "confidence_fast_advance",
                    )
                }
                return StationaryDecision(
                    consecutive = currentConsecutive,
                    shouldPause = false,
                    reason = "filter_intervened",
                )
            }
        }

        val anchor = lastLocation
            ?: return StationaryDecision(consecutive = 1, shouldPause = false, reason = "first_anchor")

        // Accuracy-defensive radius: subtract the joint accuracy envelope
        // from the raw anchor-to-fix distance before comparing against the
        // radius. Honours the [MIN_STATIONARY_RADIUS_METERS] floor on the
        // radius itself so a tiny configured radius cannot punch through.
        val radius = stationaryRadiusMeters.coerceAtLeast(MIN_STATIONARY_RADIUS_METERS)
        val rawDist = anchor.distanceTo(location)
        val anchorAccuracy = if (anchor.hasAccuracy()) anchor.accuracy else 0f
        val locationAccuracy = if (location.hasAccuracy()) location.accuracy else 0f
        val effectiveDist = (rawDist - anchorAccuracy - locationAccuracy).coerceAtLeast(0f)
        val withinRadius = effectiveDist <= radius

        val gpsSpeedMps = if (location.hasSpeed()) location.speed else 0f
        val movedByGpsSpeed = gpsSpeedMps > GPS_MOTION_FLOOR_MPS
        val movedByGeometry = !withinRadius
        // `filterConfirmedStillness` overrides phantom speed bursts: the
        // filter already proved the lat/lon didn't change.
        if (!filterConfirmedStillness && movedByGpsSpeed && movedByGeometry) {
            return StationaryDecision(consecutive = 0, shouldPause = false, reason = "gps_motion_corroborated")
        }

        val baseAdvance = if (withinRadius || filterConfirmedStillness) currentConsecutive + 1 else 1
        val confidenceFastAdvance = confidenceCanFastAdvance(currentConsecutive, confidence, imuClassification)
        val newConsecutive = if (confidenceFastAdvance) {
            maxOf(baseAdvance, PAUSE_THRESHOLD)
        } else {
            baseAdvance
        }
        val shouldPause = newConsecutive >= PAUSE_THRESHOLD
        val reason = when {
            shouldPause && confidenceFastAdvance -> "confidence_fast_advance"
            shouldPause -> "pause_threshold_reached"
            filterConfirmedStillness -> "advance_confirmed_stillness"
            withinRadius -> "advance_within_radius"
            else -> "reset_outside_radius"
        }
        return StationaryDecision(consecutive = newConsecutive, shouldPause = shouldPause, reason = reason)
    }

    private const val GPS_MOTION_FLOOR_MPS = 1.0f
    private const val PAUSE_THRESHOLD = 3
    private const val FAST_ADVANCE_SCORE = 0.6
    private const val OSCILLATING_FAST_ADVANCE_SCORE = 0.5

    /**
     * Duration after a confirmed stationary-region exit during which the
     * stationary machine does not accept any stillness evidence. Prevents
     * UNCERTAINTY_SUPPRESSED fixes near the previous parked location from
     * immediately re-establishing a new stationary region while the device
     * is still departing.
     */
    const val STATIONARY_REGION_EXIT_COOLDOWN_MS = 30_000L

    /**
     * Minimum elapsed time between successive IMU transition-triggered attention boosts.
     * Guards against persistent IMU classification oscillation (e.g.
     * PEDESTRIAN→UNKNOWN→PEDESTRIAN…) re-arming the GPS boost on every classifier cycle.
     */
    const val IMU_TRANSITION_BOOST_DEBOUNCE_MS = 30_000L

    /**
     * Minimum IMU confidence required for a VEHICULAR transition to trigger a GPS
     * wake-from-pause. Filters out low-confidence transient readings.
     */
    const val IMU_VEHICULAR_WAKE_MIN_CONFIDENCE = 0.5f

    /**
     * Minimum elapsed time between successive IMU-triggered GPS wake-from-pause calls.
     * Prevents rapid re-triggering if the IMU oscillates around the VEHICULAR threshold
     * while the device is in transit.
     */
    const val IMU_VEHICULAR_WAKE_DEBOUNCE_MS = 60_000L

    /**
     * Returns (intervalMillis, minUpdateIntervalMillis) for LocationRequest
     * from interval in seconds.
     */
    fun locationRequestIntervalFromSec(intervalSec: Long): Pair<Long, Long> {
        val intervalMs = intervalSec * 1000L
        val minUpdateMs = intervalMs / 2
        return intervalMs to minUpdateMs
    }

}
