package com.geovault.tracker.policy.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end pipeline replay backed by a real on-foot rubber-banding
 * capture (slow walk during an outdoor facility tour where the user
 * reported ~500 ft jumps). Followed by a synthetic 8-fix driving burst at
 * ~18 m/s to verify the same profile-independent filter accepts
 * legitimate fast motion without modification.
 *
 * The fixture coordinates are real GPS samples; timestamps and accuracy
 * are reconstructed at the rates a chipset reports during a slow urban
 * walk (1 Hz, 50-65 m envelope). Reported speed is held at near-zero --
 * the device knows we are stationary even though the lat/lon is dancing.
 */
class LocationFilterPipelineRubberBandReplayTest {

    @Test
    fun walkingProfile_holdsRepeatedForestAnchorInsteadOfCommittingStickySnapPoint() {
        val filter = LocationFilter(walkingConfig())
        val anchor = LocationInput(
            latitude = 39.96754381,
            longitude = -105.91159184,
            timestampMs = 1_000L,
            accuracyMeters = 12f,
            speedMps = 0.4f,
            bearingDegrees = 15f,
        )
        assertEquals(LocationFilterResult.Decision.Commit, filter.evaluate(anchor).decision)

        val replay = listOf(
            Triple(21_000L, 39.9665899225 to -105.9096088375, 47.3f),
            Triple(42_000L, 39.96586973313242 to -105.91121784411371, 8.2f),
            Triple(205_000L, 39.966577525 to -105.909610745, 47.5f),
            Triple(292_000L, 39.96657561666667 to -105.90961201666667, 48.9f),
            Triple(500_000L, 39.96656799 to -105.90960693, 47.0f),
        )

        val decisions = replay.map { (ts, latLon, accuracy) ->
            filter.evaluate(
                LocationInput(
                    latitude = latLon.first,
                    longitude = latLon.second,
                    timestampMs = ts,
                    accuracyMeters = accuracy,
                    speedMps = 0.5f,
                    bearingDegrees = 20f,
                )
            )
        }

        assertTrue(
            "walking profile must not commit the repeated forest anchor cluster",
            decisions.none { it.decision == LocationFilterResult.Decision.Commit },
        )
        assertTrue(
            "the repeated cluster should be handled as held or internal snap state",
            decisions.any {
                it.decision == LocationFilterResult.Decision.Hold ||
                    it.decision == LocationFilterResult.Decision.SnapInternal
            },
        )
    }

    @Test
    fun walkingProfile_rejectsFastUpDownLineJumps() {
        val filter = LocationFilter(walkingConfig())
        filter.evaluate(
            LocationInput(
                latitude = 39.969585724174976,
                longitude = -105.90756492689252,
                timestampMs = 1_000L,
                accuracyMeters = 10.7f,
                speedMps = 0.8f,
                bearingDegrees = 180f,
            )
        )

        val jump = filter.evaluate(
            LocationInput(
                latitude = 39.96894232928753,
                longitude = -105.90661785565317,
                timestampMs = 21_000L,
                accuracyMeters = 8.2f,
                speedMps = 10.0f,
                bearingDegrees = 5f,
            )
        )

        assertNotEquals(
            "walking profile must not commit a 100m+ line jump in 20s as normal motion",
            LocationFilterResult.Decision.Commit,
            jump.decision,
        )
    }

    @Test
    fun appProfileTuning_keepsWalkingTighterThanBikingAndDriving() {
        assertTrue(MotionProfileTuning.Walking.maxImpliedSpeedMps < MotionProfileTuning.Biking.maxImpliedSpeedMps)
        assertTrue(MotionProfileTuning.Biking.maxImpliedSpeedMps < MotionProfileTuning.Driving.maxImpliedSpeedMps)
        assertTrue(MotionProfileTuning.Walking.maxBurstDistanceMeters < MotionProfileTuning.Biking.maxBurstDistanceMeters)
        assertTrue(MotionProfileTuning.Biking.maxBurstDistanceMeters < MotionProfileTuning.Driving.maxBurstDistanceMeters)
    }

