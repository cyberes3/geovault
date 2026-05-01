package com.geovault.common.maps.ui.location

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultMapLocationSessionPolicyTest {
    private val policy = GeoVaultMapLocationSessionPolicy()

    @Test
    fun decide_whenGpsWasRequestedAfterCameraFollowCleared_keepsLocationEnabled() {
        assertEquals(
            GeoVaultMapLocationSessionDecision(
                shouldStreamGps = true,
                shouldEnablePuck = true,
            ),
            policy.decide(
                baseInput(
                    userLocationRequested = true,
                    positionFollowDesired = false,
                    headingFollowDesired = false,
                    navigationActive = false,
                ),
            ),
        )
    }

    @Test
    fun decide_whenNavigationActive_enablesLocationEvenWithoutCameraFollow() {
        assertEquals(
            GeoVaultMapLocationSessionDecision(
                shouldStreamGps = true,
                shouldEnablePuck = true,
            ),
            policy.decide(
                baseInput(
                    userLocationRequested = false,
                    positionFollowDesired = false,
                    headingFollowDesired = false,
                    navigationActive = true,
                ),
            ),
        )
    }

    @Test
    fun decide_whenNoLocationIntent_disablesLocation() {
        assertEquals(
            GeoVaultMapLocationSessionDecision.Disabled,
            policy.decide(baseInput()),
        )
    }

    @Test
    fun decide_requiresActivePermissionAndReadyMap() {
        assertEquals(
            GeoVaultMapLocationSessionDecision.Disabled,
            policy.decide(baseInput(isActive = false, userLocationRequested = true)),
        )
        assertEquals(
            GeoVaultMapLocationSessionDecision.Disabled,
            policy.decide(baseInput(hasLocationPermission = false, userLocationRequested = true)),
        )
        assertEquals(
            GeoVaultMapLocationSessionDecision.Disabled,
            policy.decide(baseInput(isMapReady = false, userLocationRequested = true)),
        )
    }

    private fun baseInput(
        isActive: Boolean = true,
        hasLocationPermission: Boolean = true,
        isMapReady: Boolean = true,
        userLocationRequested: Boolean = false,
        positionFollowDesired: Boolean = false,
        headingFollowDesired: Boolean = false,
        navigationActive: Boolean = false,
    ): GeoVaultMapLocationSessionInput {
        return GeoVaultMapLocationSessionInput(
            isActive = isActive,
            hasLocationPermission = hasLocationPermission,
            isMapReady = isMapReady,
            userLocationRequested = userLocationRequested,
            positionFollowDesired = positionFollowDesired,
            headingFollowDesired = headingFollowDesired,
            navigationActive = navigationActive,
        )
    }
}
