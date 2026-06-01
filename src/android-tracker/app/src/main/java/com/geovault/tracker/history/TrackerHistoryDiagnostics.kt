package com.geovault.tracker.history

import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.db.QueuedLocation

/**
 * Shared log lines for the history → render pipeline. All messages use the `map_update` /
 * `map_draw` prefixes so field testing can filter logcat with:
 * `adb logcat | grep -E 'map_update history_|map_draw_'`
 */
object TrackerHistoryDiagnostics {
    private const val TAG = "TrackerHistory"

    fun snapshotLine(snapshot: TrackerHistorySnapshot?): String {
        if (snapshot == null) return "snapshot=none"
        return "snapshot(trunk=${snapshot.trunk.size} overlay=${snapshot.overlay.size} " +
            "pts=${snapshot.points.size} complete=${snapshot.complete} degraded=${snapshot.degradedLocalOnly} " +
            "gen=${snapshot.generation} ${timeRange(snapshot.points)})"
    }

    fun batchLine(batch: TrackerHistorySourceBatch): String {
        return "batch(kind=${batch.sourceKind} window=${batch.window.normalizedKey} " +
            "pts=${batch.points.size} complete=${batch.complete} degraded=${batch.degradedLocalOnly} " +
            "${timeRange(batch.points)})"
    }

    fun timeRange(points: List<TrackerHistoryPoint>): String {
        if (points.isEmpty()) return "time=empty"
        return "time=${points.first().timestampMs}..${points.last().timestampMs}"
    }

    fun queuedTimeRange(points: List<QueuedLocation>): String {
        if (points.isEmpty()) return "time=empty"
        return "time=${points.first().time}..${points.last().time}"
    }

    fun mapSizes(trails: Map<String, List<QueuedLocation>>): String {
        if (trails.isEmpty()) return "{}"
        return trails.entries.joinToString(prefix = "{", postfix = "}") { (id, pts) ->
            "$id=${pts.size}"
        }
    }

    fun logIntent(
        intent: String,
        batch: TrackerHistorySourceBatch? = null,
        boundary: TrackerHistoryClearBoundary? = null,
        window: TrackerHistoryWindow? = null,
    ) {
        val detail = when {
            batch != null -> batchLine(batch)
            boundary != null -> "clear(tracker=${boundary.trackerId.trim()} at=${boundary.clearedAtMs} " +
                "session=${boundary.activeSessionStartMs ?: -1} window=${window?.normalizedKey ?: "?"})"
            else -> ""
        }
        GeoVaultCaptureLog.i(TAG, "map_update history_intent intent=$intent $detail")
    }

    fun logTransaction(
        intent: String,
        result: TrackerHistoryTransactionResult,
        batch: TrackerHistorySourceBatch? = null,
    ) {
        val level = when {
            !result.committed && result.reason == "empty_snapshot_deferred" -> LogLevel.WARN
            !result.committed -> LogLevel.DEBUG
            batch?.degradedLocalOnly == true -> LogLevel.WARN
            else -> LogLevel.INFO
        }
        val batchDetail = batch?.let { batchLine(it) }.orEmpty()
        val message = "map_update history_tx intent=$intent committed=${result.committed} " +
            "reason=${result.reason} ${snapshotLine(result.snapshot)} $batchDetail"
        when (level) {
            LogLevel.WARN -> GeoVaultCaptureLog.w(TAG, message)
            LogLevel.DEBUG -> GeoVaultCaptureLog.d(TAG, message)
            LogLevel.INFO -> GeoVaultCaptureLog.i(TAG, message)
        }
    }

    fun logComposeDeferred(
        trackerId: String,
        window: String,
        previousPoints: Int,
    ) {
        GeoVaultCaptureLog.w(
            TAG,
            "map_update history_compose_defer tracker=$trackerId window=$window " +
                "keeping_previous_pts=$previousPoints action=empty_snapshot_deferred",
        )
    }

