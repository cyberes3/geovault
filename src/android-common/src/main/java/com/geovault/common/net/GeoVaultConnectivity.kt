package com.geovault.common.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import java.io.IOException
import kotlin.coroutines.resume

enum class GeoVaultReachability {
    Offline,
    Online,
    ServerReachable,
}

object GeoVaultConnectivity {
    private const val TAG = "GeoVaultConnectivity"
    private const val HEALTH_PATH = "/api/health/"

    fun hasValidatedInternet(context: Context): Boolean {
        val connectivity = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun snapshot(context: Context): GeoVaultReachability {
        return if (hasValidatedInternet(context)) GeoVaultReachability.Online else GeoVaultReachability.Offline
    }

    suspend fun probeServerReachable(baseUrl: String): Boolean {
        val parsed = GeoVaultServerUrl.parse(baseUrl) ?: return false
        return probeServerReachable(parsed)
    }

    suspend fun probeServerReachable(base: GeoVaultServerUrl): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val url = base.resolve(HEALTH_PATH)
            val request = Request.Builder().url(url).get().build()
            val call = GeoVaultHttp.probeClient().newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "probe: transport failure url=$url msg=${e.message}", e)
                    if (continuation.isActive) continuation.resume(false)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    val code = response.code
                    response.close()
                    Log.d(TAG, "probe: host answered url=$url http=$code")
                    if (continuation.isActive) continuation.resume(true)
                }
            })
        }
    }

    /**
     * Debounced callback when the default network gains validated internet.
     * Replaces per-app ConnectivityManager callback copies.
     */
    class RecoveryMonitor(
        context: Context,
        private val onRecovery: () -> Unit,
    ) {
        private val app = context.applicationContext
        private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val mainHandler = Handler(Looper.getMainLooper())
        private val lock = Any()
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
            synchronized(lock) {
                if (!started) return
                debouncedFire?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    Log.d(TAG, "validated_internet_callback")
                    onRecovery()
                }
                debouncedFire = runnable
                mainHandler.postDelayed(runnable, DEBOUNCE_MS)
            }
        }

        fun start() {
            synchronized(lock) {
                if (started) return
                started = true
            }
            connectivity.registerDefaultNetworkCallback(callback)
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                scheduleDebouncedNotify()
            }
        }

        fun stop() {
            synchronized(lock) {
                if (!started) return
                started = false
                debouncedFire?.let { mainHandler.removeCallbacks(it) }
                debouncedFire = null
            }
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }

        companion object {
            private const val DEBOUNCE_MS = 400L
        }
    }
}
