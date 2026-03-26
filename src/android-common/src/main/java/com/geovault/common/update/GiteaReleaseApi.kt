package com.geovault.common.update

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

data class GiteaReleaseAssetDto(
    val name: String,
    val browserDownloadUrl: String
)

data class GiteaReleaseDto(
    val tagName: String,
    val htmlUrl: String,
    val assets: List<GiteaReleaseAssetDto>
)

data class GiteaCommitCompareDto(
    val status: String?,
    val totalCommits: Int?
)

open class GiteaReleaseApi(
    private val baseUrl: String = DEFAULT_GITEA_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    open fun fetchReleases(repoPath: String, limit: Int): List<GiteaReleaseDto> {
        val (owner, repo) = parseRepoPath(repoPath)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/releases".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("limit", limit.toString())
            ?.build()
            ?: run {
                Log.w(UpdateCheckLog.TAG, "fetchReleases: invalid URL for repoPath=$repoPath")
                return emptyList()
            }
        val body = executeGet(url.toString(), "fetchReleases") ?: return emptyList()
        val json = JSONArray(body)
        val releases = mutableListOf<GiteaReleaseDto>()
        for (i in 0 until json.length()) {
            val releaseObj = json.optJSONObject(i) ?: continue
            val tagName = releaseObj.optString("tag_name", "").trim()
            if (tagName.isEmpty()) continue
            val htmlUrl = releaseObj.optString("html_url", "").trim()
            val assetsArray = releaseObj.optJSONArray("assets") ?: JSONArray()
            val assets = mutableListOf<GiteaReleaseAssetDto>()
            for (j in 0 until assetsArray.length()) {
                val assetObj = assetsArray.optJSONObject(j) ?: continue
                val name = assetObj.optString("name", "").trim()
                val downloadUrl = assetObj.optString("browser_download_url", "").trim()
                if (name.isNotEmpty() && downloadUrl.isNotEmpty()) {
                    assets += GiteaReleaseAssetDto(name = name, browserDownloadUrl = downloadUrl)
                }
            }
            releases += GiteaReleaseDto(
                tagName = tagName,
                htmlUrl = if (htmlUrl.isNotEmpty()) htmlUrl else "$baseUrl/$owner/$repo/releases/tag/$tagName",
                assets = assets
            )
        }
        Log.d(
            UpdateCheckLog.TAG,
            "fetchReleases: parsed ${releases.size} release(s) with assets from $owner/$repo (limit=$limit)"
        )
        return releases
    }

    open fun compareCommits(repoPath: String, baseCommit: String, headCommit: String): GiteaCommitCompareDto? {
        val (owner, repo) = parseRepoPath(repoPath)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/compare/$baseCommit...$headCommit"
        val body = executeGet(url, "compareCommits") ?: return null
        val json = JSONObject(body)
        val status = if (json.has("status")) json.optString("status").trim().ifEmpty { null } else null
        val totalCommits = if (json.has("total_commits")) json.optInt("total_commits") else null
        return GiteaCommitCompareDto(status = status, totalCommits = totalCommits)
    }

    open fun fetchCommitDate(repoPath: String, commitSha: String): Instant? {
        val (owner, repo) = parseRepoPath(repoPath)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/git/commits/$commitSha"
        val body = executeGet(url, "fetchCommitDate") ?: return null
        val json = JSONObject(body)
        val commit = json.optJSONObject("commit") ?: return null
        val committer = commit.optJSONObject("committer")
        val author = commit.optJSONObject("author")
        val dateString = (committer?.optString("date", "") ?: "").ifEmpty { author?.optString("date", "") ?: "" }
        if (dateString.isBlank()) return null
        return try {
            Instant.parse(dateString)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    open fun resolveCommitSha(repoPath: String, commitRef: String): String? {
        val normalized = commitRef.trim().lowercase()
        if (normalized.isBlank()) return null
        if (FULL_SHA_REGEX.matches(normalized)) return normalized
        val (owner, repo) = parseRepoPath(repoPath)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/git/commits/$normalized"
        val body = executeGet(url, "resolveCommitSha") ?: return null
        val json = JSONObject(body)
        val sha = json.optString("sha", "").trim().lowercase()
        if (!FULL_SHA_REGEX.matches(sha)) {
            Log.w(UpdateCheckLog.TAG, "resolveCommitSha: invalid resolved sha for ref=$normalized")
            return null
        }
        return sha
    }

    private fun executeGet(url: String, operation: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", REQUIRED_GITEA_USER_AGENT)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = try {
                        response.body?.string()?.take(200)
                    } catch (_: Exception) {
                        null
                    }
                    val msg = buildString {
                        append("Gitea $operation: HTTP ${response.code} ${response.message} url=${response.request.url}")
                        if (!errBody.isNullOrBlank()) {
                            append(" bodySnippet=")
                            append(errBody.replace('\n', ' '))
                        }
                    }
                    Log.w(UpdateCheckLog.TAG, msg)
                    return null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.w(UpdateCheckLog.TAG, "Gitea $operation: request failed: ${e.message}", e)
            null
        }
    }

    private fun parseRepoPath(repoPath: String): Pair<String, String> {
        val trimmed = repoPath.trim().trim('/')
        val parts = trimmed.split('/')
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "repoPath must look like owner/repo"
        }
        return parts[0] to parts[1]
    }

    companion object {
        const val DEFAULT_GITEA_BASE_URL = "https://git.evulid.cc"
        const val REQUIRED_GITEA_USER_AGENT =
            "Gitea-Cloudflare-Antibot-Bypass eichaithahk9ietaGhohxeeg2ahriuG3"
        private val FULL_SHA_REGEX = Regex("^[0-9a-f]{40}$")
    }
}
