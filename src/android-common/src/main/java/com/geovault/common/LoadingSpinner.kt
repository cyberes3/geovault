package com.geovault.common

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * A smooth rotating loading spinner that bypasses system animation settings.
 * Usage:
 *   - Add to your layout: <com.geovault.common.LoadingSpinner ... />
 *   - Optionally set custom size: app:spinnerSize="40dp" (default 28dp)
 *   - Start: spinner.start()
 *   - Stop: spinner.stop()
 *   - Toggle visibility: spinner.show() / spinner.hide()
 */
class LoadingSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val spinnerView: ImageView
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.gv_common_view_loading_spinner, this, true)
        spinnerView = root.findViewById(R.id.gv_common_spinner_image)
        visibility = View.GONE

        // Read custom attributes
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.LoadingSpinner, 0, 0)
            try {
                val size = typedArray.getDimensionPixelSize(R.styleable.LoadingSpinner_spinnerSize, -1)
                if (size > 0) {
                    val params = spinnerView.layoutParams
                    params.width = size
                    params.height = size
                    spinnerView.layoutParams = params
                }
            } finally {
                typedArray.recycle()
            }
        }
    }

    /**
     * Start the rotation animation.
     * @param speedDegrees Rotation degrees per frame (default 10)
     * @param intervalMs Milliseconds between frames (default 30)
     */
    fun start(speedDegrees: Float = 10f, intervalMs: Long = 30L) {
        if (runnable != null) return
        visibility = View.VISIBLE
        // Apply first rotation step immediately so the spinner draws "in motion" on first frame
        spinnerView.rotation = (spinnerView.rotation + speedDegrees) % 360
        spinnerView.invalidate()
        runnable = object : Runnable {
            override fun run() {
                spinnerView.rotation = (spinnerView.rotation + speedDegrees) % 360
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.post(runnable!!)
    }

    /**
     * Stop the rotation animation.
     * @param hide If true, sets visibility to GONE (default true)
     */
    fun stop(hide: Boolean = true) {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        if (hide) visibility = View.GONE
    }

    /**
     * Show the spinner and start animation.
     */
    fun show() {
        start()
    }

    /**
     * Hide the spinner and stop animation.
     */
    fun hide() {
        stop(hide = true)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop(hide = false)
    }
}
