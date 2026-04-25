package com.geovault.common.maps.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.location.modes.CameraMode

class GeoVaultMapCameraFollowMachineTest {

    @Test
    fun toCameraMode_gpsAndHeadingUsesNoneSoManualUpdatesDontFightLocationComponent() {
        // Both flags on → we drive target + bearing manually at ~60 Hz from the smoothed
        // heading sensor. Putting MapLibre in TRACKING here makes the LocationComponent run
        // its own animated re-center on every GPS fix, which competes with our manual frame
        // updates and produces visible jank. NONE lets us own the camera fully.
        assertEquals(
            CameraMode.NONE,
            GeoVaultMapCameraFollowState(gpsFollowDesired = true, headingFollowDesired = true).toCameraMode(),
        )
    }

    @Test
    fun toCameraMode_headingAloneDoesNotUseMapLibreCompassMode() {
        assertEquals(
            CameraMode.NONE,
            GeoVaultMapCameraFollowState(gpsFollowDesired = false, headingFollowDesired = true).toCameraMode(),
        )
    }

    @Test
    fun toCameraMode_gpsOnly() {
        assertEquals(
            CameraMode.TRACKING,
            GeoVaultMapCameraFollowState(gpsFollowDesired = true, headingFollowDesired = false).toCameraMode(),
        )
    }

    @Test
    fun toCameraMode_none() {
        assertEquals(
            CameraMode.NONE,
            GeoVaultMapCameraFollowState.NONE.toCameraMode(),
        )
    }

    @Test
    fun afterUserGesture_clearsGpsOnly() {
        val prev = GeoVaultMapCameraFollowState(gpsFollowDesired = true, headingFollowDesired = true)
        val next = GeoVaultMapCameraFollowMachine.afterUserGesture(prev)
        assertEquals(GeoVaultMapCameraFollowState(false, true), next)
    }

    @Test
    fun afterUserGesture_whenGpsAlreadyOff_noOp() {
        val prev = GeoVaultMapCameraFollowState(gpsFollowDesired = false, headingFollowDesired = true)
        val next = GeoVaultMapCameraFollowMachine.afterUserGesture(prev)
        assertEquals(prev, next)
    }

    @Test
    fun afterProgrammaticCamera_clearsAll() {
        val prev = GeoVaultMapCameraFollowState(gpsFollowDesired = true, headingFollowDesired = true)
        assertEquals(GeoVaultMapCameraFollowState.NONE, GeoVaultMapCameraFollowMachine.afterProgrammaticCamera(prev))
    }

    @Test
    fun shouldResetNorthToUp_onlyWhenHeadingTurnsOff() {
        assertTrue(
            GeoVaultMapCameraFollowMachine.shouldResetNorthToUp(
                GeoVaultMapCameraFollowState(true, true),
                GeoVaultMapCameraFollowState(true, false),
            ),
        )
        assertFalse(
            GeoVaultMapCameraFollowMachine.shouldResetNorthToUp(
                GeoVaultMapCameraFollowState(true, true),
                GeoVaultMapCameraFollowState(false, true),
            ),
        )
    }

    @Test
    fun toggleGpsOnTap() {
        assertEquals(
            GeoVaultMapCameraFollowState(true, false),
            GeoVaultMapCameraFollowMachine.toggleGpsOnTap(GeoVaultMapCameraFollowState.NONE),
        )
        assertEquals(
            GeoVaultMapCameraFollowState.NONE,
            GeoVaultMapCameraFollowMachine.toggleGpsOnTap(GeoVaultMapCameraFollowState(true, false)),
        )
    }

    @Test
    fun toggleHeadingOnTap_offToOn_engagesGpsFollowToo() {
        // Tapping the rotation FAB should enable GPS location and lock the map to the user — the
        // map should both center and rotate, not just rotate the puck while the camera sits still.
        assertEquals(
            GeoVaultMapCameraFollowState(gpsFollowDesired = true, headingFollowDesired = true),
            GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(GeoVaultMapCameraFollowState.NONE),
        )
    }

    @Test
    fun toggleHeadingOnTap_offToOn_preservesGpsTrueWhenAlreadyOn() {
        assertEquals(
            GeoVaultMapCameraFollowState(gpsFollowDesired = true, headingFollowDesired = true),
            GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(
                GeoVaultMapCameraFollowState(gpsFollowDesired = true, headingFollowDesired = false),
            ),
        )
    }

    @Test
    fun toggleHeadingOnTap_onToOff_dropsHeadingButKeepsGps() {
        // Symmetric with the GPS FAB: the user can drop rotation while staying centered on
        // themselves without having to re-tap the GPS FAB afterwards.
        assertEquals(
            GeoVaultMapCameraFollowState(gpsFollowDesired = true, headingFollowDesired = false),
            GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(
                GeoVaultMapCameraFollowState(gpsFollowDesired = true, headingFollowDesired = true),
            ),
        )
    }

    @Test
    fun toggleHeadingOnTap_onToOff_whenGpsWasOffStaysOff() {
        // Heading-only state can arise after the gesture listener clears GPS while leaving
        // heading on. Tapping the FAB to disengage rotation should not magically re-enable GPS.
        assertEquals(
            GeoVaultMapCameraFollowState(gpsFollowDesired = false, headingFollowDesired = false),
            GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(
                GeoVaultMapCameraFollowState(gpsFollowDesired = false, headingFollowDesired = true),
            ),
        )
    }
}
