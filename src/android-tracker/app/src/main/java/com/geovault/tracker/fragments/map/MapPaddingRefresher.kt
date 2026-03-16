package com.geovault.tracker.fragments.map

import android.content.res.Resources
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import org.maplibre.android.camera.CameraPosition

internal object MapPaddingRefresher {
    fun updateRightStackMargins(
        resources: Resources,
        ordered: List<View>
    ) {
        val visible = ordered.filter { it.visibility == View.VISIBLE }
        val density = resources.displayMetrics.density
        val gapPx = (8 * density).toInt()
        val buttonHeightPx = (44 * density).toInt()
        val topDp = 16f
        val stepPx = gapPx + buttonHeightPx
        visible.forEachIndexed { index, view ->
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEachIndexed
            val topPx = (
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, topDp, resources.displayMetrics) +
                    index * stepPx
                ).toInt()
            if (params.topMargin != topPx) {
                params.topMargin = topPx
                view.layoutParams = params
            }
        }
    }

    fun isSamePadding(cameraPosition: CameraPosition, targetPadding: DoubleArray): Boolean {
        val currentPadding = cameraPosition.padding
        return currentPadding != null &&
            kotlin.math.abs(currentPadding[0] - targetPadding[0]) < 1.0 &&
            kotlin.math.abs(currentPadding[1] - targetPadding[1]) < 1.0 &&
            kotlin.math.abs(currentPadding[2] - targetPadding[2]) < 1.0 &&
            kotlin.math.abs(currentPadding[3] - targetPadding[3]) < 1.0
    }

    fun shouldApplyCameraPadding(
        allowCameraMove: Boolean,
        isFollowLockActive: Boolean,
        preserveCenteredGroupFocus: Boolean,
        preserveSingleLiveFit: Boolean,
        preserveCenteredAllTrackersFit: Boolean
    ): Boolean {
        return allowCameraMove &&
            !isFollowLockActive &&
            !preserveCenteredGroupFocus &&
            !preserveSingleLiveFit &&
            !preserveCenteredAllTrackersFit
    }
}
