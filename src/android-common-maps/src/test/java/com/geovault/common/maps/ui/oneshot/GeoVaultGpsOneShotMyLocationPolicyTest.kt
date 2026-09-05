package com.geovault.common.maps.ui.oneshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultGpsOneShotMyLocationPolicyTest {
    @Test
    fun overrideDoesNotSkipPuckWhenHostWantsPuck() {
        assertTrue(GeoVaultGpsOneShotMyLocationPolicy.shouldEnablePuck(showUserLocationPuck = true))
    }

    @Test
    fun hostCanHidePuckExplicitly() {
        assertFalse(GeoVaultGpsOneShotMyLocationPolicy.shouldEnablePuck(showUserLocationPuck = false))
    }
}
