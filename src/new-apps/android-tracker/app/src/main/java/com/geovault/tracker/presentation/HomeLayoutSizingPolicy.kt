package com.geovault.tracker.presentation

enum class HomeLayoutMode {
    NORMAL,
    TIGHT,
    COMPACT_HIDE_RADAR,
}

data class HomeLayoutSizingInput(
    val previousMode: HomeLayoutMode,
    val availableHeightPx: Int,
    val occlusionPx: Int,
)

object HomeLayoutSizingPolicy {
    private const val COMPACT_EXIT_BUFFER_PX = 20

    // Default sizing values for home layout behavior.
    private const val NORMAL_PADDING_TOP = 32
    private const val NORMAL_PADDING_BOTTOM = 16
    private const val NORMAL_RADAR_HEIGHT = 180
    private const val NORMAL_RADAR_BOTTOM_MARGIN = 24
    private const val NORMAL_INLINE_TOP_MARGIN = 12

    private const val COMPACT_PADDING_TOP = 12
    private const val COMPACT_PADDING_BOTTOM = 4
    private const val TIGHT_RADAR_HEIGHT = 132
    private const val TIGHT_RADAR_BOTTOM_MARGIN = 8
    private const val COMPACT_INLINE_TOP_MARGIN = 8

    // Estimated non-radar content height in home layout.
    private const val CONTENT_BASE_HEIGHT = 420

    @JvmStatic
    fun resolveMode(input: HomeLayoutSizingInput): HomeLayoutMode {
        val requiredNormal = requiredHeightPx(
            includeRadar = true,
            radarHeightPx = NORMAL_RADAR_HEIGHT,
            radarBottomMarginPx = NORMAL_RADAR_BOTTOM_MARGIN,
            topPaddingPx = NORMAL_PADDING_TOP,
            bottomPaddingPx = NORMAL_PADDING_BOTTOM,
            inlineTopMarginPx = NORMAL_INLINE_TOP_MARGIN,
        )
        val requiredTight = requiredHeightPx(
            includeRadar = true,
            radarHeightPx = TIGHT_RADAR_HEIGHT,
            radarBottomMarginPx = TIGHT_RADAR_BOTTOM_MARGIN,
            topPaddingPx = COMPACT_PADDING_TOP,
            bottomPaddingPx = COMPACT_PADDING_BOTTOM,
            inlineTopMarginPx = COMPACT_INLINE_TOP_MARGIN,
        )
        val requiredCompact = requiredHeightPx(
            includeRadar = false,
            radarHeightPx = TIGHT_RADAR_HEIGHT,
            radarBottomMarginPx = TIGHT_RADAR_BOTTOM_MARGIN,
            topPaddingPx = COMPACT_PADDING_TOP,
            bottomPaddingPx = COMPACT_PADDING_BOTTOM,
            inlineTopMarginPx = COMPACT_INLINE_TOP_MARGIN,
        )

        val pressureNormal = requiredNormal - input.availableHeightPx
        val pressureTight = requiredTight - input.availableHeightPx
        val pressureCompact = requiredCompact - input.availableHeightPx
        val occludedNormal = pressureNormal + input.occlusionPx
        val occludedTight = pressureTight + input.occlusionPx

        return when (input.previousMode) {
            HomeLayoutMode.NORMAL -> {
                when {
                    occludedNormal <= 0 -> HomeLayoutMode.NORMAL
                    occludedTight <= 0 -> HomeLayoutMode.TIGHT
                    else -> HomeLayoutMode.COMPACT_HIDE_RADAR
                }
            }
            HomeLayoutMode.TIGHT -> {
                when {
                    occludedNormal < -COMPACT_EXIT_BUFFER_PX -> HomeLayoutMode.NORMAL
                    occludedTight <= 0 -> HomeLayoutMode.TIGHT
                    else -> HomeLayoutMode.COMPACT_HIDE_RADAR
                }
            }
            HomeLayoutMode.COMPACT_HIDE_RADAR -> {
                if (occludedTight < -COMPACT_EXIT_BUFFER_PX && pressureCompact <= 0) {
                    HomeLayoutMode.TIGHT
                } else {
                    HomeLayoutMode.COMPACT_HIDE_RADAR
                }
            }
        }
    }

    private fun requiredHeightPx(
        includeRadar: Boolean,
        radarHeightPx: Int,
        radarBottomMarginPx: Int,
        topPaddingPx: Int,
        bottomPaddingPx: Int,
        inlineTopMarginPx: Int,
    ): Int {
        val radarBlock = if (includeRadar) radarHeightPx + radarBottomMarginPx else 0
        return CONTENT_BASE_HEIGHT + radarBlock + topPaddingPx + bottomPaddingPx + inlineTopMarginPx
    }
}
