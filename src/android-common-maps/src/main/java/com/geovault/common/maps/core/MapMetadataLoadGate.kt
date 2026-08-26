package com.geovault.common.maps.core

internal data class MapMetadataDelivery<T>(
    val value: T,
    val applyToMap: Boolean,
    val isLate: Boolean,
)

/**
 * First-paint vs late-network gate for one metadata load.
 *
 * Unusable network results never win first paint — the deadline (or a later
 * usable result) does. A usable result after first paint is applied to the map
 * only when [applyLate] is true ([MapNetworkAccessPlan.WaitForNetwork] success).
 */
internal class MapMetadataLoadGate<T>(
    private val plan: MapNetworkAccessPlan,
    private val cached: T?,
    private val timeoutPlaceholder: T,
) {
    private val lock = Any()
    private var deliveredFirst = false

    fun immediateDelivery(): MapMetadataDelivery<T>? = synchronized(lock) {
        if (plan != MapNetworkAccessPlan.CacheOnly) return null
        val value = cached ?: return null
        if (deliveredFirst) return null
        deliveredFirst = true
        MapMetadataDelivery(value = value, applyToMap = true, isLate = false)
    }

    fun onNetworkResult(value: T, isUsable: Boolean, applyLate: Boolean): MapMetadataDelivery<T>? =
        synchronized(lock) {
            if (!isUsable) return null
            if (!deliveredFirst) {
                deliveredFirst = true
                return MapMetadataDelivery(value = value, applyToMap = true, isLate = false)
            }
            MapMetadataDelivery(value = value, applyToMap = applyLate, isLate = true)
        }

    fun onDeadline(): MapMetadataDelivery<T>? = synchronized(lock) {
        if (deliveredFirst) return null
        deliveredFirst = true
        when (plan) {
            MapNetworkAccessPlan.NetworkWithCacheDeadline -> {
                val value = cached ?: timeoutPlaceholder
                MapMetadataDelivery(value = value, applyToMap = true, isLate = false)
            }
            MapNetworkAccessPlan.WaitForNetwork ->
                MapMetadataDelivery(value = timeoutPlaceholder, applyToMap = true, isLate = false)
            MapNetworkAccessPlan.CacheOnly -> null
        }
    }
}
