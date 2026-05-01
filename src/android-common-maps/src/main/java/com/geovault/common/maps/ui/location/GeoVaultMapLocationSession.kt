package com.geovault.common.maps.ui.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.geovault.common.maps.ui.GeoVaultMapFabAction
import com.geovault.common.maps.ui.camerafollow.GeoVaultMapHeadingFollowFabBundle

data class GeoVaultMapLocationSessionInput(
    val isActive: Boolean,
    val hasLocationPermission: Boolean,
    val isMapReady: Boolean,
    val userLocationRequested: Boolean,
    val positionFollowDesired: Boolean,
    val headingFollowDesired: Boolean,
    val navigationActive: Boolean = false,
)

data class GeoVaultMapLocationSessionDecision(
    val shouldStreamGps: Boolean,
    val shouldEnablePuck: Boolean,
) {
    companion object {
        val Disabled = GeoVaultMapLocationSessionDecision(
            shouldStreamGps = false,
            shouldEnablePuck = false,
        )
    }
}

/**
 * Shared policy for map hosts that need the user's current location.
 *
 * This deliberately separates location intent from camera follow:
 * - location intent controls GPS streaming and the visible user-location puck/chevron;
 * - camera follow state only controls MapLibre camera mode.
 */
class GeoVaultMapLocationSessionPolicy {
    fun decide(input: GeoVaultMapLocationSessionInput): GeoVaultMapLocationSessionDecision {
        val locationIntentActive = input.userLocationRequested ||
            input.positionFollowDesired ||
            input.headingFollowDesired ||
            input.navigationActive
        if (!input.isActive ||
            !input.hasLocationPermission ||
            !input.isMapReady ||
            !locationIntentActive
        ) {
            return GeoVaultMapLocationSessionDecision.Disabled
        }
        return GeoVaultMapLocationSessionDecision(
            shouldStreamGps = true,
            shouldEnablePuck = true,
        )
    }
}

data class GeoVaultMapLocationSession(
    val gpsFabAction: GeoVaultMapFabAction,
    val decision: GeoVaultMapLocationSessionDecision,
    val userLocationRequested: Boolean,
)

/**
 * Wraps the shared heading-follow bundle with persistent location-session intent.
 *
 * The GPS FAB requests/refreshes the location session and recenters the camera; it does not
 * toggle GPS off. Navigation also requests the location session, so stopping navigation removes
 * only the navigation overlay and leaves the user-location puck available.
 */
@Composable
fun rememberGeoVaultMapLocationSession(
    headingFollowFabs: GeoVaultMapHeadingFollowFabBundle,
    hasLocationPermission: Boolean,
    isMapReady: Boolean,
    isActive: Boolean = true,
    navigationActive: Boolean = false,
    policy: GeoVaultMapLocationSessionPolicy = remember { GeoVaultMapLocationSessionPolicy() },
): GeoVaultMapLocationSession {
    var userLocationRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(navigationActive) {
        if (navigationActive) {
            userLocationRequested = true
        }
    }
    val gpsFabAction = remember(headingFollowFabs.gpsPositionFollowFab) {
        headingFollowFabs.gpsPositionFollowFab.copy(
            onTap = {
                userLocationRequested = true
                headingFollowFabs.gpsPositionFollowFab.onTap?.invoke()
            },
        )
    }
    val decision = policy.decide(
        GeoVaultMapLocationSessionInput(
            isActive = isActive,
            hasLocationPermission = hasLocationPermission,
            isMapReady = isMapReady,
            userLocationRequested = userLocationRequested,
            positionFollowDesired = headingFollowFabs.positionFollowDesired,
            headingFollowDesired = headingFollowFabs.headingFollowDesired,
            navigationActive = navigationActive,
        ),
    )
    return GeoVaultMapLocationSession(
        gpsFabAction = gpsFabAction,
        decision = decision,
        userLocationRequested = userLocationRequested,
    )
}
