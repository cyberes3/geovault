package com.geovault.common

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * ContentProvider that exposes this app's configured GeoVault server URL (read-only).
 * Each app registers this provider with its own authority (e.g. com.geovault.places.serverurl).
 * Release builds use this for cross-app prefill; debug builds skip prefill.
 */
class ServerUrlProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        if (uri.pathSegments.lastOrNull() != ServerUrlContract.PATH_SERVER_URL) {
            return null
        }
        val url = try {
            GeovaultAuthManager.getServerUrl(context)
        } catch (_: Exception) {
            ""
        }
        val cursor = MatrixCursor(arrayOf(ServerUrlContract.COLUMN_SERVER_URL))
        cursor.addRow(arrayOf(url))
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
