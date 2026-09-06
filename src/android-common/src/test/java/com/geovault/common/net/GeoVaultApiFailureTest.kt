package com.geovault.common.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GeoVaultApiFailureTest {
    @Test
    fun fromThrowable_returnsSameInstance() {
        val original = GeoVaultApiFailure(httpCode = 404, serverMessage = "missing", operation = "fetch")
        assertSame(original, GeoVaultApiFailure.fromThrowable(original))
    }

    @Test
    fun message_includesOperationCodeAndServerText() {
        val failure = GeoVaultApiFailure(httpCode = 409, serverMessage = "already exists", operation = "createPlace")
        assertEquals("createPlace: HTTP 409: already exists", failure.message)
    }

    @Test
    fun classify_usesHttpCodeNotMessageText() {
        val failure = GeoVaultApiFailure(httpCode = 401, serverMessage = "nope")
        assertEquals(
            com.geovault.common.sync.GeoVaultHttpFailureKind.Auth,
            com.geovault.common.sync.GeoVaultHttpFailureClassifier.classify(failure),
        )
    }
}
