package com.geovault.tracker.streaming

import android.app.Service
import android.net.ConnectivityManager
import android.net.Network
import com.geovault.common.logging.GeoVaultCaptureLog

/**
 * Default-network callback. Mid-backoff reconnect is the only action.
 */
internal class LiveStreamNetwork(
    private val service: Service,
    private val onAvailableWhileWaiting: () -> Unit,
) {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun register() {
        val connectivityManager =
            service.getSystemService(Service.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onAvailableWhileWaiting()
            }
        }
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { e -> GeoVaultCaptureLog.w(TAG, "Failed to register connectivity callback", e) }
    }

    fun unregister() {
        val callback = networkCallback ?: return
        networkCallback = null
        val connectivityManager =
            service.getSystemService(Service.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private companion object {
        const val TAG = "LiveTrackStreaming"
    }
}
