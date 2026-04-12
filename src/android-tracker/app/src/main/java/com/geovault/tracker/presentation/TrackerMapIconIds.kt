package com.geovault.tracker.presentation

import com.geovault.tracker.DEFAULT_TRACKER_COLOR_HEX

object TrackerMapIconIds {
    private const val SELECTED_PREFIX = "track-direction-arrow-"
    private const val SIMPLE_PREFIX = "track-direction-arrow-simple-"
    const val SELECTED_DEFAULT = "track-direction-arrow"
    // Keep map icon default in sync with shared tracker fallback color policy.
    const val DEFAULT_COLOR_HEX: String = DEFAULT_TRACKER_COLOR_HEX

    data class IconSpec(
        val colorHex: String,
        val chevronOnly: Boolean,
    )

    fun selectedForColor(colorHex: String): String = "$SELECTED_PREFIX${toSuffix(colorHex)}"

    fun simpleForColor(colorHex: String): String = "$SIMPLE_PREFIX${toSuffix(colorHex)}"

    fun parseSpec(imageId: String): IconSpec? {
        return when {
            imageId == SELECTED_DEFAULT -> IconSpec(DEFAULT_COLOR_HEX, chevronOnly = false)
            imageId.startsWith(SIMPLE_PREFIX) -> {
                val suffix = imageId.removePrefix(SIMPLE_PREFIX)
                if (suffix.isBlank()) null else IconSpec("#$suffix", chevronOnly = true)
            }
            imageId.startsWith(SELECTED_PREFIX) -> {
                val suffix = imageId.removePrefix(SELECTED_PREFIX)
                if (suffix.isBlank()) null else IconSpec("#$suffix", chevronOnly = false)
            }
            else -> null
        }
    }

    private fun toSuffix(colorHex: String): String {
        return colorHex.trim().removePrefix("#")
    }
}
