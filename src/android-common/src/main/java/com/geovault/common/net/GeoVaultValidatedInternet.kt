package com.geovault.common.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object GeoVaultValidatedInternet {
    fun isAvailable(context: Context): Boolean {
        val connectivity = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
