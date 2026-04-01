package com.geovault.common.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val networkJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    override fun checkForUpdate(request: VersionCheckRequest): WorkerCheckApiResult {
        if (checkUrl.isBlank()) return WorkerCheckApiResult.Failed("Version-check endpoint URL is blank")
        val body = networkJson.encodeToString(
            WorkerCheckRequestPayload(
                appName = request.appName,
                localFullCommitSha = request.localFullCommitSha
            )
        )
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
            val payload = networkJson.decodeFromString<WorkerCheckSuccessPayload>(body)
            WorkerCheckApiResult.Success(
                WorkerCheckPayload(
                    isLatest = payload.isLatest,
                    appName = payload.appName.trim(),
                    versionLabel = payload.versionLabel.trim(),
                    releasePageUrl = payload.releasePageUrl.trim(),
                    releaseTag = payload.releaseTag.trim(),
                    releaseCommitSha = payload.releaseCommitSha.trim().lowercase(),
                    localCommitSha = payload.localCommitSha.trim().lowercase()
                )
            )
        } catch (e: Exception) {
            WorkerCheckApiResult.Failed("Worker check returned invalid JSON payload", e)
        }
    }

    private fun parseErrorDetail(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val parsed = runCatching { networkJson.decodeFromString<WorkerCheckErrorPayload>(body) }.getOrNull()
        if (parsed != null) {
            val parts = listOf(parsed.error?.trim().orEmpty(), parsed.detail?.trim().orEmpty())
                .filter { it.isNotBlank() }
            if (parts.isNotEmpty()) return parts.joinToString(": ")
        }
        return body.take(250).replace('\n', ' ')
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

    @Serializable
    private data class WorkerCheckRequestPayload(
        val appName: String,
        val localFullCommitSha: String
    )

    @Serializable
    private data class WorkerCheckSuccessPayload(
        val isLatest: Boolean = false,
        val appName: String = "",
        val versionLabel: String = "",
        val releasePageUrl: String = "",
        val releaseTag: String = "",
        val releaseCommitSha: String = "",
        val localCommitSha: String = ""
    )

    @Serializable
    private data class WorkerCheckErrorPayload(
        val error: String? = null,
        val detail: String? = null
    )
}
