package com.geovault.common

import android.content.Context
import android.net.Uri

/**
 * Contract and helper for cross-app server URL discovery.
 * Prefill runs on release builds only; debug builds return empty.
 */
object ServerUrlContract {

    private const val PACKAGE_PREFIX = "com.geovault."
    private const val DEBUG_SUFFIX = ".debug"
    const val AUTHORITY_SUFFIX = ".serverurl"
    const val PATH_SERVER_URL = "server_url"
    const val COLUMN_SERVER_URL = "server_url"

    /**
     * Server URLs from other installed GeoVault release apps. Empty on debug builds.
     * Caller may prefill when size == 1.
     */
    @JvmStatic
    fun getServerUrlsFromOtherApps(context: Context): Set<String> {
        val ourPackage = context.packageName
        if (ourPackage.endsWith(DEBUG_SUFFIX)) return emptySet()

        val packageManager = context.packageManager
        val ourMain = ourPackage

        val installedPackages = packageManager.getInstalledPackages(0).map { it.packageName }
        val mainPackages = installedPackages
            .asSequence()
            .filter { it.startsWith(PACKAGE_PREFIX) }
            .filter { !it.endsWith(DEBUG_SUFFIX) }
            .filter { it != ourMain }
            .toSet()

        val urls = mutableSetOf<String>()
        for (main in mainPackages) {
            val authority = main + AUTHORITY_SUFFIX
            val uri = Uri.parse("content://$authority/$PATH_SERVER_URL")
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(COLUMN_SERVER_URL)
                        if (idx >= 0) {
                            val url = cursor.getString(idx)?.trim() ?: ""
                            if (url.isNotEmpty()) urls.add(url)
                        }
                    }
                }
            } catch (_: Exception) {
                // Other app not installed or permission denied; skip
            }
        }
        return urls
    }
}
