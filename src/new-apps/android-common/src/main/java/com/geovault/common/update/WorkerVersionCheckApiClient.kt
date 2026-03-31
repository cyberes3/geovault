package com.geovault.common.update

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
    val releasePageUrl: String,
    val releaseTag: String,
    val releaseCommitSha: String,
    val localCommitSha: String
)

sealed class WorkerCheckApiResult {
    data class Success(val payload: WorkerCheckPayload) : WorkerCheckApiResult()
    data class NoMatch(val detail: String) : WorkerCheckApiResult()
    data class Failed(val detail: String, val cause: Throwable? = null) : WorkerCheckApiResult()
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
        if (checkUrl.isBlank()) return WorkerCheckApiResult.Failed("Version-check endpoint URL is blank")
        val body = JSONObject()
            .put("appName", request.appName)
            .put("localFullCommitSha", request.localFullCommitSha)
            .toString()
        val response = try {
            executePost(checkUrl.trim(), body)
        } catch (e: Exception) {
            return WorkerCheckApiResult.Failed("Version-check request failed", e)
        } ?: return WorkerCheckApiResult.Failed("Version-check request failed with no response")

        if (!response.isSuccessful) {
            if (response.code == 404) {
                return WorkerCheckApiResult.NoMatch(parseErrorDetail(response.body).ifBlank { "No matching release found" })
            }
            return WorkerCheckApiResult.Failed("Worker check HTTP ${response.code} ${response.message}: ${parseErrorDetail(response.body)}")
        }
        return parseSuccessPayload(response.body)
    }

    protected open fun executePost(url: String, requestBodyJson: String): HttpResult? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            HttpResult(response.code, response.message, response.body.string())
        }
    }

    private fun parseSuccessPayload(body: String?): WorkerCheckApiResult {
        if (body.isNullOrBlank()) return WorkerCheckApiResult.Failed("Worker check returned empty body")
        return try {
            val json = JSONObject(body)
            WorkerCheckApiResult.Success(
                WorkerCheckPayload(
                    isLatest = json.optBoolean("isLatest"),
                    appName = json.optString("appName").trim(),
                    versionLabel = json.optString("versionLabel").trim(),
                    releasePageUrl = json.optString("releasePageUrl").trim(),
                    releaseTag = json.optString("releaseTag").trim(),
                    releaseCommitSha = json.optString("releaseCommitSha").trim().lowercase(),
                    localCommitSha = json.optString("localCommitSha").trim().lowercase()
                )
            )
        } catch (e: Exception) {
            WorkerCheckApiResult.Failed("Worker check returned invalid JSON payload", e)
        }
    }

    private fun parseErrorDetail(body: String?): String {
        if (body.isNullOrBlank()) return ""
        return try {
            val json = JSONObject(body)
            val error = json.optString("error").trim()
            val detail = json.optString("detail").trim()
            listOf(error, detail).filter { it.isNotBlank() }.joinToString(": ")
        } catch (_: Exception) {
            body.take(250).replace('\n', ' ')
        }
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
