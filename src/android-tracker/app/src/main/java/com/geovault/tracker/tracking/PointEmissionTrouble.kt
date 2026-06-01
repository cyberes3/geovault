package com.geovault.tracker.tracking

data class PointEmissionTrouble(
    val active: Boolean,
    val accuracyBlocked: Boolean,
    val reason: String?,
) {
    companion object {
        val None = PointEmissionTrouble(active = false, accuracyBlocked = false, reason = null)
    }
}
