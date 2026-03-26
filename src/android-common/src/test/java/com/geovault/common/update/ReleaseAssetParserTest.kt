package com.geovault.common.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseAssetParserTest {
    private val parser = ReleaseAssetParser()

    @Test
    fun parseAssetName_extractsAppAndVersion() {
        val regex = Regex("^(.+?)\\s(\\d{4}-\\d{2}-\\d{2}\\s[0-9a-fA-F]{10})\\.apk$")
        val parsed = parser.parseAssetName(
            assetName = "GeoVault Live Tracker 2026-03-22 4ac2105e8a.apk",
            apkNameRegex = regex
        )
        assertNotNull(parsed)
        assertEquals("GeoVault Live Tracker", parsed?.appName)
        assertEquals("2026-03-22 4ac2105e8a", parsed?.versionLabel)
    }

    @Test
    fun extractCommitRefFromTag_readsHashAtTagEnd() {
        val commit = parser.extractCommitRefFromTag(
            "tracker-2026-03-22-4ac2105e8a111111111111111111111111111111"
        )
        assertEquals("4ac2105e8a111111111111111111111111111111", commit)
    }

    @Test
    fun extractCommitRefFromTag_acceptsShortHash() {
        val commit = parser.extractCommitRefFromTag("uploader-2026-03-25-6bc190f841")
        assertEquals("6bc190f841", commit)
    }

    @Test
    fun findFirstMatchingReleaseAsset_stopsAtFirstAppMatch() {
        val releases = listOf(
            GiteaReleaseDto(
                tagName = "uploader-2026-03-24-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                htmlUrl = "https://git.evulid.cc/cyberes/geovault-app-release/releases/tag/uploader",
                assets = listOf(
                    GiteaReleaseAssetDto(
                        name = "GeoVault Uploader 2026-03-24 aaaaaaaaaa.apk",
                        browserDownloadUrl = "https://example/uploader.apk"
                    )
                )
            ),
            GiteaReleaseDto(
                tagName = "tracker-2026-03-23-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                htmlUrl = "https://git.evulid.cc/cyberes/geovault-app-release/releases/tag/tracker",
                assets = listOf(
                    GiteaReleaseAssetDto(
                        name = "GeoVault Live Tracker 2026-03-23 bbbbbbbbbb.apk",
                        browserDownloadUrl = "https://example/tracker.apk"
                    )
                )
            )
        )
        val regex = Regex("^(.+?)\\s(\\d{4}-\\d{2}-\\d{2}\\s[0-9a-fA-F]{10})\\.apk$")

        val matched = parser.findFirstMatchingReleaseAsset(
            releases = releases,
            apkNameRegex = regex,
            expectedAppName = "GeoVault Live Tracker"
        )

        assertNotNull(matched)
        assertEquals("GeoVault Live Tracker", matched?.appName)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", matched?.releaseCommitSha)
        assertEquals("tracker-2026-03-23-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", matched?.releaseTag)
    }

    @Test
    fun parseAssetName_returnsNullForNonMatchingFile() {
        val regex = Regex("^(.+?)\\s(\\d{4}-\\d{2}-\\d{2}\\s[0-9a-fA-F]{10})\\.apk$")
        assertNull(parser.parseAssetName("README.txt", regex))
    }
}
