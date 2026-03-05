package com.geovault.common

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

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
    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { visibility = View.GONE }

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.gv_common_view_important_message_snackbar, this, true)
        messageText = root.findViewById(R.id.gv_common_important_message_snackbar_text)
        visibility = View.GONE
        isClickable = true
        isFocusable = true
        setOnClickListener {
            handler.removeCallbacks(dismissRunnable)
            visibility = View.GONE
        }
    }

    /**
     * Show the given message. Cancels any pending auto-dismiss and schedules a new one (15s).
     */
    fun showMessage(message: CharSequence) {
        handler.removeCallbacks(dismissRunnable)
        messageText.text = message
        visibility = View.VISIBLE
        handler.postDelayed(dismissRunnable, DISMISS_DELAY_MS)
    }

    companion object {
        private const val DISMISS_DELAY_MS = 15_000L
    }
}
