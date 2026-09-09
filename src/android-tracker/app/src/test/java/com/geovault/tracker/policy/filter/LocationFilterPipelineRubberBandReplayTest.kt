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
 * Replay coordinates are anonymized. Timestamps and accuracy are
 * reconstructed at the rates a chipset reports during a slow urban walk
 * (1 Hz, 50-65 m envelope). Reported speed is held at near-zero -- the
 * device knows we are stationary even though the lat/lon is dancing.
 */
class LocationFilterPipelineRubberBandReplayTest {

    @Test
    fun walkingProfile_holdsRepeatedForestAnchorInsteadOfCommittingStickySnapPoint() {
        val filter = LocationFilter(walkingConfig())
        val anchor = LocationInput(
            latitude = 40.0,
            longitude = -100.0,
            timestampMs = 1_000L,
            accuracyMeters = 12f,
            speedMps = 0.4f,
            bearingDegrees = 15f,
        )
        assertEquals(LocationFilterResult.Decision.Commit, filter.evaluate(anchor).decision)

        val replay = listOf(
            Triple(21_000L, 39.99904611250000 to -99.99801699750000, 47.3f),
            Triple(42_000L, 39.99832592313242 to -99.99962600411371, 8.2f),
            Triple(205_000L, 39.99903371500000 to -99.99801890500000, 47.5f),
            Triple(292_000L, 39.99903180666666 to -99.99802017666667, 48.9f),
            Triple(500_000L, 39.99902418000000 to -99.99801509000000, 47.0f),
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
                latitude = 40.00204191417497,
                longitude = -99.99597308689252,
                timestampMs = 1_000L,
                accuracyMeters = 10.7f,
                speedMps = 0.8f,
                bearingDegrees = 180f,
            )
        )

        val jump = filter.evaluate(
            LocationInput(
                latitude = 40.00139851928753,
                longitude = -99.99502601565317,
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
        var lat = 10.0
        var rejections = 0
        repeat(20) {
            lat += 0.000005
            ts += 1_000L
            val r = filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = -20.0,
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
            10.00000000000000 to -20.00000000000000,
            9.99993432091556 to -20.00024214451048,
            10.00006974266849 to -20.00028792087767,
            9.99993432091556 to -20.00021925632689,
            10.00018036888920 to -20.00015822117064,
            9.99989235924564 to -20.00025740329954,
            10.00003731774173 to -20.00053206150267,
            10.00010788964115 to -20.00057783786986,
            10.00015175865970 to -20.00053969089720,
            10.00046265648685 to -20.00028792087767,
            10.00068390892826 to -20.00033369724486,
            10.00093377159916 to -20.00026503269407,
            10.00112641381107 to -20.00018873874876,
            10.00137246178470 to -20.00016585056517,
            10.00069153832279 to -20.00030317966673,
            10.00145447777591 to -20.00002852146361,
            10.00109398888431 to -20.00016585056517,
            10.00143540428959 to -19.99997511570189,
            10.00138962792240 to -20.00026503269407,
            10.00158989952884 to -20.00003615085814,
            10.00021470116459 to -20.00031080906126,
            10.00126946495853 to -20.00023451511595,
            10.00088227318607 to -20.00022688572142,
            10.00040352867923 to -20.00018110935423,
            10.00008881615482 to -20.00042524997923,
            9.99996102379642 to -20.00021162693236,
            10.00000259581381 to -19.99999201823181,
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
