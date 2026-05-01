package com.geovault.common.maps.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.location.modes.CameraMode

class GeoVaultMapCameraFollowMachineTest {

    @Test
    fun toCameraMode_positionAndHeadingUsesNoneSoManualUpdatesDontFightLocationComponent() {
        assertEquals(
            CameraMode.NONE,
            GeoVaultMapCameraFollowState(
                positionFollowDesired = true,
                headingFollowDesired = true,
            ).toCameraMode(),
        )
    }

    @Test
    fun toCameraMode_headingAloneDoesNotUseMapLibreCompassMode() {
        assertEquals(
            CameraMode.NONE,
            GeoVaultMapCameraFollowState(
                positionFollowDesired = false,
                headingFollowDesired = true,
            ).toCameraMode(),
        )
    }

    @Test
    fun toCameraMode_positionOnly() {
        assertEquals(
            CameraMode.NONE,
            GeoVaultMapCameraFollowState(
                positionFollowDesired = true,
                headingFollowDesired = false,
            ).toCameraMode(),
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
    fun afterUserGesture_clearsPositionOnly() {
        val prev = GeoVaultMapCameraFollowState(
            positionFollowDesired = true,
            headingFollowDesired = true,
        )
        val next = GeoVaultMapCameraFollowMachine.afterUserGesture(prev)
        assertEquals(GeoVaultMapCameraFollowState(false, true), next)
    }

    @Test
    fun afterUserGesture_whenPositionAlreadyOff_noOp() {
        val prev = GeoVaultMapCameraFollowState(
            positionFollowDesired = false,
            headingFollowDesired = true,
        )
        val next = GeoVaultMapCameraFollowMachine.afterUserGesture(prev)
        assertEquals(prev, next)
    }

    @Test
    fun afterProgrammaticCamera_clearsAll() {
        val prev = GeoVaultMapCameraFollowState(
            positionFollowDesired = true,
            headingFollowDesired = true,
        )
        assertEquals(GeoVaultMapCameraFollowState.NONE, GeoVaultMapCameraFollowMachine.afterProgrammaticCamera(prev))
    }

    @Test
    fun afterGpsRecenter_whenPositionAndHeadingOn_preservesBoth() {
        val prev = GeoVaultMapCameraFollowState(
            positionFollowDesired = true,
            headingFollowDesired = true,
        )
        assertEquals(prev, GeoVaultMapCameraFollowMachine.afterGpsRecenter(prev))
    }

    @Test
    fun afterGpsRecenter_whenHeadingOnly_reengagesPosition() {
        val prev = GeoVaultMapCameraFollowState(
            positionFollowDesired = false,
            headingFollowDesired = true,
        )
        assertEquals(
            GeoVaultMapCameraFollowState(
                positionFollowDesired = true,
                headingFollowDesired = true,
            ),
            GeoVaultMapCameraFollowMachine.afterGpsRecenter(prev),
        )
    }

    @Test
    fun afterGpsRecenter_whenPositionOnly_noOp() {
        val prev = GeoVaultMapCameraFollowState(
            positionFollowDesired = true,
            headingFollowDesired = false,
        )
        assertEquals(prev, GeoVaultMapCameraFollowMachine.afterGpsRecenter(prev))
    }

    @Test
    fun afterGpsRecenter_whenNoFollow_noOp() {
        assertEquals(
            GeoVaultMapCameraFollowState.NONE,
            GeoVaultMapCameraFollowMachine.afterGpsRecenter(GeoVaultMapCameraFollowState.NONE),
        )
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
    fun toggleHeadingOnTap_offToOn_engagesPositionFollowToo() {
        assertEquals(
            GeoVaultMapCameraFollowState(
                positionFollowDesired = true,
                headingFollowDesired = true,
            ),
            GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(GeoVaultMapCameraFollowState.NONE),
        )
    }

    @Test
    fun toggleHeadingOnTap_offToOn_preservesPositionTrueWhenAlreadyOn() {
        assertEquals(
            GeoVaultMapCameraFollowState(
                positionFollowDesired = true,
                headingFollowDesired = true,
            ),
            GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(
                GeoVaultMapCameraFollowState(
                    positionFollowDesired = true,
                    headingFollowDesired = false,
                ),
            ),
        )
    }

    @Test
    fun toggleHeadingOnTap_onToOff_dropsHeadingButKeepsPosition() {
        assertEquals(
            GeoVaultMapCameraFollowState(
                positionFollowDesired = true,
                headingFollowDesired = false,
            ),
            GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(
                GeoVaultMapCameraFollowState(
                    positionFollowDesired = true,
                    headingFollowDesired = true,
                ),
            ),
        )
    }

    @Test
    fun toggleHeadingOnTap_onToOff_whenPositionWasOffStaysOff() {
        assertEquals(
            GeoVaultMapCameraFollowState(
                positionFollowDesired = false,
                headingFollowDesired = false,
            ),
            GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(
                GeoVaultMapCameraFollowState(
                    positionFollowDesired = false,
                    headingFollowDesired = true,
                ),
            ),
        )
    }

    @Test
    fun togglePositionFollowOnTap_flipsPositionOnly() {
        assertEquals(
            GeoVaultMapCameraFollowState(true, false),
            GeoVaultMapCameraFollowMachine.togglePositionFollowOnTap(GeoVaultMapCameraFollowState.NONE),
        )
        assertEquals(
            GeoVaultMapCameraFollowState.NONE,
            GeoVaultMapCameraFollowMachine.togglePositionFollowOnTap(
                GeoVaultMapCameraFollowState(true, false),
            ),
        )
    }

    @Test
    fun togglePositionFollowOnTap_preservesHeading() {
        assertEquals(
            GeoVaultMapCameraFollowState(true, true),
            GeoVaultMapCameraFollowMachine.togglePositionFollowOnTap(
                GeoVaultMapCameraFollowState(false, true),
            ),
        )
        assertEquals(
            GeoVaultMapCameraFollowState(false, true),
            GeoVaultMapCameraFollowMachine.togglePositionFollowOnTap(
                GeoVaultMapCameraFollowState(true, true),
            ),
        )
    }

    @Test
    fun enablePositionFollowOnGpsTap_turnsPositionOnWithoutClearingHeading() {
        assertEquals(
            GeoVaultMapCameraFollowState(true, false),
            GeoVaultMapCameraFollowMachine.enablePositionFollowOnGpsTap(GeoVaultMapCameraFollowState.NONE),
        )
        assertEquals(
            GeoVaultMapCameraFollowState(true, true),
            GeoVaultMapCameraFollowMachine.enablePositionFollowOnGpsTap(
                GeoVaultMapCameraFollowState(
                    positionFollowDesired = false,
                    headingFollowDesired = true,
                ),
            ),
        )
        assertEquals(
            GeoVaultMapCameraFollowState(true, true),
            GeoVaultMapCameraFollowMachine.enablePositionFollowOnGpsTap(
                GeoVaultMapCameraFollowState(
                    positionFollowDesired = true,
                    headingFollowDesired = true,
                ),
            ),
        )
    }
}
