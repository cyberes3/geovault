package com.geovault.tracker.services

import android.content.Context
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.BinaryPayloadBuilder
import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.SyncFailureClass
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.CoroutineContext

enum class QueueUploadScope {
    BACKLOG_ONLY,
    LIVE_ONLY,
    ALL
}

data class QueueUploadConfig(
    val sessionBoundaryId: Long,
    val sessionVisibleBoundaryId: Long,
    val maxBatchesPerPush: Int = 10,
    val batchSize: Int = 50,
    val useExtendedParams: Boolean,
    val sessionStartTimeMs: Long,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val deviceIdentifier: String
)

enum class QueueUploadSkipReason(val telemetryValue: String) {
    LOCK_BUSY("lock-busy"),
    NO_NETWORK("no-network"),
    INVALID_TRACKER("invalid-tracker"),
    MISSING_SERVER_URL("missing-server-url"),
    EMPTY_QUEUE("empty-queue"),
}

enum class QueueUploadFailureReason(val telemetryValue: String) {
    HTTP_PERMANENT("http-permanent"),
    HTTP_TRANSIENT("http-transient"),
    EXCEPTION("exception"),
}

data class QueueUploadResult(
    val failureClass: SyncFailureClass,
    val batchesAttempted: Int = 0,
    val batchesSent: Int = 0,
    val rowsDeleted: Int = 0,
    val visibleRowsSent: Int = 0,
    val interruptedByFailure: Boolean = false,
    val skippedReason: QueueUploadSkipReason? = null,
    val failureReason: QueueUploadFailureReason? = null,
    val httpStatusCode: Int? = null,
    val exceptionClass: String? = null,
) {
    val sentAnyRows: Boolean get() = rowsDeleted > 0
}

internal object QueueUploadOutcomePolicy {
    fun httpFailureClass(code: Int): SyncFailureClass {
        if (code == 408 || code == 429) return SyncFailureClass.TRANSIENT
        return if (code in 400..499) SyncFailureClass.PERMANENT else SyncFailureClass.TRANSIENT
    }

    fun skipped(reason: QueueUploadSkipReason): QueueUploadResult {
        val failureClass = when (reason) {
            QueueUploadSkipReason.LOCK_BUSY -> SyncFailureClass.SKIPPED
            QueueUploadSkipReason.NO_NETWORK -> SyncFailureClass.TRANSIENT
            QueueUploadSkipReason.INVALID_TRACKER,
            QueueUploadSkipReason.MISSING_SERVER_URL -> SyncFailureClass.PERMANENT
            QueueUploadSkipReason.EMPTY_QUEUE -> SyncFailureClass.NONE
        }
        return QueueUploadResult(failureClass = failureClass, skippedReason = reason)
    }

    fun finalResult(
        batchesAttempted: Int,
        batchesSent: Int,
        rowsDeleted: Int,
        visibleRowsSent: Int,
        interruptedByFailure: Boolean,
        failureReason: QueueUploadFailureReason? = null,
        httpStatusCode: Int? = null,
        exceptionClass: String? = null,
    ): QueueUploadResult {
        val failureClass = when {
            interruptedByFailure && failureReason == QueueUploadFailureReason.HTTP_PERMANENT -> SyncFailureClass.PERMANENT
            interruptedByFailure -> SyncFailureClass.TRANSIENT
            batchesSent > 0 -> SyncFailureClass.NONE
            else -> SyncFailureClass.NONE
        }
        return QueueUploadResult(
            failureClass = failureClass,
            batchesAttempted = batchesAttempted,
            batchesSent = batchesSent,
            rowsDeleted = rowsDeleted,
            visibleRowsSent = visibleRowsSent,
            interruptedByFailure = interruptedByFailure,
            failureReason = failureReason,
            httpStatusCode = httpStatusCode,
            exceptionClass = exceptionClass,
        )
    }
}

/**
 * Domain-agnostic in-flight reservation set. Tracks which row ids are currently being uploaded so
 * a second concurrent push cannot claim the same rows. Knows nothing about trackers.
 */
internal class QueueInFlightClaimSet {
    private val mutex = Mutex()
    private val claimedIds = mutableSetOf<Long>()

