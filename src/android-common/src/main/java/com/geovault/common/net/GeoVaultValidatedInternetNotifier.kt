package com.geovault.common.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Invokes [onValidatedInternet] when the default network gains validated internet access
 * ([NetworkCapabilities.NET_CAPABILITY_VALIDATED]). Debounced so rapid capability updates do not
 * spam callers.
 *
 * Call [start] while waiting on connectivity-sensitive work and [stop] when done (e.g. host is
 * already reachable or user signed out).
 */
class GeoVaultValidatedInternetNotifier(
    context: Context,
    private val onValidatedInternet: () -> Unit,
) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var debouncedFire: Runnable? = null
    private var started = false

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                scheduleDebouncedNotify()
            }
        }

    private fun scheduleDebouncedNotify() {
        debouncedFire?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            Log.d(TAG, "validated_internet_callback")
            onValidatedInternet()
        }
        debouncedFire = runnable
        mainHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    fun start() {
        if (started) return
        started = true
        connectivity.registerDefaultNetworkCallback(callback)
        fireImmediatelyIfAlreadyValidated()
    }

    private fun fireImmediatelyIfAlreadyValidated() {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            scheduleDebouncedNotify()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        debouncedFire?.let { mainHandler.removeCallbacks(it) }
        debouncedFire = null
        connectivity.unregisterNetworkCallback(callback)
    }

    companion object {
        private const val TAG = "GeoVaultValidatedNet"
        private const val DEBOUNCE_MS = 400L
    }
}
