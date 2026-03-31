package com.geovault.uploader.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.uploader.model.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class UploadRepository(
    private val context: Context,
    private val contentResolver: ContentResolver
) {
    suspend fun upload(uri: Uri, finalFilename: String): UploadResult = withContext(Dispatchers.IO) {
        val serverUrl = GeovaultAuthManager.normalizeServerUrl(GeovaultAuthManager.getServerUrl(context))
        if (serverUrl.isBlank()) return@withContext UploadResult(false, errorMessage = "Missing server URL")
        if (!GeovaultAuthManager.isLoggedIn(context)) return@withContext UploadResult(false, errorMessage = "Not signed in")

        val tmpFile = File(context.cacheDir, finalFilename)
        return@withContext try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
            } ?: return@withContext UploadResult(false, errorMessage = "Could not read file")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    finalFilename,
                    tmpFile.asRequestBody("application/octet-stream".toMediaType())
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

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    UploadResult(success = true, statusCode = response.code)
                } else {
                    if (response.code == 401) {
                        GeovaultAuthManager.handleAuthFailure(context)
                    }
                    val payload = try { response.body.string() } catch (_: Exception) { "" }
                    val serverMessage = try {
                        if (payload.trimStart().startsWith("{")) {
                            JSONObject(payload).optString("error", JSONObject(payload).optString("message", ""))
                        } else {
                            ""
                        }
                    } catch (_: Exception) {
                        ""
                    }
                    UploadResult(
                        success = false,
                        statusCode = response.code,
                        errorMessage = buildErrorMessage(response.code, serverMessage)
                    )
                }
            }
        } catch (e: Exception) {
            UploadResult(success = false, errorMessage = "Connection failed\n${e.message ?: "Unknown error"}")
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    private fun buildErrorMessage(statusCode: Int, serverMessage: String): String {
        val base = when (statusCode) {
            400 -> "Upload failed (400)\nInvalid request. Check your file format."
            401 -> "Upload failed (401)\nAPI key is invalid or expired.\nCheck Settings."
            403 -> "Upload failed (403)\nAccess denied. Check API key permissions."
            404 -> "Upload failed (404)\nServer endpoint not found.\nCheck your server URL in Settings."
            500 -> "Upload failed (500)\nServer error. Try again later."
            else -> "Upload failed ($statusCode)"
        }
        return if (serverMessage.isNotBlank()) "$base\n\n${serverMessage.take(100)}" else base
    }
}
