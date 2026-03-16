package com.geovault.tracker.fragments.map

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.geovault.tracker.LiveTrackStreamingService

internal object MapBroadcastHandlers {
    fun createLiveTrackPointReceiver(
        onTrackPoint: (trackId: String, lat: Double, lon: Double, tsMs: Long, accuracyMeters: Float?) -> Unit
    ): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != LiveTrackStreamingService.BROADCAST_TRACK_POINT) return
                val trackId = intent.getStringExtra(LiveTrackStreamingService.EXTRA_TRACK_ID) ?: return
                val lat = intent.getDoubleExtra(LiveTrackStreamingService.EXTRA_POINT_LAT, 0.0)
                val lon = intent.getDoubleExtra(LiveTrackStreamingService.EXTRA_POINT_LON, 0.0)
                val tsMs = intent.getLongExtra(LiveTrackStreamingService.EXTRA_POINT_TS_MS, 0L)
                val accuracyMeters = if (intent.hasExtra(LiveTrackStreamingService.EXTRA_ACCURACY_METERS)) {
                    intent.getFloatExtra(LiveTrackStreamingService.EXTRA_ACCURACY_METERS, 0f).takeIf { it > 0f }
                } else null
                onTrackPoint(trackId, lat, lon, tsMs, accuracyMeters)
            }
        }
    }
}
