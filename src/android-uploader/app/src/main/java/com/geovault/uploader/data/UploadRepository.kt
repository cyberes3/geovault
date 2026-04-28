package com.geovault.uploader.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.geovault.common.RetrofitClient
import com.geovault.common.auth.AuthSessionService
import com.geovault.common.auth.GeovaultAuthServices
import com.geovault.common.auth.ServerConfigService
import com.geovault.uploader.model.UploadResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class UploadRepository(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val serverConfigService: ServerConfigService = GeovaultAuthServices(context),
    private val authSessionService: AuthSessionService = GeovaultAuthServices(context)
) {
    private val callLock = Any()
    private var activeCall: Call? = null

    fun cancelActiveUpload() {
        synchronized(callLock) {
            activeCall?.cancel()
            activeCall = null
        }
    }

    suspend fun upload(uri: Uri, finalFilename: String): UploadResult {
        val serverUrl = serverConfigService.getNormalizedServerUrl()
        if (serverUrl.isBlank()) return UploadResult(false, errorMessage = "Missing server URL")
        if (!authSessionService.isLoggedIn()) return UploadResult(false, errorMessage = "Not signed in")

        var tmpFile: File? = null
        return try {
            tmpFile = withContext(Dispatchers.IO) {
                val file = File.createTempFile("geovault-upload-", ".tmp", context.cacheDir)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: error("Could not read file")
                file
            }
            val uploadFile = tmpFile ?: return UploadResult(false, errorMessage = "Could not read file")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    finalFilename,
                    uploadFile.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("$serverUrl/api/item/import/upload")
                .post(requestBody)
                .build()

            val client = RetrofitClient.getAuthenticatedOkHttpClient(context)
                .newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                synchronized(callLock) {
                    activeCall = call
                }
                continuation.invokeOnCancellation {
                    call.cancel()
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        synchronized(callLock) {
                            if (activeCall === call) activeCall = null
                        }
                        val message = if (call.isCanceled()) {
                            "Upload cancelled"
                        } else {
                            "Connection failed\n${e.message ?: "Unknown error"}"
                        }
                        if (continuation.isActive) {
                            continuation.resume(UploadResult(success = false, errorMessage = message))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        synchronized(callLock) {
                            if (activeCall === call) activeCall = null
                        }
                        response.use {
                            if (it.isSuccessful) {
                                if (continuation.isActive) {
                                    continuation.resume(UploadResult(success = true, statusCode = it.code))
                                }
                                return
                            }
                            if (it.code == 401) {
                                authSessionService.handleAuthFailure()
                            }
                            val payload = try { it.body.string() } catch (_: Exception) { "" }
                            val serverMessage = try {
                                if (payload.trimStart().startsWith("{")) {
                                    JSONObject(payload).optString("error", JSONObject(payload).optString("message", ""))
                                } else {
                                    ""
                                }
                            } catch (_: Exception) {
                                ""
                            }
                            if (continuation.isActive) {
                                continuation.resume(
                                    UploadResult(
                                        success = false,
                                        statusCode = it.code,
                                        errorMessage = UploadErrorMessageFormatter.fromStatusCode(it.code, serverMessage)
                                    )
                                )
                            }
                        }
                    }
                })
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            UploadResult(success = false, errorMessage = "Connection failed\n${e.message ?: "Unknown error"}")
        } finally {
            synchronized(callLock) {
                activeCall = null
            }
            withContext(Dispatchers.IO) {
                tmpFile?.takeIf { it.exists() }?.delete()
            }
        }
    }
}
