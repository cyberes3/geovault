package com.geovault.uploader.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.geovault.common.messages.GeoVaultUploadMessageFormatter
import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.auth.AuthSessionService
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.auth.ServerConfigService
import com.geovault.uploader.domain.ImportFileUploader
import com.geovault.uploader.model.ImportUploadOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONObject

class UploadRepository(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val authSession: GeoVaultAuthSession = GeoVaultAuthSession.get(),
    private val serverConfigService: ServerConfigService = authSession,
    private val authSessionService: AuthSessionService = authSession,
) : ImportFileUploader {
    private val callLock = Any()
    private var activeCall: Call? = null

    private val httpClient: OkHttpClient by lazy {
        GeoVaultHttp.authenticatedClient()
            .newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun cancelActiveUpload() {
        synchronized(callLock) {
            activeCall?.cancel()
            activeCall = null
        }
    }

    override suspend fun warmAccessToken(): ImportUploadOutcome? = withContext(Dispatchers.IO) {
        if (!authSessionService.isLoggedIn()) {
            return@withContext ImportUploadOutcome.Failed("Not signed in")
        }
        try {
            authSession.getValidAccessToken()
            null
        } catch (e: Exception) {
            ImportUploadOutcome.Failed("Connection failed\n${e.message ?: "Unknown error"}")
        }
    }

    override suspend fun upload(uri: Uri, finalFilename: String): ImportUploadOutcome {
        val serverUrl = serverConfigService.getNormalizedServerUrl()
        if (serverUrl.isBlank()) return ImportUploadOutcome.Failed("Missing server URL")
        if (!authSessionService.isLoggedIn()) return ImportUploadOutcome.Failed("Not signed in")

        val fileBody = UriRequestBody(
            contentResolver = contentResolver,
            uri = uri,
            contentType = "application/octet-stream".toMediaType(),
        )
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", finalFilename, fileBody)
            .build()
        val request = Request.Builder()
            .url("$serverUrl/api/item/import/upload")
            .post(requestBody)
            .build()

        return try {
            suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(request)
                synchronized(callLock) {
                    activeCall = call
                }
                continuation.invokeOnCancellation {
                    call.cancel()
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        synchronized(callLock) {
                            if (activeCall === call) activeCall = null
                        }
                        if (!continuation.isActive) return
                        val outcome = if (call.isCanceled()) {
                            ImportUploadOutcome.Cancelled
                        } else {
                            ImportUploadOutcome.Failed(
                                "Connection failed\n${e.message ?: "Unknown error"}"
                            )
                        }
                        continuation.resume(outcome)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        synchronized(callLock) {
                            if (activeCall === call) activeCall = null
                        }
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
                            if (it.code == 401) {
                                authSessionService.handleAuthFailure()
                            }
                            val payload = try {
                                it.body.string()
                            } catch (_: Exception) {
                                ""
                            }
                            val serverMessage = try {
                                if (payload.trimStart().startsWith("{")) {
                                    JSONObject(payload).optString(
                                        "error",
                                        JSONObject(payload).optString("message", "")
                                    )
                                } else {
                                    ""
                                }
                            } catch (_: Exception) {
                                ""
                            }
                            continuation.resume(
                                ImportUploadOutcome.Failed(
                                    GeoVaultUploadMessageFormatter.fromStatusCode(it.code, serverMessage)
                                )
                            )
                        }
                    }
                })
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ImportUploadOutcome.Failed("Connection failed\n${e.message ?: "Unknown error"}")
        } finally {
            synchronized(callLock) {
                activeCall = null
            }
        }
    }

    private class UriRequestBody(
        private val contentResolver: ContentResolver,
        private val uri: Uri,
        private val contentType: MediaType?,
    ) : RequestBody() {
        override fun contentType(): MediaType? = contentType

        override fun contentLength(): Long {
            return try {
                contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.statSize.takeIf { it >= 0L }
                } ?: -1L
            } catch (_: Exception) {
                -1L
            }
        }

        override fun writeTo(sink: BufferedSink) {
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Could not read file")
            input.use { stream ->
                sink.writeAll(stream.source())
            }
        }
    }
}
