package com.geovault.common.sync

/**
 * What to do with a queued offline item after a sync attempt fails.
 */
enum class GeoVaultQueuedSyncItemDisposition {
    /** Leave in queue and retry later. */
    KeepRetrying,

    /** Remove from queue; surface the error (validation / permanent client). */
    DropAndSurface,

    /** Conflict/duplicate — resolve specially (adopt existing, save as new, etc.). */
    ResolveConflict,

    /** Target gone — recreate as create or discard with UX. */
    RecreateOrDiscard,

    /** Auth broken — stop treating as offline success; require re-login. */
    RequireAuth,
}

object GeoVaultQueuedSyncFailurePolicy {
    fun dispositionFor(kind: GeoVaultHttpFailureKind): GeoVaultQueuedSyncItemDisposition {
        return when (kind) {
            GeoVaultHttpFailureKind.RetryableNetwork,
            GeoVaultHttpFailureKind.RetryableServer,
            GeoVaultHttpFailureKind.Unknown -> GeoVaultQueuedSyncItemDisposition.KeepRetrying
            GeoVaultHttpFailureKind.PermanentClient -> GeoVaultQueuedSyncItemDisposition.DropAndSurface
            GeoVaultHttpFailureKind.Conflict -> GeoVaultQueuedSyncItemDisposition.ResolveConflict
            GeoVaultHttpFailureKind.NotFound -> GeoVaultQueuedSyncItemDisposition.RecreateOrDiscard
            GeoVaultHttpFailureKind.Auth -> GeoVaultQueuedSyncItemDisposition.RequireAuth
        }
    }

    fun shouldFallbackToOfflineSave(kind: GeoVaultHttpFailureKind): Boolean {
        return when (dispositionFor(kind)) {
            GeoVaultQueuedSyncItemDisposition.KeepRetrying,
            GeoVaultQueuedSyncItemDisposition.ResolveConflict,
            GeoVaultQueuedSyncItemDisposition.RecreateOrDiscard -> true
            GeoVaultQueuedSyncItemDisposition.DropAndSurface,
            GeoVaultQueuedSyncItemDisposition.RequireAuth -> false
        }
    }

    fun shouldRemoveFromOfflineQueue(kind: GeoVaultHttpFailureKind, hasServerId: Boolean): Boolean {
        return dispositionFor(kind) == GeoVaultQueuedSyncItemDisposition.DropAndSurface && hasServerId
    }
}
