package com.geovault.common.maps.core

internal enum class MapNetworkAccessPlan {
    CacheOnly,
    NetworkWithCacheDeadline,
    WaitForNetwork,
}

internal object MapNetworkAccessPolicy {
    const val CACHE_DEADLINE_MS = 1_000L
    const val NO_CACHE_DEADLINE_MS = 10_000L

    fun plan(hasValidatedInternet: Boolean, hasCache: Boolean): MapNetworkAccessPlan {
        if (!hasCache) return MapNetworkAccessPlan.WaitForNetwork
        if (!hasValidatedInternet) return MapNetworkAccessPlan.CacheOnly
        return MapNetworkAccessPlan.NetworkWithCacheDeadline
    }

    fun firstPaintDeadlineMs(plan: MapNetworkAccessPlan): Long = when (plan) {
        MapNetworkAccessPlan.CacheOnly -> 0L
        MapNetworkAccessPlan.NetworkWithCacheDeadline -> CACHE_DEADLINE_MS
        MapNetworkAccessPlan.WaitForNetwork -> NO_CACHE_DEADLINE_MS
    }
}
