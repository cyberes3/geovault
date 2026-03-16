package com.geovault.tracker.fragments.map

import android.content.res.Resources
import android.view.View

internal object MapPaddingCalculator {
    fun calculatePadding(
        resources: Resources,
        mapRoot: View?,
        trackerLabelCard: View,
        rightControls: List<View>,
        geometryLoadingSpinner: View,
        mapTrackerInfoCard: View,
        bottomNavContainer: View?,
        baseLeftDp: Int,
        baseTopDp: Int,
        baseRightDp: Int,
        baseBottomDp: Int,
        extraEdgeDp: Int,
        mapTrackerInfoCardHeightDp: Int
    ): DoubleArray {
        val density = resources.displayMetrics.density
        val mapWidthPx = mapRoot?.width ?: 0
        val mapHeightPx = mapRoot?.height ?: 0
        val baseLeftPx = (baseLeftDp * density).toInt()
        val baseTopPx = (baseTopDp * density).toInt()
        val baseRightPx = (baseRightDp * density).toInt()
        val baseBottomPx = (baseBottomDp * density).toInt()
        val extraPadPx = (extraEdgeDp * density).toInt()

        val leftOverlayInsetPx = if (mapWidthPx > 0 && trackerLabelCard.visibility == View.VISIBLE) {
            trackerLabelCard.right.coerceAtLeast(0)
        } else {
            0
        }
        val leftPaddingPx = maxOf(
            baseLeftPx,
            if (leftOverlayInsetPx > 0) leftOverlayInsetPx + extraPadPx else baseLeftPx
        )

        val topOverlayBottomPx = listOf(trackerLabelCard)
            .filter { it.visibility == View.VISIBLE }
            .maxOfOrNull { it.top + it.height }
            ?: 0
        val topPaddingPx = maxOf(
            baseTopPx,
            if (topOverlayBottomPx > 0) topOverlayBottomPx + extraPadPx else baseTopPx
        )

        val rightOverlayInsetPx = if (mapWidthPx > 0) {
            rightControls
                .filter { it.visibility == View.VISIBLE }
                .maxOfOrNull { (mapWidthPx - it.left).coerceAtLeast(0) }
                ?: 0
        } else {
            0
        }
        val rightPaddingPx = maxOf(
            baseRightPx,
            if (rightOverlayInsetPx > 0) rightOverlayInsetPx + extraPadPx else baseRightPx
        )

        val bottomOverlayInsetPx = if (mapHeightPx > 0) {
            val spinnerInset = if (geometryLoadingSpinner.visibility == View.VISIBLE) {
                (mapHeightPx - geometryLoadingSpinner.top).coerceAtLeast(0)
            } else {
                0
            }
            val infoCardInset = if (mapTrackerInfoCard.visibility == View.VISIBLE) {
                if (mapTrackerInfoCard.height > 0) {
                    (mapHeightPx - mapTrackerInfoCard.top).coerceAtLeast(0)
                } else {
                    (mapTrackerInfoCardHeightDp * density).toInt() + (16 * density).toInt() * 2
                }
            } else {
                0
            }
            maxOf(spinnerInset, infoCardInset)
        } else {
            0
        }

        val bottomNavOverlapPx = run {
            if (mapRoot == null || bottomNavContainer == null || !bottomNavContainer.isShown) {
                0
            } else {
                val mapLoc = IntArray(2)
                val navLoc = IntArray(2)
                mapRoot.getLocationOnScreen(mapLoc)
                bottomNavContainer.getLocationOnScreen(navLoc)
                val mapBottom = mapLoc[1] + mapRoot.height
                (mapBottom - navLoc[1]).coerceAtLeast(0)
            }
        }
        val shownBottomNavHeightPx = if (bottomNavContainer?.isShown == true) {
            bottomNavContainer.height
        } else {
            0
        }
        val bottomPaddingPx = maxOf(
            baseBottomPx,
            if (bottomOverlayInsetPx > 0) bottomOverlayInsetPx + extraPadPx else baseBottomPx,
            if (bottomNavOverlapPx > 0) bottomNavOverlapPx + extraPadPx else baseBottomPx,
            if (shownBottomNavHeightPx > 0) shownBottomNavHeightPx + extraPadPx else baseBottomPx
        )

        return doubleArrayOf(
            leftPaddingPx.toDouble(),
            topPaddingPx.toDouble(),
            rightPaddingPx.toDouble(),
            bottomPaddingPx.toDouble()
        )
    }
}
