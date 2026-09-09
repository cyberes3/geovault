package com.geovault.common.sharing

object ShareUrl {
    const val MAP_SOCIAL_PREFIX = "/share/map/"
    const val TRACK_SOCIAL_PREFIX = "/share/track/"
    const val MAP_SPA_PATH = "/mapshare"
    const val TRACK_SPA_PATH = "/extensions/live-track/share"

    val PUBLIC_SHARE_PREFIXES = listOf(
        MAP_SPA_PATH,
        MAP_SOCIAL_PREFIX,
        TRACK_SOCIAL_PREFIX,
        TRACK_SPA_PATH,
    )

    fun mapSocial(shareId: String): String = "${MAP_SOCIAL_PREFIX}$shareId/"

    fun mapSpa(shareId: String): String = "/#/mapshare?id=$shareId"

    fun trackSocial(shareId: String): String = "${TRACK_SOCIAL_PREFIX}$shareId/"

    fun trackSpa(shareId: String): String = "/#/extensions/live-track/share?id=$shareId"

    fun forDomain(domain: String, shareId: String, audience: String? = null): String {
        return when {
            domain == "map" -> mapSocial(shareId)
            audience == "world" -> trackSocial(shareId)
            else -> trackSpa(shareId)
        }
    }

    fun parseMapSocialPath(pathname: String): String? =
        uuidFromPrefixedPath(pathname, MAP_SOCIAL_PREFIX)

    fun parseTrackSocialPath(pathname: String): String? =
        uuidFromPrefixedPath(pathname, TRACK_SOCIAL_PREFIX)

    fun remapPathnameToHash(pathname: String, hashValue: String = ""): String? {
        if (hashValue.isNotEmpty()) {
            return null
        }
        parseMapSocialPath(pathname)?.let { return mapSpa(it) }
        parseTrackSocialPath(pathname)?.let { return trackSpa(it) }
        return null
    }

    private fun uuidFromPrefixedPath(pathname: String, prefix: String): String? {
        if (!pathname.startsWith(prefix)) {
            return null
        }
        val rest = pathname.removePrefix(prefix).trim('/')
        return ShareId.parse(rest)?.value
    }
}
