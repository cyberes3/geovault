package com.geovault.common.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoVaultAuthConnectErrorsTest {

    @Test
    fun showAndClearRoundTrip() {
        var cleared = false
        GeoVaultAuthConnectErrors.setOnClearListener { cleared = true }
        try {
            GeoVaultAuthConnectErrors.show("Could not reach server.")
            assertEquals("Could not reach server.", GeoVaultAuthConnectErrors.message.value)
            GeoVaultAuthConnectErrors.clear()
            assertNull(GeoVaultAuthConnectErrors.message.value)
            assertEquals(true, cleared)
        } finally {
            GeoVaultAuthConnectErrors.setOnClearListener(null)
            GeoVaultAuthConnectErrors.clear(notifyListener = false)
        }
    }
}
