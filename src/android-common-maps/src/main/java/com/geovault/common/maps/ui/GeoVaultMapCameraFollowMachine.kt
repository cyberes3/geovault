package com.geovault.common.maps.ui

import org.maplibre.android.location.modes.CameraMode

/**
 * Desired GPS / heading follow flags for a single MapLibre location camera.
 *
 * MapLibre exposes one [CameraMode] at a time. Map bearing while "heading follow" is on is
 * driven manually (smoothed + throttled [HeadingSensor] in
 * [com.geovault.common.maps.ui.camerafollow.rememberGeoVaultMapCameraFollowFabBundle])
 * like other GeoVault map hosts, not [CameraMode.TRACKING_COMPASS], which tends to feel choppy.
 */
data class GeoVaultMapCameraFollowState(
    val gpsFollowDesired: Boolean,
    val headingFollowDesired: Boolean,
) {
    /**
     * Camera mode the MapLibre [org.maplibre.android.location.LocationComponent] should be in.
     *
     * When **both** GPS and heading follow are on we drive `target` + `bearing` manually from
     * the smoothed heading sensor at ~60 Hz, so we put the location component in
     * [CameraMode.NONE]; otherwise the component animates to each new GPS fix on its own
     * schedule and fights the 60 Hz manual updates, which feels choppy.
     *
     * When only GPS follow is on (no heading), [CameraMode.TRACKING] is exactly what we want
     * — let the location component re-center on each fix without bearing changes.
     */
    fun toCameraMode(): Int =
        when {
            gpsFollowDesired && headingFollowDesired -> CameraMode.NONE
            gpsFollowDesired -> CameraMode.TRACKING
            else -> CameraMode.NONE
        }

    companion object {
        val NONE = GeoVaultMapCameraFollowState(gpsFollowDesired = false, headingFollowDesired = false)
    }
}

/**
 * Pure transitions for [GeoVaultMapCameraFollowState] (unit-testable, no Android / Compose).
 */
object GeoVaultMapCameraFollowMachine {
    /**
     * User tapped the GPS follow FAB. Toggles GPS follow only; heading follow is preserved so
     * the user can independently re-engage position tracking after a pan without losing the
     * compass lock.
     */
    fun toggleGpsOnTap(current: GeoVaultMapCameraFollowState): GeoVaultMapCameraFollowState =
        current.copy(gpsFollowDesired = !current.gpsFollowDesired)

    /**
     * User tapped the heading / compass FAB.
     *
     * - Off → on: also engages GPS follow so the map centers on the user **and** rotates with
     *   the device. Matches the user-facing contract that the rotation FAB is the "compass /
     *   navigation" master toggle. Without this, heading-alone would rotate only the puck —
     *   the map underneath would stay still, which feels broken.
     * - On → off: turns off heading follow only and leaves GPS follow alone, so the user can
     *   drop rotation while staying centered on themselves.
     */
    fun toggleHeadingOnTap(current: GeoVaultMapCameraFollowState): GeoVaultMapCameraFollowState =
        if (current.headingFollowDesired) {
            current.copy(headingFollowDesired = false)
        } else {
            current.copy(gpsFollowDesired = true, headingFollowDesired = true)
        }

    /**
     * User panned or zoomed the map: drop **position** follow but keep heading follow so the
     * map can stay compass-locked.
     */
    fun afterUserGesture(current: GeoVaultMapCameraFollowState): GeoVaultMapCameraFollowState =
        current.copy(gpsFollowDesired = false)

    /** Host-driven camera (fit bounds, focus selection, navigation framing, etc.). */
    fun afterProgrammaticCamera(current: GeoVaultMapCameraFollowState): GeoVaultMapCameraFollowState =
        GeoVaultMapCameraFollowState.NONE

    /** Whether map bearing should snap to north-up after moving to [next]. */
    fun shouldResetNorthToUp(previous: GeoVaultMapCameraFollowState, next: GeoVaultMapCameraFollowState): Boolean =
        previous.headingFollowDesired && !next.headingFollowDesired
}
