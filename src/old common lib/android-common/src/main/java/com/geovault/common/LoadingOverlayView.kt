package com.geovault.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Full-screen loading overlay with optional title and subtext.
 * Shows a centered card with [LoadingSpinner], title, and subtext over a semi-transparent scrim.
 *
 * Usage:
 *   - Add to layout: <com.geovault.common.LoadingOverlayView android:id="@+id/loadingOverlay" ... />
 *   - setTitle(text) / setSubtext(text) — optional; visibility GONE when null/empty
 *   - setSubtextVisible(boolean) — hide subtext while keeping title (e.g. "Saving offline...")
 *   - show() / hide() — visibility and spinner start/stop
 *   - setOnOverlayClickListener(...) — tap-to-cancel or tap-to-save-offline
 */
class LoadingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val spinner: LoadingSpinner
    private val titleView: TextView
    private val subtextView: TextView

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.gv_common_view_loading_overlay, this, true)
        spinner = root.findViewById(R.id.gv_common_loading_overlay_spinner)
        titleView = root.findViewById(R.id.gv_common_loading_overlay_title)
        subtextView = root.findViewById(R.id.gv_common_loading_overlay_subtext)
        visibility = View.GONE
    }

    /**
     * Set the title text. Visibility is GONE when null or empty, VISIBLE otherwise.
     */
    fun setTitle(text: CharSequence?) {
        val str = text?.toString()?.trim()
        titleView.text = str
        titleView.visibility = if (str.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Set the subtext. Visibility is GONE when null or empty, VISIBLE otherwise
     * (unless overridden by setSubtextVisible(false)).
     */
    fun setSubtext(text: CharSequence?) {
        val str = text?.toString()?.trim()
        subtextView.text = str
        subtextView.visibility = if (str.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Show or hide the subtext without changing its content.
     * Used when title is shown but subtext should be hidden (e.g. "Saving offline...").
     */
    fun setSubtextVisible(visible: Boolean) {
        subtextView.visibility = if (visible && subtextView.text.isNotBlank()) View.VISIBLE else View.GONE
    }

    /**
     * Show the overlay and start the spinner.
     */
    fun show() {
        visibility = View.VISIBLE
        spinner.start()
    }

    /**
     * Hide the overlay and stop the spinner (spinner visibility left visible for next show).
     */
    fun hide() {
        spinner.stop(hide = false)
        visibility = View.GONE
    }

    /**
     * Set a click listener on the overlay (e.g. tap to cancel, tap to save offline).
     */
    fun setOnOverlayClickListener(l: OnClickListener?) {
        setOnClickListener(l)
        isClickable = l != null
    }
}
