package com.geovault.tracker

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.WireTimestampNormalizer
import com.geovault.tracker.streaming.LiveStreamRuntime
import org.json.JSONObject

class LiveTrackStreamingService : Service() {
    companion object {
        const val ACTION_START = "com.geovault.tracker.LIVE_TRACK_STREAMING_START"
        const val ACTION_STOP = "com.geovault.tracker.LIVE_TRACK_STREAMING_STOP"
        const val ACTION_RESHOW_FOREGROUND = "com.geovault.tracker.STREAMING_ACTION_RESHOW_FOREGROUND"
        const val NOTIFICATION_DISMISSED_ACTION = "com.geovault.tracker.STREAMING_NOTIFICATION_DISMISSED"
        const val NOTIFICATION_ID = 102
    }

    private lateinit var runtime: LiveStreamRuntime

    override fun onCreate() {
        runtime = LiveStreamRuntime.create(this)
        runtime.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return runtime.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runtime.onTaskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int) {
        runtime.onTimeout()
    }

    override fun onDestroy() {
        runtime.onDestroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

object StreamingTrackPointParser {
    fun parseTrackUpdatedMessage(rawJson: String): TrackPoint? {
        return parseTrackUpdatedMessages(rawJson).firstOrNull()
    }

    /** True for the app-level pong reply to the live-stream liveness ping. */
    fun isPongMessage(rawJson: String): Boolean {
        val json = JSONObject(rawJson)
        return json.optString("module", "") == "live_track" && json.optString("type", "") == "pong"
    }

    fun parseTrackUpdatedMessages(rawJson: String, nowMs: Long = System.currentTimeMillis()): List<TrackPoint> {
        val json = JSONObject(rawJson)
        if (json.optString("module", "") != "live_track" || json.optString("type", "") != "track_updated") {
            return emptyList()
        }
        val data = json.optJSONObject("data") ?: return emptyList()
        val trackId = data.optString("track_id", "").trim()
        if (trackId.isBlank()) return emptyList()
        val updates = data.optJSONArray("updates")
        if (updates != null) {
            return (0 until updates.length()).mapNotNull { index ->
                val update = updates.optJSONObject(index) ?: return@mapNotNull null
                parsePoint(
                    trackId = trackId,
                    pointArr = update.optJSONArray("point"),
                    props = update.optJSONObject("props"),
                    nowMs = nowMs,
                )
            }
        }
        return listOfNotNull(
            parsePoint(
                trackId = trackId,
                pointArr = data.optJSONArray("point"),
                props = data.optJSONObject("props"),
                nowMs = nowMs,
            )
        )
    }

    private fun parsePoint(
        trackId: String,
        pointArr: org.json.JSONArray?,
        props: JSONObject?,
        nowMs: Long,
    ): TrackPoint? {
        if (pointArr == null || pointArr.length() < 2) return null
        val lon = pointArr.getDouble(0)
        val lat = pointArr.getDouble(1)
        val ts = if (pointArr.length() >= 3) {
            WireTimestampNormalizer.normalizeToMilliseconds(pointArr.optLong(2, 0L)) ?: nowMs
        } else {
            nowMs
        }
        val acc = props?.optDouble("acc", Double.NaN)?.takeIf { !it.isNaN() }?.toFloat()
        val propsJson = props?.takeIf { it.length() > 0 }?.toString()
        return TrackPoint(
            trackerId = trackId,
            timeMs = ts,
            latitude = lat,
            longitude = lon,
            provenance = TrackPointSource.REMOTE_STREAM,
            accuracyMeters = acc,
            propsJson = propsJson,
        )
    }
}
