package com.geovault.common.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommitOrderResolverTest {
    @Test
    fun returnsTrueWhenCompareStatusAhead() {
        val api = FakeGiteaApi(compareStatus = "ahead")
        val resolver = CommitOrderResolver(api)
        assertTrue(
            resolver.isReleaseCommitNewer(
                codeRepoPath = "cyberes/geovault",
                localCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                releaseCommitSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            )
        )
    }

    @Test
    fun returnsFalseWhenCompareStatusBehind() {
        val api = FakeGiteaApi(compareStatus = "behind")
        val resolver = CommitOrderResolver(api)
        assertFalse(
            resolver.isReleaseCommitNewer(
                codeRepoPath = "cyberes/geovault",
                localCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                releaseCommitSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            )
        )
    }

    @Test
    fun returnsFalseWhenCompareUnavailable() {
        val api = FakeGiteaApi(compareStatus = null)
        val resolver = CommitOrderResolver(api)
        assertFalse(
            resolver.isReleaseCommitNewer(
                codeRepoPath = "cyberes/geovault",
                localCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                releaseCommitSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            )
        )
    }

    @Test
    fun returnsTrueWhenTotalCommitsIsPositive() {
        val api = FakeGiteaApi(compareStatus = null, totalCommits = 3)
        val resolver = CommitOrderResolver(api)
        assertTrue(
            resolver.isReleaseCommitNewer(
                codeRepoPath = "cyberes/geovault",
                localCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                releaseCommitSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            )
        )
    }

    private class FakeGiteaApi(
        private val compareStatus: String?,
        private val totalCommits: Int? = null
    ) : GiteaReleaseApi() {
        override fun fetchReleases(repoPath: String, limit: Int): List<GiteaReleaseDto> = emptyList()

        override fun compareCommits(repoPath: String, baseCommit: String, headCommit: String): GiteaCommitCompareDto? {
            return if (compareStatus == null && totalCommits == null) null
            else GiteaCommitCompareDto(compareStatus, totalCommits)
        }
    }
}
