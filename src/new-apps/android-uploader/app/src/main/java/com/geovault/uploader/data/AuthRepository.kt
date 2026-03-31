package com.geovault.uploader.data

import android.content.Context
import com.geovault.common.GeovaultAuthManager

class AuthRepository(private val context: Context) {
    fun getNormalizedServerUrl(): String =
        GeovaultAuthManager.normalizeServerUrl(GeovaultAuthManager.getServerUrl(context))

    fun isLoggedIn(): Boolean = GeovaultAuthManager.isLoggedIn(context)

    fun getCachedUserEmail(): String? = GeovaultAuthManager.getCachedUserEmail(context)

    fun fetchUserEmail(callback: (String?) -> Unit) {
        GeovaultAuthManager.fetchUserStatus(context, callback)
    }
}
