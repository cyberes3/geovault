package com.geovault.common.sync

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultHttpFailureClassifierTest {
    @Test
    fun classifiesHttpCodes() {
        assertEquals(GeoVaultHttpFailureKind.Auth, GeoVaultHttpFailureClassifier.classify(401, null, null))
        assertEquals(GeoVaultHttpFailureKind.NotFound, GeoVaultHttpFailureClassifier.classify(404, null, null))
        assertEquals(GeoVaultHttpFailureKind.Conflict, GeoVaultHttpFailureClassifier.classify(409, null, null))
        assertEquals(GeoVaultHttpFailureKind.PermanentClient, GeoVaultHttpFailureClassifier.classify(400, null, null))
        assertEquals(GeoVaultHttpFailureKind.RetryableServer, GeoVaultHttpFailureClassifier.classify(500, null, null))
    }

    @Test
    fun classifiesTransportAndMessages() {
        assertEquals(
            GeoVaultHttpFailureKind.RetryableNetwork,
            GeoVaultHttpFailureClassifier.classifyThrowable(UnknownHostException("dns")),
        )
        assertEquals(
            GeoVaultHttpFailureKind.RetryableNetwork,
            GeoVaultHttpFailureClassifier.classifyThrowable(SocketTimeoutException("timeout")),
        )
        assertEquals(
            GeoVaultHttpFailureKind.Conflict,
            GeoVaultHttpFailureClassifier.classify(null, "A place with the same name and coordinates already exists.", null),
        )
        assertEquals(
            GeoVaultHttpFailureKind.PermanentClient,
            GeoVaultHttpFailureClassifier.classify(
                com.geovault.common.net.GeoVaultApiFailure(httpCode = 400, serverMessage = "Validation failed"),
            ),
        )
        assertEquals(
            GeoVaultHttpFailureKind.NotFound,
            GeoVaultHttpFailureClassifier.classify(
                com.geovault.common.net.GeoVaultApiFailure(httpCode = 404, serverMessage = "Resource not found"),
            ),
        )
    }

    @Test
    fun dispositionMatchesPolicy() {
        assertEquals(
            GeoVaultQueuedSyncItemDisposition.KeepRetrying,
            GeoVaultQueuedSyncFailurePolicy.dispositionFor(GeoVaultHttpFailureKind.RetryableNetwork),
        )
        assertEquals(
            GeoVaultQueuedSyncItemDisposition.DropAndSurface,
            GeoVaultQueuedSyncFailurePolicy.dispositionFor(GeoVaultHttpFailureKind.PermanentClient),
        )
        assertEquals(
            GeoVaultQueuedSyncItemDisposition.ResolveConflict,
            GeoVaultQueuedSyncFailurePolicy.dispositionFor(GeoVaultHttpFailureKind.Conflict),
        )
        assertEquals(
            GeoVaultQueuedSyncItemDisposition.RecreateOrDiscard,
            GeoVaultQueuedSyncFailurePolicy.dispositionFor(GeoVaultHttpFailureKind.NotFound),
        )
        assertEquals(
            GeoVaultQueuedSyncItemDisposition.RequireAuth,
            GeoVaultQueuedSyncFailurePolicy.dispositionFor(GeoVaultHttpFailureKind.Auth),
        )
    }
}
