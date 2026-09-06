package com.geovault.tracker.params

import com.geovault.common.geo.Wgs84Point

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerParamsContentReducerTest {

    @Test
    fun nonEmptyParams_isShowingGrid() {
        assertEquals(
            TrackerParamsBodyKind.ShowingGrid,
            TrackerParamsContentReducer.resolve(
                latestPointParams = mapOf("batt" to 90),
                lastTimestampMs = null,
                lastPosition = null,
            ),
        )
    }

    @Test
    fun emptyParams_butHasTimestamp_isNoExtended() {
        assertEquals(
            TrackerParamsBodyKind.NoExtendedParams,
            TrackerParamsContentReducer.resolve(
                latestPointParams = emptyMap(),
                lastTimestampMs = 1L,
                lastPosition = null,
            ),
        )
    }

    @Test
    fun emptyParams_butHasPosition_isNoExtended() {
        assertEquals(
            TrackerParamsBodyKind.NoExtendedParams,
            TrackerParamsContentReducer.resolve(
                latestPointParams = emptyMap(),
                lastTimestampMs = null,
                lastPosition = Wgs84Point(1.0, 2.0),
            ),
        )
    }

    @Test
    fun emptyParams_noTimestamp_noPosition_isWaiting() {
        assertEquals(
            TrackerParamsBodyKind.WaitingForData,
            TrackerParamsContentReducer.resolve(
                latestPointParams = emptyMap(),
                lastTimestampMs = null,
                lastPosition = null,
            ),
        )
    }
}
