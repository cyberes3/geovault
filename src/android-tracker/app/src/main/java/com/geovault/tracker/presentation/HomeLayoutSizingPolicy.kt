package com.geovault.tracker.presentation

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HomeLayoutMode {
    NORMAL,
    TIGHT,
    COMPACT_HIDE_RADAR,
}

data class HomeLayoutSizingInput(
    val density: Density,
    val previousMode: HomeLayoutMode,
    val availableHeightPx: Int,
    val occlusionPx: Int,
)

object HomeLayoutSizingPolicy {

    /**
     * Total vertical space needed in [NORMAL] mode (px), for tests and diagnostics.
     */
    internal fun requiredNormalHeightPxForTesting(input: HomeLayoutSizingInput): Int =
        requiredHeightForScenario(
            density = input.density,
            includeRadar = true,
            radarContainerDp = NORMAL_RADAR_CONTAINER_DP,
            radarBottomSpacerDp = NORMAL_RADAR_BOTTOM_SPACER_DP,
            topPaddingDp = NORMAL_PADDING_TOP_DP,
            bottomPaddingDp = NORMAL_PADDING_BOTTOM_DP,
            sessionStatsBottomSpacerDp = NORMAL_SESSION_STATS_BOTTOM_SPACER_DP,
            inlineTopSpacerDp = NORMAL_INLINE_TOP_SPACER_DP,
        )