    /**
     * Claims up to [limit] rows from [candidates] in their provided order, skipping rows that are
     * already claimed by another in-flight push.
     */
    suspend fun claim(candidates: List<QueuedLocation>, limit: Int): List<QueuedLocation> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        return mutex.withLock {
            val batch = ArrayList<QueuedLocation>(limit)
            for (item in candidates) {
                if (item.id in claimedIds) continue
                claimedIds.add(item.id)
                batch.add(item)
                if (batch.size >= limit) break
            }
            batch
        }
    }

    suspend fun release(batch: List<QueuedLocation>) {
        if (batch.isEmpty()) return
        mutex.withLock {
            for (item in batch) {
                claimedIds.remove(item.id)
            }
        }
    }

    suspend fun releaseIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            claimedIds.removeAll(ids)
        }
    }
}

class QueueUploadEngine(
    private val context: Context,
    private val locationDao: LocationDao,
    private val pushContext: CoroutineContext,
    private val authenticatedClientProvider: () -> OkHttpClient
) {
    private val appContext = context.applicationContext
    private val livePushSemaphore = Semaphore(2)
    private val backlogPushSemaphore = Semaphore(1)
    private val inFlightClaims = QueueInFlightClaimSet()

    suspend fun push(
        scope: QueueUploadScope,
        trackerId: String,
        serverUrl: String,
        config: QueueUploadConfig,
        onBatchUploaded: suspend (visibleSentCount: Int) -> Unit
    ): QueueUploadResult = withContext(pushContext) {
        var liveAcquired = false
        var backlogAcquired = false
        val locallyClaimedIds = linkedSetOf<Long>()
        val lockAcquired = when (scope) {
            QueueUploadScope.LIVE_ONLY -> {
                liveAcquired = livePushSemaphore.tryAcquire()
                liveAcquired
            }
            QueueUploadScope.BACKLOG_ONLY -> {
                backlogAcquired = backlogPushSemaphore.tryAcquire()
                backlogAcquired
            }
            QueueUploadScope.ALL -> {
                liveAcquired = livePushSemaphore.tryAcquire()
                if (!liveAcquired) {
                    false
                } else {
                    backlogAcquired = backlogPushSemaphore.tryAcquire()
                    if (!backlogAcquired) {
                        livePushSemaphore.release()
                        liveAcquired = false
                        false
                    } else {
                        true
                    }
                }
            }
        }
        if (!lockAcquired) {
            GeoVaultCaptureLog.d(TAG, "Push already in progress for scope=$scope")
            return@withContext QueueUploadOutcomePolicy.skipped(QueueUploadSkipReason.LOCK_BUSY)
        }

        try {
            if (!NetworkStatusMonitor.hasUsableNetwork(appContext)) {
                return@withContext QueueUploadOutcomePolicy.skipped(QueueUploadSkipReason.NO_NETWORK)
            }
            if (trackerId.isBlank()) {
                return@withContext QueueUploadOutcomePolicy.skipped(QueueUploadSkipReason.INVALID_TRACKER)
            }
            if (serverUrl.isBlank()) {
                return@withContext QueueUploadOutcomePolicy.skipped(QueueUploadSkipReason.MISSING_SERVER_URL)
            }
            val trackerUuid = runCatching { UUID.fromString(trackerId) }.getOrNull()
                ?: return@withContext QueueUploadOutcomePolicy.skipped(QueueUploadSkipReason.INVALID_TRACKER)
            val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
            val ingressUrl = "${baseUrl}api/extensions/live-track/app-ingress/"
            var batchesAttempted = 0
            var batchesSent = 0
            var rowsDeleted = 0
            var visibleRowsSent = 0
            var shouldContinuePush = true
            var interruptedByFailure = false
            var failureReason: QueueUploadFailureReason? = null
            var httpStatusCode: Int? = null
            var exceptionClass: String? = null
            while (batchesSent < config.maxBatchesPerPush && shouldContinuePush) {
                val batch = claimNextBatch(
                    scope = scope,
                    trackerId = trackerId,
                    sessionBoundaryId = config.sessionBoundaryId,
                    limit = config.batchSize,
                )
                if (batch.isEmpty()) break
                batchesAttempted++
                locallyClaimedIds.addAll(batch.map { it.id })
                val payload = BinaryPayloadBuilder.build(
                    locations = batch,
                    header = BinaryPayloadBuilder.Header(
                        trackerUuid = trackerUuid,
                        sessionStartMs = config.sessionStartTimeMs,
                        hasExtended = config.useExtendedParams,
                        buildSerial = config.deviceIdentifier,
                    ),
                    batteryLevel = config.batteryLevel,
                    isCharging = config.isCharging,
                )
                val requestBody = gzipCompress(payload).toRequestBody("application/octet-stream".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(ingressUrl)
                    .addHeader("Content-Encoding", "gzip")
                    .post(requestBody)
                    .build()
                try {
                    authenticatedClientProvider().newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            withContext(NonCancellable) {
                                try {
                                    locationDao.delete(batch)
                                    rowsDeleted += batch.size
                                } finally {
                                    releaseClaimedBatch(batch)
                                    locallyClaimedIds.removeAll(batch.map { it.id }.toSet())
                                }
                            }
                            val visibleSentCount = visibleSentCountForBatchIds(batch.map { it.id }, config.sessionVisibleBoundaryId)
                            visibleRowsSent += visibleSentCount
                            onBatchUploaded(visibleSentCount)
                            batchesSent++
                        } else {
                            releaseClaimedBatch(batch)
                            locallyClaimedIds.removeAll(batch.map { it.id }.toSet())
                            val failureClass = QueueUploadOutcomePolicy.httpFailureClass(response.code)
                            httpStatusCode = response.code
                            failureReason = if (failureClass == SyncFailureClass.PERMANENT) {
                                QueueUploadFailureReason.HTTP_PERMANENT
                            } else {
                                QueueUploadFailureReason.HTTP_TRANSIENT
                            }
                            if (failureClass == SyncFailureClass.PERMANENT) {
                                return@withContext QueueUploadOutcomePolicy.finalResult(
                                    batchesAttempted = batchesAttempted,
                                    batchesSent = batchesSent,
                                    rowsDeleted = rowsDeleted,
                                    visibleRowsSent = visibleRowsSent,
                                    interruptedByFailure = true,
                                    failureReason = failureReason,
                                    httpStatusCode = httpStatusCode,
                                )
                            }
                            interruptedByFailure = true
                            shouldContinuePush = false
                        }
                    }
                } catch (e: Exception) {
                    GeoVaultCaptureLog.e(TAG, "Exception pushing locations", e)
                    releaseClaimedBatch(batch)
                    locallyClaimedIds.removeAll(batch.map { it.id }.toSet())
                    interruptedByFailure = true
                    failureReason = QueueUploadFailureReason.EXCEPTION
                    exceptionClass = e::class.java.simpleName
                    shouldContinuePush = false
                }
            }
            return@withContext QueueUploadOutcomePolicy.finalResult(
                batchesAttempted = batchesAttempted,
                batchesSent = batchesSent,
                rowsDeleted = rowsDeleted,
                visibleRowsSent = visibleRowsSent,
                interruptedByFailure = interruptedByFailure,
                failureReason = failureReason,
                httpStatusCode = httpStatusCode,
                exceptionClass = exceptionClass,
            )
        } finally {
            if (locallyClaimedIds.isNotEmpty()) {
                inFlightClaims.releaseIds(locallyClaimedIds.toSet())
            }
            if (backlogAcquired) backlogPushSemaphore.release()
            if (liveAcquired) livePushSemaphore.release()
        }
    }

    suspend fun claimNextBatch(
        scope: QueueUploadScope,
        trackerId: String,
        sessionBoundaryId: Long,
        limit: Int,
    ): List<QueuedLocation> {
        val probe = limit * 3
        val candidates = when (scope) {
            QueueUploadScope.BACKLOG_ONLY ->
                locationDao.getOldestBacklogForTracker(trackerId, sessionBoundaryId, probe)
            QueueUploadScope.LIVE_ONLY ->
                locationDao.getOldestCurrentSessionForTracker(trackerId, sessionBoundaryId, probe)
            QueueUploadScope.ALL ->
                locationDao.getOldestForTracker(trackerId, probe)
        }
        if (candidates.isEmpty()) return emptyList()
        return inFlightClaims.claim(candidates, limit)
    }

    private suspend fun releaseClaimedBatch(batch: List<QueuedLocation>) {
        inFlightClaims.release(batch)
    }

    private fun visibleSentCountForBatchIds(batchIds: List<Long>, sessionBoundaryId: Long): Int {
        if (batchIds.isEmpty()) return 0
        return batchIds.count { it > sessionBoundaryId }
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(data)
        }
        return output.toByteArray()
    }

    companion object {
        private const val TAG = "QueueUploadEngine"
    }
}
