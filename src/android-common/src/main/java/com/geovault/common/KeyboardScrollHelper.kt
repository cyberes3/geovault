package com.geovault.common

import android.graphics.Rect
import android.view.View
import androidx.core.widget.NestedScrollView

object KeyboardScrollHelper {

    private val KEYBOARD_SETTLE_DELAYS_MS = intArrayOf(0, 120, 260)

/**
 * Auto-scroll focused inputs into view inside a [NestedScrollView].
 *
 * This helper is intended for form screens where the keyboard can hide fields near the bottom.
 */
    fun installNestedScrollFocusAutoScroll(
        scrollView: NestedScrollView,
        focusableViews: List<View>,
        centerBias: Float = 0.5f
    ) {
        focusableViews.forEach { input ->
            input.setOnFocusChangeListener { focusedView, hasFocus ->
                if (hasFocus) {
                    centerChildInVisibleViewportAfterImeSettles(scrollView, focusedView, centerBias)
                }
            }
        }
    }

    fun centerChildInVisibleViewportAfterImeSettles(
        scrollView: NestedScrollView,
        targetView: View,
        centerBias: Float = 0.5f
    ) {
        KEYBOARD_SETTLE_DELAYS_MS.forEach { delayMs ->
            scrollView.postDelayed(
                { centerChildInVisibleViewport(scrollView, targetView, centerBias) },
                delayMs.toLong()
            )
        }
    }

    fun centerChildInVisibleViewport(
        scrollView: NestedScrollView,
        targetView: View,
        centerBias: Float = 0.5f
    ) {
        scrollView.post {
            val rect = Rect()
            targetView.getDrawingRect(rect)
            scrollView.offsetDescendantRectToMyCoords(targetView, rect)

            val clampedBias = centerBias.coerceIn(0f, 1f)
            val viewportHeight = (scrollView.height - scrollView.paddingTop - scrollView.paddingBottom).coerceAtLeast(1)
            val desiredY = rect.centerY() - (viewportHeight * clampedBias).toInt()
            val contentHeight = scrollView.getChildAt(0)?.height ?: 0
            val maxScrollY = (contentHeight - viewportHeight).coerceAtLeast(0)
            val targetScrollY = desiredY.coerceIn(0, maxScrollY)
            scrollView.smoothScrollTo(0, targetScrollY)
        }
    }
}
