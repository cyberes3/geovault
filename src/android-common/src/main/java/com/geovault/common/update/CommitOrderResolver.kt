package com.geovault.common.update

import android.util.Log

class CommitOrderResolver(
    private val giteaReleaseApi: GiteaReleaseApi
) {
    fun isReleaseCommitNewer(
        codeRepoPath: String,
        localCommitSha: String,
        releaseCommitSha: String
    ): Boolean {
        val local = localCommitSha.lowercase()
        val release = releaseCommitSha.lowercase()
        if (local == release) return false

        val compare = giteaReleaseApi.compareCommits(
            repoPath = codeRepoPath,
            baseCommit = local,
            headCommit = release
        )
        val status = compare?.status?.lowercase()
        val totalCommits = compare?.totalCommits
        Log.d(
            UpdateCheckLog.TAG,
            "commit order: compare base(local)=$local head(release)=$release " +
                "status=${status ?: "null/missing"} totalCommits=${totalCommits ?: -1}"
        )
        when (status) {
            "ahead" -> {
                Log.d(UpdateCheckLog.TAG, "commit order: Gitea compare says head is ahead of base → newer release")
                return true
            }
            "identical" -> return false
            "behind" -> return false
        }
        if (totalCommits != null) {
            val newer = totalCommits > 0
            Log.d(UpdateCheckLog.TAG, "commit order: derived from total_commits=$totalCommits newer=$newer")
            return newer
        }
        Log.w(
            UpdateCheckLog.TAG,
            "commit order: compare endpoint missing/unexpected status; treating release as not newer"
        )
        return false
    }
}
