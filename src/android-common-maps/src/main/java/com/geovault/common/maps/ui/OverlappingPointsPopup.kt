package com.geovault.common.maps.ui

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
import androidx.appcompat.view.ContextThemeWrapper
import com.geovault.common.maps.R
import com.google.android.material.R as MaterialR
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class OverlappingPointsPopup(
    context: Context,
    private val anchor: View,
    private val pointNames: List<String>,
    private val tapX: Int,
    private val tapY: Int,
    private val onSelect: (index: Int) -> Unit,
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
    private val topNotchView: View
    private val bottomNotchView: View
    private val cardView: View

    init {
        val themedContext = ContextThemeWrapper(context, MaterialR.style.Theme_MaterialComponents_DayNight_NoActionBar)
        val inflater = LayoutInflater.from(themedContext)
        contentView = inflater.inflate(R.layout.gv_common_popup_overlapping_points, null)
        listView = contentView.findViewById(R.id.gv_common_popup_list)
        chevronView = contentView.findViewById(R.id.gv_common_popup_chevron)
        topNotchView = contentView.findViewById(R.id.gv_common_popup_notch_top)
        bottomNotchView = contentView.findViewById(R.id.gv_common_popup_notch_bottom)
        cardView = contentView.findViewById(R.id.gv_common_popup_card)
        val adapter = object : ArrayAdapter<String>(
            themedContext,
            R.layout.gv_common_item_overlapping_point,
            R.id.gv_common_overlapping_point_text,
            pointNames,
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
        val itemHeightPx = themedContext.resources.getDimensionPixelSize(R.dimen.gv_common_overlapping_popup_item_height)
        val dividerHeightPx = (1 * themedContext.resources.displayMetrics.density).toInt().coerceAtLeast(0)
        val canScroll = pointNames.size > MAX_VISIBLE_ITEMS
        val visibleRows = pointNames.size.coerceAtMost(MAX_VISIBLE_ITEMS)
        val listHeightPx = if (pointNames.isEmpty()) 0 else visibleRows * itemHeightPx + (visibleRows - 1) * dividerHeightPx
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
            true,
        ).apply {
            isOutsideTouchable = true
            isFocusable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setTouchInterceptor { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                }
                false
            }
            elevation = 0f
        }
        setTouchModalCompat(popupWindow, false)
    }

    fun show() {
        contentView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val contentWidth = contentView.measuredWidth
        val contentHeight = contentView.measuredHeight
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val screenX = anchorLocation[0] + tapX
        val screenY = anchorLocation[1] + tapY
        val rect = Rect()
        anchor.getWindowVisibleDisplayFrame(rect)
        rect.intersect(anchorScreenBounds(anchorLocation))
        popupAvoidanceInsets()?.let { insets ->
            rect.left += insets.left
            rect.top += insets.top
            rect.right -= insets.right
            rect.bottom -= insets.bottom
        }
        val density = anchor.resources.displayMetrics.density
        val paddingPx = (12 * density).toInt()
        val gapPx = ((TAP_PROTECTION_DP + POPUP_GAP_DP) * density).toInt()
        val placement = choosePosition(screenX, screenY, contentWidth, contentHeight, rect, paddingPx, gapPx)
        applyNotchPlacement(placement.notchPlacement, placement.x, screenX)
        popupWindow.showAtLocation(anchor, Gravity.NO_GRAVITY, placement.x, placement.y)
    }

    fun dismiss() {
        popupWindow.dismiss()
    }

    fun isShowing(): Boolean = popupWindow.isShowing

    private fun anchorScreenBounds(anchorLocation: IntArray): Rect {
        return Rect(
            anchorLocation[0],
            anchorLocation[1],
            anchorLocation[0] + anchor.width,
            anchorLocation[1] + anchor.height,
        )
    }

    private fun popupAvoidanceInsets(): Rect? {
        return anchor.getTag(R.id.gv_common_map_popup_avoidance_insets) as? Rect
    }

    private fun choosePosition(
        screenX: Int,
        screenY: Int,
        contentWidth: Int,
        contentHeight: Int,
        rect: Rect,
        paddingPx: Int,
        gapPx: Int,
    ): PopupPlacement {
        val minX = rect.left + paddingPx
        val maxX = rect.right - paddingPx - contentWidth
        val minY = rect.top + paddingPx
        val maxY = rect.bottom - paddingPx - contentHeight
        val safeMinX = minOf(minX, maxX)
        val safeMaxX = maxOf(minX, maxX)
        val safeMinY = minOf(minY, maxY)
        val safeMaxY = maxOf(minY, maxY)
        val yAbove = screenY - gapPx - contentHeight
        val xCentered = (screenX - contentWidth / 2).coerceIn(safeMinX, safeMaxX)
        if (yAbove >= minY && yAbove + contentHeight <= rect.bottom - paddingPx) {
            return PopupPlacement(
                x = xCentered,
                y = yAbove,
                notchPlacement = NotchPlacement.Bottom,
            )
        }
        val yBelow = screenY + gapPx
        if (yBelow >= minY && yBelow + contentHeight <= rect.bottom - paddingPx) {
            return PopupPlacement(
                x = xCentered,
                y = yBelow,
                notchPlacement = NotchPlacement.Top,
            )
        }
        val xLeft = screenX - gapPx - contentWidth
        if (xLeft >= minX && xLeft + contentWidth <= rect.right - paddingPx) {
            val y = (screenY - contentHeight / 2).coerceIn(safeMinY, safeMaxY)
            val notchPlacement = if (screenY >= y + contentHeight / 2) {
                NotchPlacement.Bottom
            } else {
                NotchPlacement.Top
            }
            return PopupPlacement(
                x = xLeft,
                y = y,
                notchPlacement = notchPlacement,
            )
        }
        val xRight = screenX + gapPx
        if (xRight >= minX && xRight + contentWidth <= rect.right - paddingPx) {
            val y = (screenY - contentHeight / 2).coerceIn(safeMinY, safeMaxY)
            val notchPlacement = if (screenY >= y + contentHeight / 2) {
                NotchPlacement.Bottom
            } else {
                NotchPlacement.Top
            }
            return PopupPlacement(
                x = xRight,
                y = y,
                notchPlacement = notchPlacement,
            )
        }

        val yAboveClamped = yAbove.coerceIn(safeMinY, safeMaxY)
        val yBelowClamped = yBelow.coerceIn(safeMinY, safeMaxY)
        return if (yAboveClamped <= yBelowClamped) {
            PopupPlacement(
                x = xCentered,
                y = yAboveClamped,
                notchPlacement = NotchPlacement.Bottom,
            )
        } else {
            PopupPlacement(
                x = xCentered,
                y = yBelowClamped,
                notchPlacement = NotchPlacement.Top,
            )
        }
    }

    private fun applyNotchPlacement(placement: NotchPlacement, popupX: Int, tapScreenX: Int) {
        val topVisible = placement == NotchPlacement.Top
        topNotchView.visibility = if (topVisible) View.VISIBLE else View.GONE
        bottomNotchView.visibility = if (topVisible) View.GONE else View.VISIBLE
        topNotchView.translationX = 0f
        bottomNotchView.translationX = 0f

        val notchView = if (topVisible) topNotchView else bottomNotchView
        notchView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val notchWidth = max(1, notchView.measuredWidth)
        val cardWidth = max(1, cardView.measuredWidth)
        val notchHalf = notchWidth / 2
        val targetCenterX = tapScreenX - popupX
        val clampedCenterX = min(max(targetCenterX, notchHalf), cardWidth - notchHalf)
        val defaultCenterX = cardWidth / 2
        notchView.translationX = (clampedCenterX - defaultCenterX).toFloat()
    }

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

    private enum class NotchPlacement {
        Top,
        Bottom,
    }

    private data class PopupPlacement(
        val x: Int,
        val y: Int,
        val notchPlacement: NotchPlacement,
    )

}
