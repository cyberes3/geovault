package com.geovault.common

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class ServerUrlProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val providerContext = context ?: return null
        if (uri.pathSegments.lastOrNull() != ServerUrlContract.PATH_SERVER_URL) return null
        val url = GeovaultAuthManager.getServerUrl(providerContext)
        return MatrixCursor(arrayOf(ServerUrlContract.COLUMN_SERVER_URL)).apply {
            addRow(arrayOf(url))
        }
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
