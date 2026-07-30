package com.geovault.common.maps.location

import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class GeoVaultMapGpsLocationEngineTest {
    private lateinit var context: Context
    private lateinit var provider: FakeFusedLocationProviderPort
    private lateinit var foreground: FakeForegroundController
    private lateinit var engine: GeoVaultMapGpsLocationEngine

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        provider = FakeFusedLocationProviderPort()
        foreground = FakeForegroundController()
        engine = GeoVaultMapGpsLocationEngine(
            context = context,
            provider = provider,
            foreground = foreground,
            mainLooper = Looper.getMainLooper(),
        )
        GeoVaultMapGpsLocationEngine.replaceForTests(engine)
    }

    @After
    fun tearDown() {
        GeoVaultMapGpsLocationEngine.replaceForTests(null)
    }

    @Test
    fun acquire_startsForegroundAndProvider_onceForMultipleSessions() {
        val first = mutableListOf<Location>()
        val second = mutableListOf<Location>()

        val sessionA = engine.acquire(1000L) { first += it }
        val sessionB = engine.acquire(2000L) { second += it }

        assertEquals(1, foreground.startCount)
        assertEquals(1, provider.requestCount)
        assertEquals(1000L, provider.lastIntervalMs)

        val fix = location(11.0, 22.0)
        provider.emit(fix)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(fix), first)
        assertEquals(listOf(fix), second)

        sessionA.stop()
        assertEquals(0, foreground.stopCount)
        assertTrue(foreground.isStarted)

        sessionB.stop()
        assertEquals(1, foreground.stopCount)
        assertFalse(foreground.isStarted)
        assertTrue(provider.removeCount >= 1)
    }

    @Test
    fun stop_isIdempotent() {
        val session = engine.acquire(1000L) { }
        session.stop()
        session.stop()
        assertEquals(1, foreground.stopCount)
        assertEquals(1, provider.removeCount)
    }

    @Test
    fun reconcile_usesMinimumIntervalAmongSessions() {
        val sessionSlow = engine.acquire(5000L) { }
        assertEquals(5000L, provider.lastIntervalMs)

        val sessionFast = engine.acquire(1000L) { }
        assertEquals(1000L, provider.lastIntervalMs)
        assertEquals(2, provider.requestCount)

        sessionFast.stop()
        assertEquals(5000L, provider.lastIntervalMs)

        sessionSlow.stop()
        assertFalse(foreground.isStarted)
    }

    private fun location(lat: Double, lon: Double): Location {
        return Location("test").apply {
            latitude = lat
            longitude = lon
            accuracy = 5f
        }
    }

    private class FakeForegroundController : GeoVaultMapLocationForegroundController {
        var startCount = 0
        var stopCount = 0
        var isStarted = false

        override fun ensureStarted(context: Context) {
            startCount += 1
            isStarted = true
        }

        override fun ensureStopped(context: Context) {
            stopCount += 1
            isStarted = false
        }
    }

    private class FakeFusedLocationProviderPort : FusedLocationProviderPort {
        var requestCount = 0
        var removeCount = 0
        var lastIntervalMs: Long? = null
        private var callback: LocationCallback? = null

        override fun requestUpdates(
            request: LocationRequest,
            callback: LocationCallback,
            looper: Looper,
        ) {
            requestCount += 1
            lastIntervalMs = request.intervalMillis
            this.callback = callback
        }

        override fun removeUpdates(callback: LocationCallback) {
            removeCount += 1
            if (this.callback === callback) {
                this.callback = null
            }
        }

        fun emit(location: Location) {
            callback?.onLocationResult(LocationResult.create(listOf(location)))
        }
    }
}
