package com.geovault.common.maps.kml.icon

/**
 * Classifies a KML `Icon/href` so callers can extract from a KMZ, fetch over HTTP, or skip.
 */
sealed class KmlIconHref {
    data class Remote(val url: String) : KmlIconHref()
    data class ArchiveRelative(val path: String) : KmlIconHref()
    data object Unusable : KmlIconHref()

    companion object {
        fun parse(raw: String?): KmlIconHref {
            val href = raw?.trim().orEmpty()
            if (href.isEmpty()) return Unusable
            val schemeSeparator = href.indexOf(':')
            if (href.startsWith("http://", ignoreCase = true) ||
                href.startsWith("https://", ignoreCase = true)
            ) {
                return Remote(href)
            }
            // `:/files/icon.png` is a KMZ-relative Google Earth path, not a URI scheme.
            if (schemeSeparator > 0 && !href.startsWith(":/")) {
                return Unusable
            }
            return ArchiveRelative(href)
        }
    }
}
