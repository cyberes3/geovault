package com.geovault.common.update

class ReleaseAssetParser {
    fun findFirstMatchingReleaseAsset(
        releases: List<GiteaReleaseDto>,
        apkNameRegex: Regex,
        expectedAppName: String?
    ): MatchedReleaseAsset? {
        for (release in releases) {
            val releaseCommit = extractCommitRefFromTag(release.tagName) ?: continue
            for (asset in release.assets) {
                val parsed = parseAssetName(asset.name, apkNameRegex) ?: continue
                val appNameMatches = expectedAppName.isNullOrBlank() || parsed.appName == expectedAppName
                if (!appNameMatches) continue
                return MatchedReleaseAsset(
                    appName = parsed.appName,
                    versionLabel = parsed.versionLabel,
                    assetName = asset.name,
                    releaseTag = release.tagName,
                    releaseUrl = release.htmlUrl,
                    releaseCommitSha = releaseCommit
                )
            }
        }
        return null
    }

    fun parseAssetName(assetName: String, apkNameRegex: Regex): ParsedApkName? {
        val match = apkNameRegex.matchEntire(assetName.trim()) ?: return null
        if (match.groupValues.size < 3) {
            return null
        }
        val appName = match.groupValues[1].trim()
        val version = match.groupValues[2].trim()
        if (appName.isBlank() || version.isBlank()) {
            return null
        }
        return ParsedApkName(appName = appName, versionLabel = version)
    }

    fun extractCommitRefFromTag(tagName: String): String? {
        val match = TAG_COMMIT_REGEX.find(tagName.trim()) ?: return null
        return match.groupValues[1].lowercase()
    }

    data class ParsedApkName(
        val appName: String,
        val versionLabel: String
    )

    companion object {
        // Supports both old full SHA tags and newer short SHA tags.
        private val TAG_COMMIT_REGEX = Regex("([0-9a-fA-F]{7,40})$")
    }
}