    fun logRefreshDecision(
        cause: TrackerHistoryRefreshCause,
        shouldRefresh: Boolean,
        policyReason: String,
        lastTrunkFetchedAtMs: Long?,
        nowMs: Long,
    ) {
        val ageMs = lastTrunkFetchedAtMs?.let { nowMs - it }
        GeoVaultCaptureLog.i(
            TAG,
            "map_update history_refresh cause=$cause shouldRefresh=$shouldRefresh " +
                "policy=$policyReason trunk_age_ms=${ageMs ?: "never"}",
        )
    }

    fun logDrawApply(
        mode: String,
        displayedTrackerId: String,
        trails: TrailsDrawSummary,
        skipClientWindowFilter: Set<String>,
        throttleKey: String = "history_draw_apply",
    ) {
        val signature =
            "mode=$mode|displayed=$displayedTrackerId|single=${trails.singleCount}|" +
                "multi=${trails.multiSizes}|skip=${skipClientWindowFilter.sorted()}|" +
                "degraded=${trails.degradedTrackerIds.sorted()}|truncated=${trails.incompleteTrackerIds.sorted()}"
        if (!CaptureLogThrottle.shouldLogOnChange(throttleKey, signature)) return
        GeoVaultCaptureLog.i(
            TAG,
            "map_draw_apply mode=$mode displayed=$displayedTrackerId " +
                "single_pts=${trails.singleCount} ${trails.singleTime} " +
                "multi=${trails.multiSizes} skip_client_window_filter=${skipClientWindowFilter.sorted()} " +
                "incomplete_trunks=${trails.incompleteTrackerIds.sorted()} " +
                "degraded_trunks=${trails.degradedTrackerIds.sorted()}",
        )
    }

    fun logRenderDecimation(
        trackerId: String,
        window: String,
        rawCount: Int,
        renderedCount: Int,
    ) {
        if (rawCount == renderedCount) return
        GeoVaultCaptureLog.d(
            TAG,
            "map_draw_decimate tracker=$trackerId window=$window raw=$rawCount rendered=$renderedCount",
        )
    }

    fun logOverlayCommitThrottled(
        sourceKind: TrackerHistorySourceKind,
        trackerId: String,
        window: String,
        pointCount: Int,
        committed: Boolean,
        reason: String,
    ) {
        val signature = "$sourceKind|$trackerId|$window|$pointCount|$committed|$reason"
        if (!CaptureLogThrottle.shouldLogOnChange("history_overlay_$sourceKind", signature)) return
        GeoVaultCaptureLog.d(
            TAG,
            "map_update history_overlay kind=$sourceKind tracker=$trackerId window=$window " +
                "pts=$pointCount committed=$committed reason=$reason",
        )
    }

    fun logRuntimeHead(
        trackerId: String,
        action: String,
        runtimeTs: Long,
    ) {
        if (!CaptureLogThrottle.shouldLogOnChange("history_runtime_head", "$trackerId|$action|$runtimeTs")) return
        GeoVaultCaptureLog.d(
            TAG,
            "map_update history_runtime_head tracker=$trackerId action=$action runtime_ts=$runtimeTs",
        )
    }

    fun logSessionDrawFilter(
        trackerId: String,
        windowKey: String?,
        skipped: Boolean,
        rawCount: Int,
        filteredCount: Int,
    ) {
        if (!skipped && rawCount == filteredCount) return
        val signature = "$trackerId|$windowKey|$skipped|$rawCount|$filteredCount"
        if (!CaptureLogThrottle.shouldLogOnChange("map_draw_window_filter", signature)) return
        GeoVaultCaptureLog.d(
            TAG,
            "map_draw_filter tracker=$trackerId window=$windowKey skipped=$skipped " +
                "raw=$rawCount filtered=$filteredCount",
        )
    }

    data class TrailsDrawSummary(
        val singleCount: Int,
        val singleTime: String,
        val multiSizes: String,
        val incompleteTrackerIds: Set<String>,
        val degradedTrackerIds: Set<String>,
    )

    private enum class LogLevel {
        INFO,
        WARN,
        DEBUG,
    }
}
