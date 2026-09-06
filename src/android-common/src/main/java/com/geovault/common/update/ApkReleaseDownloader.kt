package com.geovault.common.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ApkDownloadProgress(
    val bytesReceived: Long,
    val totalBytes: Long?,
    val smoothedBytesPerSecond: Long,
)

class ApkReleaseDownloader(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun download(
        url: String,
        knownTotalBytes: Long?,
        destination: File,
        onProgress: (ApkDownloadProgress) -> Unit,
    ): Result<Unit> {
        val httpsUrl = ApkDownloadUrlPolicy.requireHttps(url).getOrElse { return Result.failure(it) }
        destination.parentFile?.mkdirs()
        if (destination.exists()) {
            destination.delete()
        }
        val request = Request.Builder()
            .url(httpsUrl)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
            .get()
            .build()
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("HTTP ${response.code}"),
                        )
                    }
                    val body = response.body
                    val headerLength = body.contentLength()
                    val totalBytes = when {
                        knownTotalBytes != null && knownTotalBytes > 0L -> knownTotalBytes
                        headerLength > 0L -> headerLength
                        else -> null
                    }
                    var received = 0L
                    var lastEmit = System.nanoTime()
                    var lastReceivedAtEmit = 0L
                    var smoothedBps: Long = 0L
                    val alpha = 0.22
                    try {
                        body.byteStream().use { input ->
                            FileOutputStream(destination).use { output ->
                                val buf = ByteArray(8192)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val r = input.read(buf)
                                    if (r == -1) break
                                    if (r > 0) {
                                        output.write(buf, 0, r)
                                        received += r.toLong()
                                    }
                                    val now = System.nanoTime()
                                    val elapsedSec = (now - lastEmit) / 1_000_000_000.0
                                    val shouldEmit = elapsedSec >= PROGRESS_EMIT_INTERVAL_SEC ||
                                        (totalBytes != null && received >= totalBytes)
                                    if (shouldEmit && received > 0L) {
                                        val deltaBytes = received - lastReceivedAtEmit
                                        val instantBps = if (elapsedSec > 0.001) {
                                            (deltaBytes / elapsedSec).toLong().coerceAtLeast(0L)
                                        } else {
                                            0L
                                        }
                                        smoothedBps = UpdateDownloadProgressMath.exponentialMovingAverageBps(
                                            smoothedBps.takeIf { it > 0L },
                                            instantBps,
                                            alpha,
                                        )
                                        onProgress(
                                            ApkDownloadProgress(
                                                bytesReceived = received,
                                                totalBytes = totalBytes,
                                                smoothedBytesPerSecond = smoothedBps,
                                            ),
                                        )
                                        lastEmit = now
                                        lastReceivedAtEmit = received
                                    }
                                }
                            }
                        }
                        onProgress(
                            ApkDownloadProgress(
                                bytesReceived = received,
                                totalBytes = totalBytes,
                                smoothedBytesPerSecond = smoothedBps,
                            ),
                        )
                        if (knownTotalBytes != null && knownTotalBytes > 0L && received != knownTotalBytes) {
                            if (destination.exists()) {
                                destination.delete()
                            }
                            return@withContext Result.failure(IllegalStateException("apk_size_mismatch"))
                        }
                        Result.success(Unit)
                    } catch (t: Throwable) {
                        if (destination.exists()) {
                            destination.delete()
                        }
                        Result.failure(t)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (destination.exists()) {
                destination.delete()
            }
            throw e
        } catch (e: Exception) {
            if (destination.exists()) {
                destination.delete()
            }
            Result.failure(e)
        }
    }

    companion object {
        private const val PROGRESS_EMIT_INTERVAL_SEC = 0.125

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
