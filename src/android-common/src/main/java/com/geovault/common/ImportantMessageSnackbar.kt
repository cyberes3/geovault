package com.geovault.common

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.gv_common_view_important_message_snackbar, this, true)
        messageText = root.findViewById(R.id.gv_common_important_message_snackbar_text)
        actionButton = root.findViewById(R.id.gv_common_important_message_snackbar_action)
        visibility = View.GONE
        isClickable = true
        isFocusable = true
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener {
            handler.removeCallbacks(dismissRunnable)
            visibility = View.GONE
        }
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

    companion object {
        private const val DISMISS_DELAY_MS = 15_000L
        private const val MAX_HEIGHT_DP = 64f
    }
}
