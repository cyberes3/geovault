package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MapNetworkAccessPolicyTest {

    @Test
    fun offlinePlusCache_isCacheOnly() {
        assertEquals(
            MapNetworkAccessPlan.CacheOnly,
            MapNetworkAccessPolicy.plan(hasValidatedInternet = false, hasCache = true),
        )
    }

    @Test
    fun onlinePlusCache_isDeadlineRace() {
        assertEquals(
            MapNetworkAccessPlan.NetworkWithCacheDeadline,
            MapNetworkAccessPolicy.plan(hasValidatedInternet = true, hasCache = true),
        )
    }

    @Test
    fun noCache_isWaitForNetwork_whenOnline() {
        assertEquals(
            MapNetworkAccessPlan.WaitForNetwork,
            MapNetworkAccessPolicy.plan(hasValidatedInternet = true, hasCache = false),
        )
    }

    @Test
    fun noCache_isWaitForNetwork_whenOffline() {
        assertEquals(
            MapNetworkAccessPlan.WaitForNetwork,
            MapNetworkAccessPolicy.plan(hasValidatedInternet = false, hasCache = false),
        )
    }

    @Test
    fun firstPaintDeadlines_matchPlan() {
        assertEquals(0L, MapNetworkAccessPolicy.firstPaintDeadlineMs(MapNetworkAccessPlan.CacheOnly))
        assertEquals(
            MapNetworkAccessPolicy.CACHE_DEADLINE_MS,
            MapNetworkAccessPolicy.firstPaintDeadlineMs(MapNetworkAccessPlan.NetworkWithCacheDeadline),
        )
        assertEquals(
            MapNetworkAccessPolicy.NO_CACHE_DEADLINE_MS,
            MapNetworkAccessPolicy.firstPaintDeadlineMs(MapNetworkAccessPlan.WaitForNetwork),
        )
    }
}
