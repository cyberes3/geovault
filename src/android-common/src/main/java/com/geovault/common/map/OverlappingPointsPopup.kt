package com.geovault.common.map

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import com.geovault.common.R
import kotlin.math.ceil

/**
 * Popup that shows a list of point/feature names when the user taps on overlapping map markers.
 * User picks one; the callback receives the selected index.
 * Theme-aware (surface, text, border from gv_common colors).
 */
class OverlappingPointsPopup(
    context: Context,
    private val anchor: View,
    private val pointNames: List<String>,
    private val tapX: Int,
    private val tapY: Int,
    private val onSelect: (index: Int) -> Unit
) {
    private companion object {
        const val MAX_VISIBLE_ITEMS = 4
        const val TAP_PROTECTION_DP = 10
        const val POPUP_GAP_DP = 4
    }

    private val popupWindow: PopupWindow
    private val contentView: View
    private val listView: ListView
    private val chevronView: ImageView

    init {
        val inflater = LayoutInflater.from(context)
        contentView = inflater.inflate(R.layout.gv_common_popup_overlapping_points, null)
        listView = contentView.findViewById(R.id.gv_common_popup_list)
        chevronView = contentView.findViewById(R.id.gv_common_popup_chevron)

        val adapter = object : ArrayAdapter<String>(
            context,
            R.layout.gv_common_item_overlapping_point,
            R.id.gv_common_overlapping_point_text,
            pointNames
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val itemView = super.getView(position, convertView, parent)
                val textView = itemView.findViewById<TextView>(R.id.gv_common_overlapping_point_text)
                textView.text = getItem(position).orEmpty().ifEmpty { " " }
                return itemView
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            onSelect(position)
            popupWindow.dismiss()
        }

        val itemHeightPx = context.resources.getDimensionPixelSize(R.dimen.gv_common_overlapping_popup_item_height)
        val dividerHeightPx = (1 * context.resources.displayMetrics.density).toInt().coerceAtLeast(0)
        val canScroll = pointNames.size > MAX_VISIBLE_ITEMS
        val visibleRows = pointNames.size.coerceAtMost(MAX_VISIBLE_ITEMS)
        val listHeightPx = if (pointNames.isEmpty()) 0
            else visibleRows * itemHeightPx + (visibleRows - 1) * dividerHeightPx
        val rowWidthPx = computeRowWidthPx(inflater)
        listView.layoutParams = listView.layoutParams.apply {
            height = listHeightPx
            width = rowWidthPx
        }
        listView.isVerticalScrollBarEnabled = canScroll
        listView.overScrollMode = if (canScroll) View.OVER_SCROLL_IF_CONTENT_SCROLLS else View.OVER_SCROLL_NEVER

        chevronView.visibility = if (canScroll) View.VISIBLE else View.GONE

        popupWindow = PopupWindow(
            contentView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            // Non-modal so one outside touch dismisses and still reaches the map.
            isFocusable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setTouchInterceptor { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                }
                false
            }
            // Remove square shadow from PopupWindow layer; only card should cast shadow.
            elevation = 0f
        }
        setTouchModalCompat(popupWindow, false)

        popupWindow.setOnDismissListener {
            // No-op; caller can clear state if needed
        }
    }

    /**
     * Show the popup positioned near the tap point. The popup is never placed over the point:
     * it appears above, below, left, or right of it (with a small gap), choosing the side that
     * fits on screen. List is limited to 4 visible items with scrolling.
     */
    fun show() {
        contentView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val contentWidth = contentView.measuredWidth
        val contentHeight = contentView.measuredHeight

        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val anchorLeft = anchorLocation[0]
        val anchorTop = anchorLocation[1]

        val screenX = anchorLeft + tapX
        val screenY = anchorTop + tapY

        val rect = Rect()
        anchor.getWindowVisibleDisplayFrame(rect)
        val density = anchor.resources.displayMetrics.density
        val paddingPx = (12 * density).toInt()
        // Keep a protected zone around the tapped marker so popup never appears to clip it.
        val gapPx = ((TAP_PROTECTION_DP + POPUP_GAP_DP) * density).toInt()

        // Place popup so it never covers the tap point: above, below, left, or right with a gap.
        val (x, y) = choosePosition(
            screenX = screenX,
            screenY = screenY,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            rect = rect,
            paddingPx = paddingPx,
            gapPx = gapPx
        )

        popupWindow.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    /**
     * Chooses (x, y) so the popup does not overlap (screenX, screenY) and stays in rect.
     * Tries above, below, left, right in order of preference.
     */
    private fun choosePosition(
        screenX: Int,
        screenY: Int,
        contentWidth: Int,
        contentHeight: Int,
        rect: Rect,
        paddingPx: Int,
        gapPx: Int
    ): Pair<Int, Int> {
        val minX = rect.left + paddingPx
        val maxX = rect.right - paddingPx - contentWidth
        val minY = rect.top + paddingPx
        val maxY = rect.bottom - paddingPx - contentHeight
        // When popup is larger than visible rect, max can be < min; coerceIn throws. Use safe range.
        val safeMinX = minOf(minX, maxX)
        val safeMaxX = maxOf(minX, maxX)
        val safeMinY = minOf(minY, maxY)
        val safeMaxY = maxOf(minY, maxY)

        // Above: popup bottom edge at screenY - gap (popup fully above point)
        val yAbove = screenY - gapPx - contentHeight
        if (yAbove >= minY) {
            val x = (screenX - contentWidth / 2).coerceIn(safeMinX, safeMaxX)
            return Pair(x, yAbove)
        }

        // Below: popup top edge at screenY + gap (popup fully below point)
        val yBelow = screenY + gapPx
        if (yBelow >= minY && yBelow + contentHeight <= rect.bottom - paddingPx) {
            val x = (screenX - contentWidth / 2).coerceIn(safeMinX, safeMaxX)
            return Pair(x, yBelow)
        }

        // Left: popup right edge at screenX - gap (popup fully left of point)
        val xLeft = screenX - gapPx - contentWidth
        if (xLeft >= minX && xLeft + contentWidth <= rect.right - paddingPx) {
            val y = (screenY - contentHeight / 2).coerceIn(safeMinY, safeMaxY)
            return Pair(xLeft, y)
        }

        // Right: popup left edge at screenX + gap (popup fully right of point)
        val xRight = screenX + gapPx
        if (xRight >= minX && xRight + contentWidth <= rect.right - paddingPx) {
            val y = (screenY - contentHeight / 2).coerceIn(safeMinY, safeMaxY)
            return Pair(xRight, y)
        }

        // Fallback: prefer above or below even if partially off-screen, then clamp (point may be near edge)
        val yAboveClamped = yAbove.coerceIn(safeMinY, safeMaxY)
        val yBelowClamped = yBelow.coerceIn(safeMinY, safeMaxY)
        return if (yAboveClamped <= yBelowClamped) {
            Pair((screenX - contentWidth / 2).coerceIn(safeMinX, safeMaxX), yAboveClamped)
        } else {
            Pair((screenX - contentWidth / 2).coerceIn(safeMinX, safeMaxX), yBelowClamped)
        }
    }

    fun dismiss() {
        popupWindow.dismiss()
    }

    fun isShowing(): Boolean = popupWindow.isShowing

    private fun setTouchModalCompat(window: PopupWindow, touchModal: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setTouchModal(touchModal)
            return
        }
        try {
            val method = PopupWindow::class.java.getDeclaredMethod("setTouchModal", Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(window, touchModal)
        } catch (_: Throwable) {
            // Best-effort fallback; older platforms may keep modal touch behavior.
        }
    }

    private fun computeRowWidthPx(inflater: LayoutInflater): Int {
        val sampleItem = inflater.inflate(R.layout.gv_common_item_overlapping_point, listView, false)
        val sampleText = sampleItem.findViewById<TextView>(R.id.gv_common_overlapping_point_text)
        val textPaint = sampleText.paint
        val horizontalPadding = sampleText.paddingLeft + sampleText.paddingRight

        val minText = "000000"
        val widestTextPx = pointNames
            .map { it.ifEmpty { " " } }
            .plus(minText)
            .maxOf { label -> ceil(textPaint.measureText(label).toDouble()).toInt() }

        return widestTextPx + horizontalPadding
    }
}
