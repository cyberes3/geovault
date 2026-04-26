package com.geovault.common.update

import android.app.Application

private fun geoVaultVersionCheckSession(
    application: Application,
    rateLimitKey: String,
    releaseWorkerAppName: String,
    localFullCommitSha: () -> String,
): GeoVaultVersionCheckSession = GeoVaultVersionCheckSession(
    application = application,
    rateLimitKey = rateLimitKey,
    releaseWorkerAppName = releaseWorkerAppName,
    localFullCommitSha = localFullCommitSha,
)

/**
 * Canonical names and rate-limiter keys for GeoVault Android apps when talking to the
 * shared release / version-check worker. Must stay aligned with APK asset naming,
 * [com.geovault.common.update.WorkerVersionCheckApiClient] payloads, and backend release
 * asset selection (e.g. `GeoVault Places` prefix on APK filenames).
 *
 * Each nested object exposes [versionCheckSession] so apps wire the worker with one call
 * and cannot drift on rate-limit keys vs display names.
 */
object GeoVaultAndroidReleaseIdentity {

    object Places {
        const val WORKER_APP_NAME: String = "GeoVault Places"
        const val RATE_LIMIT_KEY: String = "places"

        fun versionCheckSession(
            application: Application,
            localFullCommitSha: () -> String,
        ): GeoVaultVersionCheckSession = geoVaultVersionCheckSession(
            application,
            RATE_LIMIT_KEY,
            WORKER_APP_NAME,
            localFullCommitSha,
        )
    }

    object Uploader {
        const val WORKER_APP_NAME: String = "GeoVault Uploader"
        const val RATE_LIMIT_KEY: String = "uploader"

        fun versionCheckSession(
            application: Application,
            localFullCommitSha: () -> String,
        ): GeoVaultVersionCheckSession = geoVaultVersionCheckSession(
            application,
            RATE_LIMIT_KEY,
            WORKER_APP_NAME,
            localFullCommitSha,
        )
    }

    object Tracker {
        const val WORKER_APP_NAME: String = "GeoVault Live Tracker"
        const val RATE_LIMIT_KEY: String = "tracker"

        fun versionCheckSession(
            application: Application,
            localFullCommitSha: () -> String,
        ): GeoVaultVersionCheckSession = geoVaultVersionCheckSession(
            application,
            RATE_LIMIT_KEY,
            WORKER_APP_NAME,
            localFullCommitSha,
        )
    }

    object SurveyDataViewer {
        const val WORKER_APP_NAME: String = "GeoVault Survey Data Viewer"
        const val RATE_LIMIT_KEY: String = "survey"

        fun versionCheckSession(
            application: Application,
            localFullCommitSha: () -> String,
        ): GeoVaultVersionCheckSession = geoVaultVersionCheckSession(
            application,
            RATE_LIMIT_KEY,
            WORKER_APP_NAME,
            localFullCommitSha,
        )
    }

    object NgsNavigator {
        const val WORKER_APP_NAME: String = "GeoVault NGS Navigator"
        const val RATE_LIMIT_KEY: String = "ngs_navigator"

        fun versionCheckSession(
            application: Application,
            localFullCommitSha: () -> String,
        ): GeoVaultVersionCheckSession = geoVaultVersionCheckSession(
            application,
            RATE_LIMIT_KEY,
            WORKER_APP_NAME,
            localFullCommitSha,
        )
    }
}
