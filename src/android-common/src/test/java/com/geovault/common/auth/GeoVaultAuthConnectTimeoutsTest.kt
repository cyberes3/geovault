package com.geovault.common.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultAuthConnectTimeoutsTest {

    @Test
    fun serverUrlResolveTimeoutMsMatchesSecondsConstant() {
        assertEquals(5_000L, GeoVaultAuthConnectTimeouts.serverUrlResolveTimeoutMs)
        assertEquals(5L, GeoVaultAuthConnectTimeouts.SERVER_URL_RESOLVE_TIMEOUT_SECONDS)
    }
}
