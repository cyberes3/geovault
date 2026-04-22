package com.geovault.common.update

/**
 * Canonical names and rate-limiter keys for GeoVault Android apps when talking to the
 * shared release / version-check worker. Must stay aligned with APK asset naming,
 * [com.geovault.common.update.WorkerVersionCheckApiClient] payloads, and backend release
 * asset selection (e.g. `GeoVault Places` prefix on APK filenames).
 */
object GeoVaultAndroidReleaseIdentity {

    object Places {
        const val WORKER_APP_NAME: String = "GeoVault Places"
        const val RATE_LIMIT_KEY: String = "places"
    }

    object Uploader {
        const val WORKER_APP_NAME: String = "GeoVault Uploader"
        const val RATE_LIMIT_KEY: String = "uploader"
    }

    object Tracker {
        const val WORKER_APP_NAME: String = "GeoVault Live Tracker"
        const val RATE_LIMIT_KEY: String = "tracker"
    }

    object SurveyDataViewer {
        const val WORKER_APP_NAME: String = "GeoVault Survey Data Viewer"
        const val RATE_LIMIT_KEY: String = "survey"
    }
}
