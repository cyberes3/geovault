package com.geovault.tracker.services

import android.content.Context
import android.util.Log
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

internal object QueueUploadOutcomePolicy {
    fun httpFailureClass(code: Int): SyncFailureClass {
        if (code == 408 || code == 429) return SyncFailureClass.TRANSIENT
        return if (code in 400..499) SyncFailureClass.PERMANENT else SyncFailureClass.TRANSIENT
    }

    fun finalOutcome(batchesSent: Int, interruptedByFailure: Boolean): SyncFailureClass {
        return when {
            interruptedByFailure -> SyncFailureClass.TRANSIENT
            batchesSent > 0 -> SyncFailureClass.NONE
            else -> SyncFailureClass.TRANSIENT
        }
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
    ): SyncFailureClass = withContext(pushContext) {
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
            Log.d(TAG, "Push already in progress for scope=$scope")
            return@withContext SyncFailureClass.SKIPPED
        }

        try {
            if (!NetworkStatusMonitor.hasUsableNetwork(appContext)) {
                return@withContext SyncFailureClass.TRANSIENT
            }
            if (trackerId.isBlank() || serverUrl.isBlank()) {
                return@withContext SyncFailureClass.PERMANENT
            }
            val trackerUuid = runCatching { UUID.fromString(trackerId) }.getOrNull()
                ?: return@withContext SyncFailureClass.PERMANENT
            val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
            val ingressUrl = "${baseUrl}api/extensions/live-track/app-ingress/"
            var batchesSent = 0
            var shouldContinuePush = true
            var interruptedByFailure = false
            while (batchesSent < config.maxBatchesPerPush && shouldContinuePush) {
                val batch = claimNextBatch(
                    scope = scope,
                    trackerId = trackerId,
                    sessionBoundaryId = config.sessionBoundaryId,
                    limit = config.batchSize,
                )
                if (batch.isEmpty()) break
                locallyClaimedIds.addAll(batch.map { it.id })
                val payload = if (config.useExtendedParams) {
                    BinaryPayloadBuilder.buildPayload(
                        locations = batch,
                        trackerId = trackerUuid,
                        sessionStartTimeMs = config.sessionStartTimeMs,
                        batteryLevel = config.batteryLevel,
                        isCharging = config.isCharging,
                        buildSerial = config.deviceIdentifier
                    )
                } else {
                    BinaryPayloadBuilder.buildPayloadMinimal(
                        locations = batch,
                        trackerId = trackerUuid
                    )
                }
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
                                } finally {
                                    releaseClaimedBatch(batch)
                                    locallyClaimedIds.removeAll(batch.map { it.id }.toSet())
                                }
                            }
                            onBatchUploaded(visibleSentCountForBatchIds(batch.map { it.id }, config.sessionVisibleBoundaryId))
                            batchesSent++
                        } else {
                            releaseClaimedBatch(batch)
                            locallyClaimedIds.removeAll(batch.map { it.id }.toSet())
                            val failureClass = QueueUploadOutcomePolicy.httpFailureClass(response.code)
                            if (failureClass == SyncFailureClass.PERMANENT) {
                                return@withContext SyncFailureClass.PERMANENT
                            }
                            interruptedByFailure = true
                            shouldContinuePush = false
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception pushing locations", e)
                    releaseClaimedBatch(batch)
                    locallyClaimedIds.removeAll(batch.map { it.id }.toSet())
                    interruptedByFailure = true
                    shouldContinuePush = false
                }
            }
            return@withContext QueueUploadOutcomePolicy.finalOutcome(
                batchesSent = batchesSent,
                interruptedByFailure = interruptedByFailure
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
