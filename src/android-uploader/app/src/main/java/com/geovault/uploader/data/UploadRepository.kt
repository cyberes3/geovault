package com.geovault.uploader.data

import android.content.ContentResolver
import android.net.Uri
import com.geovault.common.auth.AuthSessionService
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.auth.ServerConfigService
import com.geovault.common.files.GeoVaultContentUriRequestBody
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.net.GeoVaultServerUrl
import com.geovault.uploader.domain.ImportFileUploader
import com.geovault.uploader.domain.ImportUploadOutcome
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class UploadRepository(
    private val contentResolver: ContentResolver,
    private val serverConfigService: ServerConfigService,
    private val authSessionService: AuthSessionService,
    private val httpClient: OkHttpClient = defaultClient(),
) : ImportFileUploader {
    private val callLock = Any()
    private var active: ActiveUpload? = null

    override fun cancelActiveUpload(generation: Long) {
        synchronized(callLock) {
            val current = active ?: return
            if (current.generation != generation) return
            current.call.cancel()
            active = null
        }
    }

    fun cancelAllUploads() {
        synchronized(callLock) {
            active?.call?.cancel()
            active = null
        }
    }

    override suspend fun upload(
        uri: Uri,
        finalFilename: String,
        generation: Long,
    ): ImportUploadOutcome = withContext(Dispatchers.IO) {
        val serverUrl = GeoVaultServerUrl.parse(serverConfigService.getNormalizedServerUrl())
            ?: return@withContext ImportUploadOutcome.Failed(
                GeoVaultApiFailure(
                    httpCode = null,
                    serverMessage = "Missing server URL",
                    operation = "importUpload",
                ),
            )
        if (!authSessionService.isLoggedIn()) {
            return@withContext ImportUploadOutcome.Failed(
                GeoVaultApiFailure(
                    httpCode = null,
                    serverMessage = "Not signed in",
                    operation = "importUpload",
                ),
            )
        }

        val fileBody = GeoVaultContentUriRequestBody(
            contentResolver = contentResolver,
            uri = uri,
            contentType = "application/octet-stream".toMediaType(),
        )
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", finalFilename, fileBody)
            .build()
        val request = Request.Builder()
            .url(serverUrl.resolve("/api/item/import/upload"))
            .post(requestBody)
            .build()

        try {
            suspendCancellableCoroutine<ImportUploadOutcome> { continuation ->
                val call = httpClient.newCall(request)
                synchronized(callLock) {
                    active = ActiveUpload(generation, call)
                }
                continuation.invokeOnCancellation {
                    call.cancel()
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        clearIfOwner(generation, call)
                        if (!continuation.isActive) return
                        val outcome = if (call.isCanceled()) {
                            ImportUploadOutcome.Cancelled
                        } else {
                            ImportUploadOutcome.Failed(
                                GeoVaultApiFailure.fromThrowable(e, "importUpload"),
                            )
                        }
                        continuation.resume(outcome)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        clearIfOwner(generation, call)
                        if (!continuation.isActive) return
                        response.use {
                            if (call.isCanceled()) {
                                continuation.resume(ImportUploadOutcome.Cancelled)
                                return
                            }
                            if (it.isSuccessful) {
                                continuation.resume(ImportUploadOutcome.Success)
                                return
                            }
                            val payload = try {
                                it.body.string()
                            } catch (_: Exception) {
                                ""
                            }
                            continuation.resume(
                                ImportUploadOutcome.Failed(
                                    GeoVaultApiFailure.fromOkHttp(it, "importUpload", payload),
                                ),
                            )
                        }
                    }
                })
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ImportUploadOutcome.Failed(GeoVaultApiFailure.fromThrowable(e, "importUpload"))
        } finally {
            synchronized(callLock) {
                if (active?.generation == generation) {
                    active = null
                }
            }
        }
    }

    private fun clearIfOwner(generation: Long, call: Call) {
        synchronized(callLock) {
            val current = active ?: return
            if (current.generation == generation && current.call === call) {
                active = null
            }
        }
    }

    private data class ActiveUpload(
        val generation: Long,
        val call: Call,
    )

    companion object {
        fun defaultClient(): OkHttpClient {
            return GeoVaultHttp.authenticatedClient()
                .newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun fromSession(
            contentResolver: ContentResolver,
            session: GeoVaultAuthSession = GeoVaultAuthSession.get(),
        ): UploadRepository {
            return UploadRepository(
                contentResolver = contentResolver,
                serverConfigService = session,
                authSessionService = session,
            )
        }
    }
}
