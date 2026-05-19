package com.geovault.tracker.policy.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end pipeline replay backed by an anonymized on-foot
 * rubber-banding capture shape. Followed by a synthetic 8-fix driving
 * burst at ~18 m/s to verify the same profile-independent filter accepts
 * legitimate fast motion without modification.
 *
 * Replay coordinates are shifted from their source geometry by a fixed
 * offset so relative distances are preserved without retaining the real
 * path. Timestamps and accuracy are reconstructed at the rates a chipset
 * reports during a slow urban walk (1 Hz, 50-65 m envelope). Reported
 * speed is held at near-zero -- the device knows we are stationary even
 * though the lat/lon is dancing.
 */
class LocationFilterPipelineRubberBandReplayTest {

    @Test
    fun walkingProfile_holdsRepeatedForestAnchorInsteadOfCommittingStickySnapPoint() {
        val filter = LocationFilter(walkingConfig())
        val anchor = LocationInput(
            latitude = 41.20204381,
            longitude = -103.56599184,
            timestampMs = 1_000L,
            accuracyMeters = 12f,
            speedMps = 0.4f,
            bearingDegrees = 15f,
        )
        assertEquals(LocationFilterResult.Decision.Commit, filter.evaluate(anchor).decision)

        val replay = listOf(
            Triple(21_000L, 41.2010899225 to -103.5640088375, 47.3f),
            Triple(42_000L, 41.20036973313242 to -103.56561784411370, 8.2f),
            Triple(205_000L, 41.201077525 to -103.56401074499999, 47.5f),
            Triple(292_000L, 41.20107561666666 to -103.56401201666667, 48.9f),
            Triple(500_000L, 41.20106799 to -103.56400692999999, 47.0f),
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
                latitude = 41.20408572417497,
                longitude = -103.56196492689251,
                timestampMs = 1_000L,
                accuracyMeters = 10.7f,
                speedMps = 0.8f,
                bearingDegrees = 180f,
            )
        )

        val jump = filter.evaluate(
            LocationInput(
                latitude = 41.20344232928753,
                longitude = -103.56101785565316,
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
            25.94418901587643 to -78.75547621486452,
            25.94412333679199 to -78.75571835937500,
            25.94425875854492 to -78.75576413574218,
            25.94412333679199 to -78.75569547119140,
            25.94436938476563 to -78.75563443603515,
            25.94408137512207 to -78.75573361816406,
            25.94422633361816 to -78.75600827636718,
            25.94429690551758 to -78.75605405273437,
            25.94434077453613 to -78.75601590576171,
            25.94465167236328 to -78.75576413574218,
            25.94487292480469 to -78.75580991210937,
            25.94512278747559 to -78.75574124755859,
            25.94531542968750 to -78.75566495361328,
            25.94556147766113 to -78.75564206542968,
            25.94488055419922 to -78.75577939453125,
            25.94564349365234 to -78.75550473632812,
            25.94528300476074 to -78.75564206542968,
            25.94562442016602 to -78.75545133056640,
            25.94557864379883 to -78.75574124755859,
            25.94577891540527 to -78.75551236572265,
            25.94440371704102 to -78.75578702392578,
            25.94545848083496 to -78.75571072998046,
            25.94507128906250 to -78.75570310058593,
            25.94459254455566 to -78.75565732421875,
            25.94427783203125 to -78.75590146484375,
            25.94415003967285 to -78.75568784179687,
            25.94419161169024 to -78.75546823309632,
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
