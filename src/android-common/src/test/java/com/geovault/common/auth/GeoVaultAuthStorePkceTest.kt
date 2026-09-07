package com.geovault.common.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoVaultAuthStorePkceTest {

    @Test
    fun peekDoesNotClearStoredPkce() {
        val store = GeoVaultAuthStore.getInstance(RuntimeEnvironment.getApplication())
        store.savePkceState("verifier", "state-1")
        assertEquals("verifier" to "state-1", store.peekPkceState())
        assertEquals("verifier" to "state-1", store.peekPkceState())
        assertEquals("verifier" to "state-1", store.getAndClearPkceState())
        assertNull(store.peekPkceState())
        assertNull(store.getAndClearPkceState())
        assertNotNull(store)
    }
}
