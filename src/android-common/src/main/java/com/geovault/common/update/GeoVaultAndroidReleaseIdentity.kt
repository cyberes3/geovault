package com.geovault.common.update

import android.app.Application
import com.geovault.common.auth.GeoVaultAuthSession

private fun geoVaultAppUpdateCoordinator(
    application: Application,
    cacheKey: String,
    releaseWorkerAppName: String,
    localFullCommitSha: () -> String,
): GeoVaultAppUpdateCoordinator {
    val checker = AppVersionChecker()
    val appContext = application.applicationContext
    return GeoVaultAppUpdateCoordinator(
        cacheKey = cacheKey,
        releaseWorkerAppName = releaseWorkerAppName,
        localFullCommitSha = localFullCommitSha,
        isLoggedIn = { GeoVaultAuthSession.get().isLoggedIn() },
        peekCachedUpdate = { key, localSha ->
            checker.peekCachedUpdate(
                context = appContext,
                cacheKey = key,
                localFullCommitSha = localSha,
            )
        },
        checkForUpdate = { key, request ->
            checker.checkForUpdate(
                context = appContext,
                request = request,
                cacheKey = key,
            )
        },
    )
}

/**
 * Canonical names and on-disk cache keys for GeoVault Android apps when talking to the
 * shared release / version-check worker. Must stay aligned with APK asset naming,
 * [com.geovault.common.update.WorkerVersionCheckApiClient] payloads, and backend release
 * asset selection (e.g. `GeoVault Places` prefix on APK filenames).
 *
 * Each nested object exposes [updateCoordinator] so apps wire the worker with one call
 * and cannot drift on cache keys vs display names.
 */
object GeoVaultAndroidReleaseIdentity {

    object Places {
        const val WORKER_APP_NAME: String = "GeoVault Places"
        const val CACHE_KEY: String = "places"

        fun updateCoordinator(
            application: Application,
            localFullCommitSha: () -> String,
        ): GeoVaultAppUpdateCoordinator = geoVaultAppUpdateCoordinator(
            application,
            CACHE_KEY,
            WORKER_APP_NAME,
            localFullCommitSha,
        )
    }

    object Uploader {
        const val WORKER_APP_NAME: String = "GeoVault Uploader"
        const val CACHE_KEY: String = "uploader"

        fun updateCoordinator(
            application: Application,
            localFullCommitSha: () -> String,
        ): GeoVaultAppUpdateCoordinator = geoVaultAppUpdateCoordinator(
            application,
            CACHE_KEY,
            WORKER_APP_NAME,
            localFullCommitSha,
        )
    }

    object Tracker {
        const val WORKER_APP_NAME: String = "GeoVault Live Tracker"
        const val CACHE_KEY: String = "tracker"

        fun updateCoordinator(
            application: Application,
            localFullCommitSha: () -> String,
        ): GeoVaultAppUpdateCoordinator = geoVaultAppUpdateCoordinator(
            application,
            CACHE_KEY,
            WORKER_APP_NAME,
            localFullCommitSha,
        )
    }
}
