package com.geovault.common

import android.content.Context
import android.net.Uri

object ServerUrlContract {
    private const val PACKAGE_PREFIX = "com.geovault."
    private const val DEBUG_SUFFIX = ".debug"
    const val AUTHORITY_SUFFIX = ".serverurl"
    const val PATH_SERVER_URL = "server_url"
    const val COLUMN_SERVER_URL = "server_url"

    fun getServerUrlsFromOtherApps(context: Context): Set<String> {
        val ourPackage = context.packageName
        val packages = context.packageManager.getInstalledPackages(0)
            .map { it.packageName }
            .asSequence()
            .filter { it.startsWith(PACKAGE_PREFIX) }
            .filter { !it.endsWith(DEBUG_SUFFIX) }
            .filter { it != ourPackage }
            .toSet()

        val urls = mutableSetOf<String>()
        for (pkg in packages) {
            val uri = Uri.parse("content://${pkg}${AUTHORITY_SUFFIX}/$PATH_SERVER_URL")
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(COLUMN_SERVER_URL)
                        if (index >= 0) {
                            cursor.getString(index)?.trim()?.takeIf { it.isNotEmpty() }?.let(urls::add)
                        }
                    }
                }
            } catch (_: Exception) {
                // Skip apps that do not expose the provider.
            }
        }
        return urls
    }
}
