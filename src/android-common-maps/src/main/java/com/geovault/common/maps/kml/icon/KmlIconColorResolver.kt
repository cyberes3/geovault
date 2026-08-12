package com.geovault.common.maps.kml.icon

import com.geovault.common.image.IconPrimaryColor
import java.io.File

/**
 * Resolves KML/KMZ icon hrefs to dominant `#rrggbb` colors.
 *
 * Remote `http(s)` hrefs are fetched. Archive-relative hrefs are read from [kmzFile] when
 * provided. Failed, unusable, or undecodable hrefs are omitted so callers keep the default pin.
 */
class KmlIconColorResolver(
    private val remoteFetcher: KmlIconBytesFetcher = KmlRemoteIconFetcher(),
) {

    fun resolve(hrefs: Iterable<String>, kmzFile: File? = null): Map<String, String> {
        val unique = LinkedHashSet<String>()
        for (raw in hrefs) {
            val href = raw.trim()
            if (href.isNotEmpty()) unique.add(href)
        }
        if (unique.isEmpty()) return emptyMap()
        val colors = LinkedHashMap<String, String>(unique.size)
        for (href in unique) {
            val bytes = loadBytes(href, kmzFile) ?: continue
            val color = IconPrimaryColor.fromImageBytes(bytes) ?: continue
            colors[href] = color
        }
        return colors
    }

    private fun loadBytes(href: String, kmzFile: File?): ByteArray? {
        return when (val parsed = KmlIconHref.parse(href)) {
            is KmlIconHref.Remote -> remoteFetcher.fetch(parsed.url)
            is KmlIconHref.ArchiveRelative -> {
                val file = kmzFile ?: return null
                KmzIconExtractor.extract(file, parsed.path)
            }
            KmlIconHref.Unusable -> null
        }
    }
}
