package com.geovault.common

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

/**
 * Dismissable message bar for important messages (errors, confirmations).
 * Tap to dismiss; auto-dismisses after 15 seconds.
 * Callers should apply navigation bar bottom inset to this view so it appears above the system nav bar.
 */
class ImportantMessageSnackbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val messageText: TextView
    private val actionButton: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { visibility = View.GONE }
    private var baseBottomMarginPx: Int? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var velocityTracker: VelocityTracker? = null

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.gv_common_view_important_message_snackbar, this, true)
        messageText = root.findViewById(R.id.gv_common_important_message_snackbar_text)
        actionButton = root.findViewById(R.id.gv_common_important_message_snackbar_action)
        visibility = View.GONE
        isClickable = true
        isFocusable = true
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        actionButton.setOnClickListener {
            // Don't propagate to parent; action callback is set in showMessage
            handler.removeCallbacks(dismissRunnable)
            visibility = View.GONE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = if (childCount > 0) getChildAt(0) else null
        if (child != null && layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            child.layoutParams = child.layoutParams?.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            val maxHeightPx = (MAX_HEIGHT_DP * resources.displayMetrics.density).toInt()
            val maxChildHeight = (maxHeightPx - paddingTop - paddingBottom).coerceAtLeast(0)
            measureChild(
                child,
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxChildHeight, MeasureSpec.AT_MOST)
            )
            val contentHeight = (child.measuredHeight + paddingTop + paddingBottom).coerceIn(0, maxHeightPx)
            setMeasuredDimension(
                resolveSize(child.measuredWidth + paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(contentHeight, heightMeasureSpec)
            )
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Show the given message. Cancels any pending auto-dismiss and schedules a new one (15s).
     */
    fun showMessage(message: CharSequence) {
        showMessage(message, null, null)
    }

    /**
     * Show the given message with an optional action button.
     * @param actionLabel label for the action button (e.g. "Open"); if null, no button is shown
     * @param action callback when the action button is tapped; dismissed after running
     */
    fun showMessage(message: CharSequence, actionLabel: CharSequence?, action: (() -> Unit)?) {
        handler.removeCallbacks(dismissRunnable)
        // Ensure position is corrected at display time based on current root insets.
        applyCurrentBottomInset()
        messageText.text = message
        if (!actionLabel.isNullOrBlank() && action != null) {
            actionButton.text = actionLabel
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener {
                handler.removeCallbacks(dismissRunnable)
                visibility = View.GONE
                action()
            }
        } else {
            actionButton.visibility = View.GONE
            actionButton.setOnClickListener(null)
        }
        visibility = View.VISIBLE
        handler.postDelayed(dismissRunnable, DISMISS_DELAY_MS)
    }

    /**
     * Applies system bottom inset as external margin so this bottom-anchored view
     * sits above nav/gesture bars and IME.
     */
    fun setBottomInset(bottomInsetPx: Int) {
        val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val base = baseBottomMarginPx ?: lp.bottomMargin.also { baseBottomMarginPx = it }
        val desired = base + bottomInsetPx.coerceAtLeast(0)
        if (lp.bottomMargin == desired) return
        lp.bottomMargin = desired
        layoutParams = lp
    }

    private fun applyCurrentBottomInset() {
        val wi = ViewCompat.getRootWindowInsets(this) ?: return
        val systemBars = wi.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = wi.getInsets(WindowInsetsCompat.Type.ime())
        val bottomInset = if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
        setBottomInset(bottomInset)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != View.VISIBLE) return super.onTouchEvent(event)
        if (isTouchOnActionButton(event)) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                swiping = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!swiping && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    swiping = true
                }
                if (swiping) {
                    translationX = dx
                    val fraction = (abs(dx) / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    alpha = 1f - (fraction * 0.35f)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                val dx = event.rawX - downX
                val dismissByDistance = abs(dx) > width * DISMISS_DISTANCE_FRACTION
                val dismissByVelocity = velocityTracker?.let { tracker ->
                    tracker.computeCurrentVelocity(1000)
                    val vx = tracker.xVelocity
                    abs(vx) > minFlingVelocity && abs(vx) > abs(tracker.yVelocity)
                } == true
                val shouldDismiss = swiping && (dismissByDistance || dismissByVelocity)
                velocityTracker?.recycle()
                velocityTracker = null
                swiping = false
                if (shouldDismiss) {
                    dismissWithSwipe(dx)
                } else {
                    animate().translationX(0f).alpha(1f).setDuration(160L).start()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dismissWithSwipe(dx: Float) {
        handler.removeCallbacks(dismissRunnable)
        val widthPx = width.coerceAtLeast(1).toFloat()
        val targetX = if (dx < 0f) -widthPx else widthPx
        animate()
            .translationX(targetX)
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                translationX = 0f
                alpha = 1f
                visibility = View.GONE
            }
            .start()
    }

    private fun isTouchOnActionButton(event: MotionEvent): Boolean {
        if (actionButton.visibility != View.VISIBLE) return false
        val actionLoc = IntArray(2)
        actionButton.getLocationOnScreen(actionLoc)
        val x = event.rawX
        val y = event.rawY
        return x >= actionLoc[0] &&
            x <= actionLoc[0] + actionButton.width &&
            y >= actionLoc[1] &&
            y <= actionLoc[1] + actionButton.height
    }

    companion object {
        private const val DISMISS_DELAY_MS = 15_000L
        private const val MAX_HEIGHT_DP = 64f
        private const val DISMISS_DISTANCE_FRACTION = 0.35f
    }
}
