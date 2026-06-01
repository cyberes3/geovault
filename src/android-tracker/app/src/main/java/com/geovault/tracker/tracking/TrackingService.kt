package com.geovault.tracker.tracking

import android.content.Intent
import android.os.IBinder
import android.app.Service

class TrackingService : Service() {
    private lateinit var host: TrackingServiceHost

    override fun onCreate() {
        super.onCreate()
        host = TrackingServiceHost(this)
        host.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return host.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        host.onTaskRemoved(rootIntent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        host.onDestroy()
        super.onDestroy()
    }
}
