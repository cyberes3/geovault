package com.geovault.common.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.floor

/**
 * Dominant opaque color of a raster icon, matching the web map's `detectPrimaryColor`.
 *
 * Transparent, near-white, and near-black pixels are ignored so pin artwork outlines and
 * backgrounds do not win. Remaining colors are quantized to 16-level buckets; the most
 * frequent bucket is the result. Light results are darkened so they stay visible on imagery.
 *
 * Returns `null` when no eligible pixels remain (caller should use its default pin).
 */
object IconPrimaryColor {

    private const val MIN_ALPHA = 128
    private const val LIGHT_PIXEL_MIN = 241
    private const val DARK_PIXEL_MAX = 19
    private const val QUANTIZE_STEP = 16
    private const val LIGHTNESS_THRESHOLD = 0.7
    private const val DARKEN_FACTOR = 0.2
    private const val MIN_BRIGHTNESS_KEEP = 0.2
    private const val MIN_VISIBLE_COMPONENT = 40

    fun fromArgbPixels(pixels: IntArray): String? {
        if (pixels.isEmpty()) return null
        val counts = LinkedHashMap<Int, Int>()
        for (pixel in pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < MIN_ALPHA) continue
            val red = (pixel ushr 16) and 0xFF
            val green = (pixel ushr 8) and 0xFF
            val blue = pixel and 0xFF
            if (red >= LIGHT_PIXEL_MIN && green >= LIGHT_PIXEL_MIN && blue >= LIGHT_PIXEL_MIN) continue
            if (red <= DARK_PIXEL_MAX && green <= DARK_PIXEL_MAX && blue <= DARK_PIXEL_MAX) continue
            val quantized = packRgb(
                quantize(red),
                quantize(green),
                quantize(blue),
            )
            counts[quantized] = (counts[quantized] ?: 0) + 1
        }
        var bestCount = 0
        var bestRgb: Int? = null
        for ((rgb, count) in counts) {
            if (count > bestCount) {
                bestCount = count
                bestRgb = rgb
            }
        }
        val rgb = bestRgb ?: return null
        var red = (rgb ushr 16) and 0xFF
        var green = (rgb ushr 8) and 0xFF
        var blue = rgb and 0xFF
        if (isTooLight(red, green, blue)) {
            val darkened = darken(red, green, blue)
            red = darkened[0]
            green = darkened[1]
            blue = darkened[2]
        }
        return rgbToHex(red, green, blue)
    }

    fun fromBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return fromArgbPixels(pixels)
    }

    fun fromImageBytes(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            fromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun quantize(component: Int): Int = (component / QUANTIZE_STEP) * QUANTIZE_STEP

    private fun packRgb(red: Int, green: Int, blue: Int): Int =
        (red shl 16) or (green shl 8) or blue

    private fun isTooLight(red: Int, green: Int, blue: Int): Boolean {
        val luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255.0
        return luminance > LIGHTNESS_THRESHOLD
    }

    private fun darken(red: Int, green: Int, blue: Int): IntArray {
        val actualDarken = minOf(DARKEN_FACTOR, 1.0 - MIN_BRIGHTNESS_KEEP)
        var outR = maxOf(0, floor(red * (1.0 - actualDarken)).toInt())
        var outG = maxOf(0, floor(green * (1.0 - actualDarken)).toInt())
        var outB = maxOf(0, floor(blue * (1.0 - actualDarken)).toInt())
        if (outR < MIN_VISIBLE_COMPONENT && outG < MIN_VISIBLE_COMPONENT && outB < MIN_VISIBLE_COMPONENT) {
            val maxComponent = maxOf(outR, outG, outB)
            if (maxComponent > 0) {
                val scale = MIN_VISIBLE_COMPONENT.toDouble() / maxComponent
                outR = minOf(255, floor(outR * scale).toInt())
                outG = minOf(255, floor(outG * scale).toInt())
                outB = minOf(255, floor(outB * scale).toInt())
            }
        }
        return intArrayOf(outR, outG, outB)
    }

    private fun rgbToHex(red: Int, green: Int, blue: Int): String =
        "#${hexByte(red)}${hexByte(green)}${hexByte(blue)}"

    private fun hexByte(value: Int): String = value.toString(16).padStart(2, '0')
}