    @JvmStatic
    fun resolveMode(input: HomeLayoutSizingInput): HomeLayoutMode {
        val density = input.density
        val compactExitBufferPx = with(density) { COMPACT_EXIT_BUFFER_DP.dp.roundToPx() }

        val requiredNormal = requiredHeightForScenario(
            density = density,
            includeRadar = true,
            radarContainerDp = NORMAL_RADAR_CONTAINER_DP,
            radarBottomSpacerDp = NORMAL_RADAR_BOTTOM_SPACER_DP,
            topPaddingDp = NORMAL_PADDING_TOP_DP,
            bottomPaddingDp = NORMAL_PADDING_BOTTOM_DP,
            sessionStatsBottomSpacerDp = NORMAL_SESSION_STATS_BOTTOM_SPACER_DP,
            inlineTopSpacerDp = NORMAL_INLINE_TOP_SPACER_DP,
        )
        val requiredTight = requiredHeightForScenario(
            density = density,
            includeRadar = true,
            radarContainerDp = TIGHT_RADAR_CONTAINER_DP,
            radarBottomSpacerDp = TIGHT_RADAR_BOTTOM_SPACER_DP,
            topPaddingDp = COMPACT_PADDING_TOP_DP,
            bottomPaddingDp = COMPACT_PADDING_BOTTOM_DP,
            sessionStatsBottomSpacerDp = TIGHT_SESSION_STATS_BOTTOM_SPACER_DP,
            inlineTopSpacerDp = COMPACT_INLINE_TOP_SPACER_DP,
        )
        val requiredCompact = requiredHeightForScenario(
            density = density,
            includeRadar = false,
            radarContainerDp = TIGHT_RADAR_CONTAINER_DP,
            radarBottomSpacerDp = TIGHT_RADAR_BOTTOM_SPACER_DP,
            topPaddingDp = COMPACT_PADDING_TOP_DP,
            bottomPaddingDp = COMPACT_PADDING_BOTTOM_DP,
            sessionStatsBottomSpacerDp = TIGHT_SESSION_STATS_BOTTOM_SPACER_DP,
            inlineTopSpacerDp = COMPACT_INLINE_TOP_SPACER_DP,
        )

        val pressureNormal = requiredNormal - input.availableHeightPx
        val pressureTight = requiredTight - input.availableHeightPx
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
                    occludedNormal < -compactExitBufferPx -> HomeLayoutMode.NORMAL
                    occludedTight <= 0 -> HomeLayoutMode.TIGHT
                    else -> HomeLayoutMode.COMPACT_HIDE_RADAR
                }
            }
            HomeLayoutMode.COMPACT_HIDE_RADAR -> {
                if (occludedTight < -compactExitBufferPx) {
                    HomeLayoutMode.TIGHT
                } else {
                    HomeLayoutMode.COMPACT_HIDE_RADAR
                }
            }
        }
    }

    private const val COMPACT_EXIT_BUFFER_DP = 20f

    private const val NORMAL_RADAR_CONTAINER_DP = 180f
    private const val NORMAL_RADAR_BOTTOM_SPACER_DP = 24f
    private const val NORMAL_PADDING_TOP_DP = 32f
    private const val NORMAL_PADDING_BOTTOM_DP = 16f
    private const val NORMAL_SESSION_STATS_BOTTOM_SPACER_DP = 24f
    private const val NORMAL_INLINE_TOP_SPACER_DP = 12f

    private const val TIGHT_RADAR_CONTAINER_DP = 132f
    private const val TIGHT_RADAR_BOTTOM_SPACER_DP = 8f
    private const val COMPACT_PADDING_TOP_DP = 12f
    private const val COMPACT_PADDING_BOTTOM_DP = 4f
    private const val TIGHT_SESSION_STATS_BOTTOM_SPACER_DP = 16f
    private const val COMPACT_INLINE_TOP_SPACER_DP = 8f

    /**
     * Height below the radar: status, stats grid, spacer before start/stop, button, inline icon row.
     * Matches [com.geovault.tracker.ui.HomeScreenKt.TrackingContainer] vertical stack (spacers use [dp],
     * text uses [sp] so height tracks font scale).
     */
    private fun homeContentBelowRadarPx(
        density: Density,
        sessionStatsBottomSpacerDp: Float,
        inlineTopSpacerDp: Float,
    ): Int = with(density) {
        val status = 24.sp.roundToPx()
        val afterStatus = 4.dp.roundToPx()
        val tracker = 16.sp.roundToPx()
        val afterTracker = 16.dp.roundToPx()
        val stats = statsBlockHeightPx()
        val beforePrimary = sessionStatsBottomSpacerDp.dp.roundToPx()
        val primaryButton = 64.dp.roundToPx()
        val afterPrimary = inlineTopSpacerDp.dp.roundToPx()
        val inlineRow = 40.dp.roundToPx()
        status + afterStatus + tracker + afterTracker + stats + beforePrimary + primaryButton + afterPrimary + inlineRow
    }

    private fun Density.statsBlockHeightPx(): Int {
        val row = statCardRowHeightPx()
        return row * 3 + 6.dp.roundToPx() * 2
    }

    private fun Density.statCardRowHeightPx(): Int =
        12.dp.roundToPx() +
            14.sp.roundToPx() +
            4.dp.roundToPx() +
            16.sp.roundToPx() +
            12.dp.roundToPx()

    private fun requiredHeightForScenario(
        density: Density,
        includeRadar: Boolean,
        radarContainerDp: Float,
        radarBottomSpacerDp: Float,
        topPaddingDp: Float,
        bottomPaddingDp: Float,
        sessionStatsBottomSpacerDp: Float,
        inlineTopSpacerDp: Float,
    ): Int {
        val radarBlockPx = if (includeRadar) {
            with(density) {
                radarContainerDp.dp.roundToPx() + radarBottomSpacerDp.dp.roundToPx()
            }
        } else {
            0
        }
        val contentPx = homeContentBelowRadarPx(
            density = density,
            sessionStatsBottomSpacerDp = sessionStatsBottomSpacerDp,
            inlineTopSpacerDp = inlineTopSpacerDp,
        )
        val paddingPx = with(density) {
            topPaddingDp.dp.roundToPx() + bottomPaddingDp.dp.roundToPx()
        }
        return radarBlockPx + contentPx + paddingPx
    }
}