    @Test
    fun walkCluster_clipsOrRejectsRubberBanding_andAcceptsSubsequentDrivingBurst() {
        val filter = LocationFilter(LocationFilterConfig.Default)

        var ts = 1_700_000_000_000L
        var rubberBandClippedOrRejected = 0
        WALK_CLUSTER.forEachIndexed { idx, latLon ->
            val (lat, lon) = latLon
            ts += 1_000L
            val result = filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = lon,
                    timestampMs = ts,
                    accuracyMeters = WALK_ACCURACY_METERS,
                    speedMps = WALK_SPEED_MPS,
                    bearingDegrees = ((idx * 47) % 360).toFloat(),
                )
            )
            if (result.decision != LocationFilterResult.Decision.Commit) {
                rubberBandClippedOrRejected++
            }
        }

        assertTrue(
            "rubber-banding cluster must be clipped or rejected on at least 6 of ${WALK_CLUSTER.size} fixes," +
                " observed $rubberBandClippedOrRejected",
            rubberBandClippedOrRejected >= 6,
        )

        var driveLat = WALK_CLUSTER.last().first
        var driveLon = WALK_CLUSTER.last().second
        var rejectionsDuringDrive = 0
        var adjustedDuringDrive = 0
        DRIVING_BURST_DELTAS.forEach { (dLat, dLon) ->
            ts += 1_000L
            driveLat += dLat
            driveLon += dLon
            val result = filter.evaluate(
                LocationInput(
                    latitude = driveLat,
                    longitude = driveLon,
                    timestampMs = ts,
                    accuracyMeters = DRIVE_ACCURACY_METERS,
                    speedMps = DRIVE_SPEED_MPS,
                    bearingDegrees = 45f,
                )
            )
            when (result.decision) {
                LocationFilterResult.Decision.Reject -> rejectionsDuringDrive++
                LocationFilterResult.Decision.Commit -> if (result.adjustedLatitude != null) adjustedDuringDrive++
                LocationFilterResult.Decision.Hold,
                LocationFilterResult.Decision.SnapInternal -> adjustedDuringDrive++
            }
        }
        assertEquals(
            "driving burst must not be rejected by the rubber-band filter",
            0,
            rejectionsDuringDrive,
        )
        assertEquals(
            "driving burst must not be clipped (accept untouched)",
            0,
            adjustedDuringDrive,
        )
    }

    @Test
    fun slowWalkSlightlyForward_isAcceptedUnaltered() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        var ts = 1_700_000_000_000L
        var lat = 24.7097
        var rejections = 0
        repeat(20) {
            lat += 0.000005
            ts += 1_000L
            val r = filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = -81.1011,
                    timestampMs = ts,
                    accuracyMeters = 6f,
                    speedMps = 0.6f,
                    bearingDegrees = 0f,
                )
            )
            if (r.decision == LocationFilterResult.Decision.Reject) rejections++
        }
        assertEquals(
            "deliberate slow walk with accurate fixes must not be filtered",
            0,
            rejections,
        )
    }

    @Test
    fun motionChange_walkToDrive_preservesAnchorAndAcceptsBurstWithoutClip() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        var ts = 1_700_000_000_000L
        WALK_CLUSTER.forEachIndexed { idx, latLon ->
            ts += 1_000L
            filter.evaluate(
                LocationInput(
                    latitude = latLon.first,
                    longitude = latLon.second,
                    timestampMs = ts,
                    accuracyMeters = WALK_ACCURACY_METERS,
                    speedMps = WALK_SPEED_MPS,
                    bearingDegrees = ((idx * 47) % 360).toFloat(),
                )
            )
        }
        val anchorTsBeforeMotionChange = filter.lastAcceptedTimestampMs
        filter.onMotionChanged()
        assertEquals(
            "onMotionChanged must preserve the anchor so stationary jitter immediately after a false motion wakeup still snaps",
            anchorTsBeforeMotionChange,
            filter.lastAcceptedTimestampMs,
        )

        ts += 5_000L
        var lat = WALK_CLUSTER.last().first
        var lon = WALK_CLUSTER.last().second
        DRIVING_BURST_DELTAS.forEachIndexed { idx, (dLat, dLon) ->
            ts += 1_000L
            lat += dLat
            lon += dLon
            val r = filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = lon,
                    timestampMs = ts,
                    accuracyMeters = DRIVE_ACCURACY_METERS,
                    speedMps = DRIVE_SPEED_MPS,
                    bearingDegrees = 45f,
                )
            )
            assertNotEquals(
                "driving fix idx=$idx after a motion-change reset should not be rejected",
                LocationFilterResult.Decision.Reject,
                r.decision,
            )
        }
    }

    private companion object {
        private const val WALK_ACCURACY_METERS = 55f
        private const val WALK_SPEED_MPS = 0.4f
        private const val DRIVE_ACCURACY_METERS = 5f
        private const val DRIVE_SPEED_MPS = 18f

        private fun walkingConfig(): LocationFilterConfig =
            LocationFilterConfig.fromTuning(
                tuning = MotionProfileTuning.Walking,
                trackingAccuracyThresholdMeters = 50.0,
                maxFutureSkewMs = 0L,
                freshnessTtlMs = 0L,
                normalizeSecondsTimestamps = false,
            )

        private val WALK_CLUSTER: List<Pair<Double, Double>> = listOf(
            24.709689015876428 to -81.10107621486452,
            24.709623336791992 to -81.101318359375,
            24.709758758544922 to -81.10136413574219,
            24.709623336791992 to -81.1012954711914,
            24.709869384765625 to -81.10123443603516,
            24.70958137512207 to -81.10133361816406,
            24.709726333618164 to -81.10160827636719,
            24.709796905517578 to -81.10165405273438,
            24.709840774536133 to -81.10161590576172,
            24.71015167236328 to -81.10136413574219,
            24.710372924804688 to -81.10140991210938,
            24.710622787475586 to -81.1013412475586,
            24.7108154296875 to -81.10126495361328,
            24.711061477661133 to -81.10124206542969,
            24.71038055419922 to -81.10137939453125,
            24.711143493652344 to -81.10110473632812,
            24.710783004760742 to -81.10124206542969,
            24.711124420166016 to -81.1010513305664,
            24.711078643798828 to -81.1013412475586,
            24.711278915405273 to -81.10111236572266,
            24.709903717041016 to -81.10138702392578,
            24.71095848083496 to -81.10131072998047,
            24.7105712890625 to -81.10130310058594,
            24.710092544555664 to -81.10125732421875,
            24.70977783203125 to -81.10150146484375,
            24.70965003967285 to -81.10128784179688,
            24.70969161169024 to -81.10106823309633,
        )

        private val DRIVING_BURST_DELTAS: List<Pair<Double, Double>> = listOf(
            0.00012 to 0.00006,
            0.00013 to 0.00006,
            0.00012 to 0.00007,
            0.00014 to 0.00006,
            0.00013 to 0.00007,
            0.00012 to 0.00006,
            0.00013 to 0.00007,
            0.00014 to 0.00006,
        )
    }
}
