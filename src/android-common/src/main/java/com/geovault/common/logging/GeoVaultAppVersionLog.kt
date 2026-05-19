package com.geovault.common.logging

import android.app.Application
import android.content.pm.PackageManager

/**
 * Logs app version metadata once at cold start to logcat and, when capture logging is enabled,
 * to the SQLite capture log cache. Call from [Application.onCreate] after [android.app.Application.onCreate].
 */
object GeoVaultAppVersionLog {

    const val TAG = "AppVersion"

    private val BUILD_DATE_PREFIX = Regex("""^(\d{4}-\d{2}-\d{2})""")

    /**
     * @param gitCommitSha Full 40-character git commit SHA from the app module's [android.os.BuildConfig].
     */
    fun log(application: Application, gitCommitSha: String) {
        GeoVaultCaptureLog.init(application)
        val versionName = readVersionName(application)
        val versionCode = readVersionCode(application)
        val buildDate = parseBuildDateFromVersionName(versionName)
        val normalizedSha = gitCommitSha.trim().lowercase()
        GeoVaultCaptureLog.i(
            TAG,
            "versionName=$versionName versionCode=$versionCode buildDate=$buildDate gitCommitSha=$normalizedSha",
        )
    }

    internal fun parseBuildDateFromVersionName(versionName: String): String {
        val match = BUILD_DATE_PREFIX.find(versionName)
        return match?.groupValues?.get(1) ?: "unknown"
    }

    private fun readVersionName(application: Application): String {
        return try {
            val info =
                application.packageManager.getPackageInfo(
                    application.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            info.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun readVersionCode(application: Application): Long {
        return try {
            val info =
                application.packageManager.getPackageInfo(
                    application.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            info.longVersionCode
        } catch (_: Exception) {
            -1L
        }
    }
}
