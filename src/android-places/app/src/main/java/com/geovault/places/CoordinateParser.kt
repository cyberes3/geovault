package com.geovault.places

import com.synerset.unitility.unitsystem.geographic.Latitude
import com.synerset.unitility.unitsystem.geographic.Longitude
import com.synerset.unitility.unitsystem.util.PhysicalQuantityParsingFactory

/**
 * Parses a unified coordinates string into lat/lon using Unitility (no preprocessing).
 * Supported formats (handled out-of-the-box by the library):
 * - Decimal: "37.7749, -122.4194"
 * - DMS: "45° 46' 52\" N 108° 30' 14\" W"
 * - Decimal minutes (DDM): "45° 46.8666' N 108° 30.2333' W"
 * - Decimal degrees with directions: "45.7811111° N 108.5038888° W"
 * Returns Pair(lat, lon) in degrees, or null if parsing fails.
 */
object CoordinateParser {

    private val parsingFactory by lazy {
        PhysicalQuantityParsingFactory.getDefaultParsingFactory()
    }

    /**
     * Direction-style: two chunks (body + N/S/E/W), any style.
     * Body: digits, spaces, ° ' " . , o deg/min/sec letters, minus.
     */
    private val latLonPattern = Regex(
        """([\s\d°'\".,oA-Za-z\-]+[NnSsEeWw])\s*([\s\d°'\".,oA-Za-z\-]+[NnSsEeWw])"""
    )

    /** Comma-separated numeric coords (no N/S/E/W in second part = separator comma, not decimal). */
    private val commaCoordsPattern = Regex(
        """([\d\s.\-°'"]+),([\d\s.\-°'"]+)"""
    )

    /**
     * Try to parse the input as latitude and longitude.
     * Unitility accepts DMS and plain decimal (README 8.4). If it rejects a part (e.g. DDM or "45.7811111° N"),
     * we convert that part to signed decimal and pass that so the library still creates the quantity.
     */
    fun parse(input: String?): Pair<Double, Double>? {
        if (input.isNullOrBlank()) return null
        val normalized = normalizePrimeSymbols(input.trim())
        val parts = splitCoordinates(normalized) ?: return null
        val (first, second) = parts
        return try {
            val latitude = parsingFactory.parse(Latitude::class.java, first)
            val longitude = parsingFactory.parse(Longitude::class.java, second)
            Pair(latitude.getInDegrees(), longitude.getInDegrees())
        } catch (e: Exception) {
            val latDeg = partToSignedDecimalDegrees(first) ?: return null
            val lonDeg = partToSignedDecimalDegrees(second) ?: return null
            try {
                val lat = parsingFactory.parse(Latitude::class.java, latDeg.toString())
                val lon = parsingFactory.parse(Longitude::class.java, lonDeg.toString())
                Pair(lat.getInDegrees(), lon.getInDegrees())
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** Convert one part (e.g. "45° 46.8666' N" or "45.7811111° N") to signed decimal degrees for Unitility. */
    private fun partToSignedDecimalDegrees(part: String): Double? {
        val t = part.trim()
        val last = t.lastOrNull() ?: return null
        val sign = when (last.uppercaseChar()) {
            'S', 'W' -> -1.0
            'N', 'E' -> 1.0
            else -> return null
        }
        val body = t.dropLast(1).trim()
        val ddm = Regex("""^(-?\d+)\s*°\s*(\d+(?:\.\d+)?)\s*'""").find(body)
        if (ddm != null) {
            val deg = ddm.groupValues[1].toDoubleOrNull() ?: return null
            val min = ddm.groupValues[2].toDoubleOrNull() ?: return null
            return sign * (kotlin.math.abs(deg) + min / 60.0)
        }
        val dd = Regex("""^(-?\d+(?:\.\d+)?)\s*°?""").find(body)
        if (dd != null) {
            val deg = dd.groupValues[1].toDoubleOrNull() ?: return null
            return sign * kotlin.math.abs(deg)
        }
        return null
    }

    /** True if input looks like a coordinate string (so we should not geocode it when parse fails). */
    fun looksLikeCoordinates(input: String): Boolean {
        val n = normalizePrimeSymbols(input.trim())
        return latLonPattern.containsMatchIn(n) || commaCoordsPattern.containsMatchIn(n)
    }

    /** Normalize Unicode variants to ASCII so regex and Unitility both accept. */
    private fun normalizePrimeSymbols(s: String): String =
        s.replace("\uFEFF", "")       // BOM
            .replace('\u00A0', ' ')   // non-breaking space → space (so \\s matches)
            .replace('\u2032', '\'')  // ′ prime (minutes)
            .replace('\u2033', '"')   // ″ double prime (seconds)
            .replace('\u02DA', '°')   // ˚ ring above (degree)
            .replace('\u00BA', '°')   // º ordinal (degree)

    /**
     * Splits full coord string into [latStr, lonStr] for Unitility. Regex only.
     * Tries direction-style first (so 45°46,8666' N 108°... is not split on decimal comma), then comma-separated numeric.
     */
    private fun splitCoordinates(input: String): Pair<String, String>? {
        latLonPattern.find(input)?.let { m ->
            val a = m.groupValues[1].trim()
            val b = m.groupValues[2].trim()
            if (a.isNotEmpty() && b.isNotEmpty()) return Pair(a, b)
        }
        commaCoordsPattern.find(input)?.let { m ->
            val a = m.groupValues[1].trim()
            val b = m.groupValues[2].trim()
            if (a.isNotEmpty() && b.isNotEmpty()) return Pair(a, b)
        }
        return null
    }
}
