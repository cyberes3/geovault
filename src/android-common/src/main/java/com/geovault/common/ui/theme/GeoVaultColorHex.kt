package com.geovault.common.ui.theme

import android.graphics.Color
import java.util.Locale

/**
 * Parse / normalize / format tracker color hex strings (`#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`).
 */
object GeoVaultColorHex {
    fun normalizeHashPrefix(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    }

    fun normalizeForCompare(raw: String, locale: Locale = Locale.US): String {
        val withHash = normalizeHashPrefix(raw) ?: return ""
        return withHash.lowercase(locale)
    }

    fun isValid(raw: String?): Boolean {
        val hex = raw?.trim()?.removePrefix("#") ?: return false
        if (hex.length != 3 && hex.length != 4 && hex.length != 6 && hex.length != 8) return false
        return hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    fun parseColorInt(hex: String?, fallback: Int): Int {
        val normalized = normalizeHashPrefix(hex) ?: return fallback
        return try {
            val parsedHex = if (normalized.length == 9 && isValid(normalized)) {
                val rrggbb = normalized.substring(1, 7)
                val aa = normalized.substring(7, 9)
                "#$aa$rrggbb"
            } else {
                normalized
            }
            Color.parseColor(parsedHex)
        } catch (_: Exception) {
            fallback
        }
    }

    fun formatRgb(colorInt: Int): String {
        val r = Color.red(colorInt)
        val g = Color.green(colorInt)
        val b = Color.blue(colorInt)
        return String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    }

    fun toRgbaCss(hex: String, alphaByte: Int, fallbackHex: String): String {
        val normalized = (normalizeHashPrefix(hex) ?: fallbackHex).removePrefix("#")
        val fallbackDigits = fallbackHex.removePrefix("#")
        val safeHex = if (normalized.length == 6 && isValid("#$normalized")) {
            normalized
        } else if (fallbackDigits.length == 6) {
            fallbackDigits
        } else {
            normalized
        }
        val r = safeHex.substring(0, 2).toInt(16)
        val g = safeHex.substring(2, 4).toInt(16)
        val b = safeHex.substring(4, 6).toInt(16)
        val a = alphaByte.coerceIn(0, 255) / 255f
        return "rgba($r,$g,$b,$a)"
    }
}
