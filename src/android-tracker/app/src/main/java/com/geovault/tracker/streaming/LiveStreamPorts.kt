package com.geovault.tracker.streaming

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.service.GeoVaultForegroundStartGate
import com.geovault.tracker.LiveTrackStreamingService
import com.geovault.tracker.policy.StreamingTargetPolicy

internal sealed class LiveStreamApplyResult {
    data class Started(val trackerIds: Set<String>) : LiveStreamApplyResult()
    data class Failed(val reason: String) : LiveStreamApplyResult()
}

internal sealed class LiveStreamStopResult {
    data object Stopped : LiveStreamStopResult()
    data class Failed(val reason: String) : LiveStreamStopResult()
}

enum class LiveStreamCommand {
    Apply,
    Stop,
    Reshow,
}

interface LiveStreamPersistPort {
    fun commit(trackerIds: Set<String>, trackerName: String?)
    fun read(): Pair<Set<String>, String?>
    fun clear()
}

internal interface LiveStreamHostPort {
    fun apply(context: Context): LiveStreamApplyResult
    fun stop(context: Context): LiveStreamStopResult
    fun reshow(context: Context)
    fun cancelRetry()
}

class SharedPrefsLiveStreamPersistPort(
    context: Context,
) : LiveStreamPersistPort {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun commit(trackerIds: Set<String>, trackerName: String?) {
        val ids = StreamingTargetPolicy.normalizeTrackerIds(trackerIds)
        if (ids.isEmpty()) {
            clear()
            return
        }
        prefs.edit()
            .putStringSet(KEY_TRACKER_IDS, ids)
            .putString(KEY_TRACKER_NAME, trackerName?.trim()?.ifBlank { null })
            .apply()
    }

    override fun read(): Pair<Set<String>, String?> {
        val rawIds = prefs.getStringSet(KEY_TRACKER_IDS, emptySet()).orEmpty()
        val ids = StreamingTargetPolicy.normalizeTrackerIds(rawIds)
        val name = prefs.getString(KEY_TRACKER_NAME, null)?.trim()?.ifBlank { null }
        if (ids != rawIds.toSet()) {
            if (ids.isEmpty()) clear() else commit(ids, name)
        }
        return ids to name
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "live_track_streaming_targets"
        const val KEY_TRACKER_IDS = "tracker_ids"
        const val KEY_TRACKER_NAME = "tracker_name"
    }
}

internal class DefaultLiveStreamHostPort(
    context: Context,
    private val persist: LiveStreamPersistPort,
) : LiveStreamHostPort {
    private val appContext = context.applicationContext
    private val startGate = GeoVaultForegroundStartGate(
        context = appContext,
        config = GeoVaultForegroundStartGate.Config(
            prefsName = "live_stream_start_gate",
            retryRequestCode = RETRY_REQUEST_CODE,
            tag = TAG,
        ),
        startIntentFactory = { commandIntent(LiveStreamCommand.Apply) },
    )

    override fun apply(context: Context): LiveStreamApplyResult {
        val decision = startGate.dispatchStart("apply")
        return if (decision.allowed) {
            LiveStreamApplyResult.Started(emptySet())
        } else {
            LiveStreamApplyResult.Failed(decision.reason)
        }
    }

    override fun stop(context: Context): LiveStreamStopResult {
        startGate.cancelRetry()
        persist.clear()
        return try {
            appContext.startService(commandIntent(LiveStreamCommand.Stop))
            LiveStreamStopResult.Stopped
        } catch (e: IllegalStateException) {
            runCatching { ContextCompat.startForegroundService(appContext, commandIntent(LiveStreamCommand.Stop)) }
                .fold(
                    onSuccess = { LiveStreamStopResult.Stopped },
                    onFailure = { inner ->
                        GeoVaultCaptureLog.e(TAG, "FGS stop command failed", inner)
                        LiveStreamStopResult.Failed(inner.message ?: "Unable to stop live streaming service")
                    },
                )
        }
    }

    override fun reshow(context: Context) {
        runCatching { ContextCompat.startForegroundService(appContext, commandIntent(LiveStreamCommand.Reshow)) }
    }

    override fun cancelRetry() {
        startGate.cancelRetry()
    }

    private fun commandIntent(command: LiveStreamCommand): Intent {
        return Intent(appContext, LiveTrackStreamingService::class.java).apply {
            action = command.action()
        }
    }

    companion object {
        private const val TAG = "LiveStreamHostPort"
        private const val RETRY_REQUEST_CODE = 7102
    }
}

fun LiveStreamCommand.action(): String = when (this) {
    LiveStreamCommand.Apply -> LiveTrackStreamingService.ACTION_START
    LiveStreamCommand.Stop -> LiveTrackStreamingService.ACTION_STOP
    LiveStreamCommand.Reshow -> LiveTrackStreamingService.ACTION_RESHOW_FOREGROUND
}
