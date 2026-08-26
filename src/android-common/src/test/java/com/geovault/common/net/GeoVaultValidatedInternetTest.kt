package com.geovault.common.net

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoVaultValidatedInternetTest {

    @Test
    fun isAvailable_withoutActiveNetwork_isFalse() {
        assertFalse(GeoVaultValidatedInternet.isAvailable(RuntimeEnvironment.getApplication()))
    }
}
