package com.geovault.common.maps.ui

import org.maplibre.android.location.modes.CameraMode

/**
 * Desired **position** follow and **heading** follow flags for a single MapLibre location camera.
 *
 * "Position follow" is MapLibre [CameraMode.TRACKING] (or [CameraMode.NONE] when both flags are on;
 * see [toCameraMode]). The host’s **one-shot** “go to my position” is
 * [com.geovault.common.maps.ui.recenter.rememberGeoVaultGpsRecenterFabAction] — this type does not
 * model that tap.
 *
 * MapLibre exposes one [CameraMode] at a time. Map bearing while "heading follow" is on is
 * driven manually (puck [MapLocationRendererPlugin] bearing in
 * [com.geovault.common.maps.ui.camerafollow.rememberGeoVaultMapHeadingFollowFabBundle]) like
 * other GeoVault map hosts, not [CameraMode.TRACKING_COMPASS], which tends to feel choppy.
 */
data class GeoVaultMapCameraFollowState(
    val positionFollowDesired: Boolean,
    val headingFollowDesired: Boolean,
) {
    /**
     * Camera mode the MapLibre [org.maplibre.android.location.LocationComponent] should be in.
     *
     * When **both** position and heading follow are on we drive `target` + `bearing` manually from
     * the smoothed heading sensor at ~60 Hz, so we put the location component in
     * [CameraMode.NONE]; otherwise the component animates to each new GPS fix on its own
     * schedule and fights the 60 Hz manual updates, which feels choppy.
     *
     * When only position follow is on (no heading), [CameraMode.TRACKING] is exactly what we
     * want — let the location component re-center on each fix without bearing changes.
     */
    fun toCameraMode(): Int =
        when {
            positionFollowDesired && headingFollowDesired -> CameraMode.NONE
            positionFollowDesired -> CameraMode.TRACKING
            else -> CameraMode.NONE
        }

    companion object {
        val NONE = GeoVaultMapCameraFollowState(
            positionFollowDesired = false,
            headingFollowDesired = false,
        )
    }
}

/**
 * Pure transitions for [GeoVaultMapCameraFollowState] (unit-testable, no Android / Compose).
 */
object GeoVaultMapCameraFollowMachine {
    /**
     * User tapped the heading / compass FAB.
     *
     * - Off → on: also engages position follow so the map centers on the user **and** rotates
     *   with the device.
     * - On → off: turns off heading follow only and leaves position follow alone, so the user
     *   can drop rotation while staying centered (until a pan clears position follow per
     *   [afterUserGesture]).
     */
    fun toggleHeadingOnTap(current: GeoVaultMapCameraFollowState): GeoVaultMapCameraFollowState =
        if (current.headingFollowDesired) {
            current.copy(headingFollowDesired = false)
        } else {
            current.copy(positionFollowDesired = true, headingFollowDesired = true)
        }

    /**
     * User panned or zoomed the map: drop **position** follow but keep heading follow so the
     * map can stay compass-locked.
     */
    fun afterUserGesture(current: GeoVaultMapCameraFollowState): GeoVaultMapCameraFollowState =
        current.copy(positionFollowDesired = false)

    /** Host-driven camera (fit bounds, focus selection, navigation framing, etc.). */
    fun afterProgrammaticCamera(current: GeoVaultMapCameraFollowState): GeoVaultMapCameraFollowState =
        GeoVaultMapCameraFollowState.NONE

    /** Whether map bearing should snap to north-up after moving to [next]. */
    fun shouldResetNorthToUp(previous: GeoVaultMapCameraFollowState, next: GeoVaultMapCameraFollowState): Boolean =
        previous.headingFollowDesired && !next.headingFollowDesired
}
