package com.geovault.common.maps.kml.icon

/**
 * Loads icon bytes from an `http(s)` URL. Implementations must not throw; return `null` on failure.
 */
fun interface KmlIconBytesFetcher {
    fun fetch(url: String): ByteArray?
}
