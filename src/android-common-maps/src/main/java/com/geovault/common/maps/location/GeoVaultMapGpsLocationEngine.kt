package com.geovault.common.maps.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped owner of continuous map GPS.
 *
 * Guarantees:
 * - at most one fused continuous session for the whole process
 * - foreground-service elevation while any session is active
 * - idempotent acquire/release with refcounted listeners
 *
 * Hosts must not open their own continuous fused clients for map features. Acquire a session
 * here (directly or via [MapLocationRendererPlugin.startRenderingGpsLocation]).
 *
 * [acquire] / [GeoVaultMapGpsLocationSession.stop] are thread-safe. Location callbacks are
 * delivered on [mainLooper].
 */
class GeoVaultMapGpsLocationEngine(
    context: Context,
    private val provider: FusedLocationProviderPort,
    private val foreground: GeoVaultMapLocationForegroundController,
    private val mainLooper: Looper = Looper.getMainLooper(),
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(mainLooper)
    private val lock = Any()
    private val sessions = linkedMapOf<GeoVaultMapGpsLocationSessionImpl, Unit>()
    private var activeCallback: LocationCallback? = null
    private var activeIntervalMs: Long? = null
    private var foregroundStarted = false

    /**
     * Registers [onLocation] for continuous high-accuracy fixes. Starts the location FGS and
     * fused session on the first acquire; subsequent acquires share that session.
     *
     * Caller must hold fine or coarse location permission.
     */
    fun acquire(
        intervalMs: Long,
        onLocation: (Location) -> Unit,
    ): GeoVaultMapGpsLocationSession {
        val normalizedInterval = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val session = GeoVaultMapGpsLocationSessionImpl(
            intervalMs = normalizedInterval,
            onLocation = onLocation,
        )
        synchronized(lock) {
            sessions[session] = Unit
            reconcileLocked()
        }
        return session
    }

    private fun release(session: GeoVaultMapGpsLocationSessionImpl) {
        synchronized(lock) {
            if (sessions.remove(session) == null) return
            reconcileLocked()
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconcileLocked() {
        if (sessions.isEmpty()) {
            tearDownLocked()
            return
        }
        val desiredInterval = sessions.keys.minOf { it.intervalMs }
        ensureForegroundLocked()
        if (activeCallback != null && activeIntervalMs == desiredInterval) {
            return
        }
        stopProviderLocked()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val listeners = synchronized(lock) { sessions.keys.toList() }
                result.locations.forEach { location ->
                    val valid = LocationUpdates.validLocationOrNull(location) ?: return@forEach
                    listeners.forEach { session ->
                        session.dispatch(valid)
                    }
                }
            }
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            desiredInterval,
        )
            .setMinUpdateDistanceMeters(0f)
            .setMinUpdateIntervalMillis((desiredInterval / 2L).coerceAtLeast(MIN_HALF_INTERVAL_MS))
            .build()
        provider.requestUpdates(request, callback, mainLooper)
        activeCallback = callback
        activeIntervalMs = desiredInterval
    }

    private fun ensureForegroundLocked() {
        if (foregroundStarted) return
        foreground.ensureStarted(appContext)
        foregroundStarted = true
    }

    private fun tearDownLocked() {
        stopProviderLocked()
        if (foregroundStarted) {
            foreground.ensureStopped(appContext)
            foregroundStarted = false
        }
    }

    private fun stopProviderLocked() {
        val callback = activeCallback ?: return
        provider.removeUpdates(callback)
        activeCallback = null
        activeIntervalMs = null
    }

    private inner class GeoVaultMapGpsLocationSessionImpl(
        val intervalMs: Long,
        private val onLocation: (Location) -> Unit,
    ) : GeoVaultMapGpsLocationSession {
        private val stopped = AtomicBoolean(false)

        fun dispatch(location: Location) {
            if (stopped.get()) return
            if (Looper.myLooper() == mainLooper) {
                if (!stopped.get()) onLocation(location)
            } else {
                mainHandler.post {
                    if (!stopped.get()) onLocation(location)
                }
            }
        }

        override fun stop() {
            if (stopped.compareAndSet(false, true)) {
                release(this)
            }
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 500L
        private const val MIN_HALF_INTERVAL_MS = 250L

        @Volatile
        private var instance: GeoVaultMapGpsLocationEngine? = null

        fun get(context: Context): GeoVaultMapGpsLocationEngine {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: GeoVaultMapGpsLocationEngine(
                    context = context.applicationContext,
                    provider = PlayServicesContinuousLocationProvider(context.applicationContext),
                    foreground = AndroidGeoVaultMapLocationForegroundController,
                ).also { instance = it }
            }
        }

        /** Test-only: replace or clear the process singleton. */
        internal fun replaceForTests(engine: GeoVaultMapGpsLocationEngine?) {
            synchronized(this) {
                instance = engine
            }
        }
    }
}

/**
 * Handle returned by [GeoVaultMapGpsLocationEngine.acquire]. Call [stop] exactly once when the
 * subscriber no longer needs continuous fixes.
 */
interface GeoVaultMapGpsLocationSession {
    fun stop()
}
