package com.geovault.common.maps.kml.icon

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Reads an embedded icon from a KMZ zip. Path fallbacks match the GeoVault backend
 * `extract_icon_from_kmz` helper.
 */
object KmzIconExtractor {

    const val MAX_DECOMPRESSED_BYTES: Int = 10 * 1024 * 1024

    private const val TAG = "KmzIconExtractor"
    private const val READ_CHUNK_BYTES = 64 * 1024

    fun extract(kmzFile: File, iconPath: String): ByteArray? {
        if (!kmzFile.isFile || iconPath.isBlank()) return null
        return try {
            ZipFile(kmzFile).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                val candidates = candidatePaths(iconPath)
                for (path in candidates) {
                    if (path in names) {
                        return@use readBounded(zip, path)
                    }
                }
                for (path in candidates) {
                    val lower = path.lowercase(Locale.US)
                    val match = names.firstOrNull { it.lowercase(Locale.US) == lower } ?: continue
                    return@use readBounded(zip, match)
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract KMZ icon '$iconPath' from ${kmzFile.name}", e)
            null
        }
    }

    internal fun candidatePaths(iconPath: String): List<String> {
        val paths = ArrayList<String>(3)
        if (iconPath.isNotEmpty()) {
            paths.add(iconPath)
        }
        val normalized = when {
            iconPath.startsWith(":/") -> iconPath.removePrefix(":/")
            iconPath.startsWith("files/") -> iconPath.removePrefix("files/")
            else -> iconPath
        }
        if (normalized.isNotEmpty() && normalized != iconPath) {
            paths.add(normalized)
        }
        if (!iconPath.startsWith("files/") && !iconPath.startsWith(":/") && iconPath.isNotEmpty()) {
            paths.add("files/$iconPath")
        }
        return paths
    }

    private fun readBounded(zip: ZipFile, name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(READ_CHUNK_BYTES)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_DECOMPRESSED_BYTES) {
                    Log.w(TAG, "KMZ icon '$name' exceeds $MAX_DECOMPRESSED_BYTES byte limit")
                    return null
                }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
