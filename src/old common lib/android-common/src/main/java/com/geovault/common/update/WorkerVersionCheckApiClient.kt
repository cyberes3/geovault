package com.geovault.common.update

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface VersionCheckApiClient {
    fun checkForUpdate(request: VersionCheckRequest): WorkerCheckApiResult
}

data class WorkerCheckPayload(
    val isLatest: Boolean,
    val appName: String,
    val versionLabel: String,
    val latestApkUrl: String,
    val releasePageUrl: String,
    val releaseTag: String,
    val releaseCommitSha: String,
    val localCommitSha: String,
    val releasesRepo: String?,
    val codeRepo: String?
)

sealed class WorkerCheckApiResult {
    data class Success(
        val payload: WorkerCheckPayload
    ) : WorkerCheckApiResult()

    data class NoMatch(
        val detail: String
    ) : WorkerCheckApiResult()

    data class Failed(
        val detail: String,
        val cause: Throwable? = null
    ) : WorkerCheckApiResult()
}

open class WorkerVersionCheckApiClient(
    private val checkUrl: String = DEFAULT_CHECK_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) : VersionCheckApiClient {
    override fun checkForUpdate(request: VersionCheckRequest): WorkerCheckApiResult {
        val normalizedCheckUrl = checkUrl.trim()
        if (normalizedCheckUrl.isEmpty()) {
            return WorkerCheckApiResult.Failed("Version-check endpoint URL is blank")
        }

        val requestJson = JSONObject()
            .put("appName", request.appName)
            .put("localFullCommitSha", request.localFullCommitSha)
            .toString()

        val httpResult = try {
            executePost(url = normalizedCheckUrl, requestBodyJson = requestJson)
        } catch (e: Exception) {
            Log.w(UpdateCheckLog.TAG, "worker check: request execution failed: ${e.message}", e)
            return WorkerCheckApiResult.Failed(
                detail = "Version-check request failed",
                cause = e
            )
        }

        if (httpResult == null) {
            return WorkerCheckApiResult.Failed("Version-check request failed with no HTTP response")
        }

        if (!httpResult.isSuccessful) {
            val errorDetail = parseErrorDetail(httpResult.body)
            val detail = buildString {
                append("Worker check HTTP ${httpResult.code} ${httpResult.message}")
                if (errorDetail.isNotBlank()) {
                    append(": ")
                    append(errorDetail)
                }
            }
            if (httpResult.code == 404) {
                Log.i(UpdateCheckLog.TAG, "worker check: no matching release (404): $errorDetail")
                return WorkerCheckApiResult.NoMatch(
                    detail = errorDetail.ifBlank { "No matching release found for app" }
                )
            }
            Log.w(UpdateCheckLog.TAG, "worker check failed: $detail")
            return WorkerCheckApiResult.Failed(detail = detail)
        }

        return parseSuccessPayload(httpResult.body)
    }

    protected open fun executePost(url: String, requestBodyJson: String): HttpResult? {
        val body = requestBodyJson.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        return client.newCall(request).execute().use { response ->
            HttpResult(
                code = response.code,
                message = response.message,
                body = response.body?.string()
            )
        }
    }

    private fun parseSuccessPayload(body: String?): WorkerCheckApiResult {
        if (body.isNullOrBlank()) {
            return WorkerCheckApiResult.Failed("Worker check returned empty response body")
        }
        return try {
            val json = JSONObject(body)
            val payload = WorkerCheckPayload(
                isLatest = readRequiredBoolean(json, "isLatest"),
                appName = readRequiredString(json, "appName"),
                versionLabel = readRequiredString(json, "versionLabel"),
                latestApkUrl = readRequiredString(json, "latestApkUrl"),
                releasePageUrl = readRequiredString(json, "releasePageUrl"),
                releaseTag = readRequiredString(json, "releaseTag"),
                releaseCommitSha = readRequiredString(json, "releaseCommitSha").lowercase(),
                localCommitSha = readRequiredString(json, "localCommitSha").lowercase(),
                releasesRepo = readOptionalString(json, "releasesRepo"),
                codeRepo = readOptionalString(json, "codeRepo")
            )
            WorkerCheckApiResult.Success(payload = payload)
        } catch (e: Exception) {
            WorkerCheckApiResult.Failed(
                detail = "Worker check returned invalid JSON payload",
                cause = e
            )
        }
    }

    private fun parseErrorDetail(body: String?): String {
        if (body.isNullOrBlank()) return ""
        return try {
            val json = JSONObject(body)
            val errorCode = readOptionalString(json, "error")
            val detail = readOptionalString(json, "detail")
            buildString {
                if (!errorCode.isNullOrBlank()) {
                    append(errorCode)
                }
                if (!detail.isNullOrBlank()) {
                    if (isNotEmpty()) append(": ")
                    append(detail)
                }
            }
        } catch (_: Exception) {
            body.take(250).replace('\n', ' ')
        }
    }

    private fun readRequiredString(json: JSONObject, key: String): String {
        val value = json.optString(key, "").trim()
        require(value.isNotBlank()) { "Missing required string field '$key'" }
        return value
    }

    private fun readOptionalString(json: JSONObject, key: String): String? {
        if (!json.has(key)) return null
        val value = json.optString(key, "").trim()
        return value.ifBlank { null }
    }

    private fun readRequiredBoolean(json: JSONObject, key: String): Boolean {
        require(json.has(key)) { "Missing required boolean field '$key'" }
        return json.optBoolean(key)
    }

    data class HttpResult(
        val code: Int,
        val message: String,
        val body: String?
    ) {
        val isSuccessful: Boolean
            get() = code in 200..299
    }

    companion object {
        const val DEFAULT_CHECK_URL = "https://git.evulid.cc/api/v1/geovault-app-releases/check"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
