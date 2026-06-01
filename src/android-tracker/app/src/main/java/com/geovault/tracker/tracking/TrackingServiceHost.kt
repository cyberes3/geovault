package com.geovault.tracker.tracking

import android.content.Intent
import android.location.LocationListener
import android.os.IBinder
import com.geovault.tracker.positioning.PositioningRuntime

internal class TrackingServiceHost(
    private val service: TrackingService,
) {
    val runtime = PositioningRuntime(service)

    val locationListener: LocationListener get() = runtime.locationListener
    val gpsProviderReceiver get() = runtime.gpsProviderReceiver

    fun onCreate() = runtime.onCreate()

    fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = runtime.onStartCommand(intent, flags, startId)

    fun onBind(intent: Intent?): IBinder? = runtime.onBind(intent)

    fun onTaskRemoved(rootIntent: Intent?) = runtime.onTaskRemoved(rootIntent)

    fun onDestroy() = runtime.onDestroy()
}
