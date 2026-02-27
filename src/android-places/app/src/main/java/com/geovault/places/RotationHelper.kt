package com.geovault.places

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView

/**
 * A utility to rotate an ImageView manually, bypassing system animation settings.
 */
class RotationHelper(private val view: ImageView) {
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    fun start(speedDegrees: Float = 10f, intervalMs: Long = 30L) {
        if (runnable != null) return
        view.visibility = View.VISIBLE
        // Apply first rotation step immediately so the spinner draws "in motion" on first frame
        view.rotation = (view.rotation + speedDegrees) % 360
        view.invalidate()
        runnable = object : Runnable {
            override fun run() {
                view.rotation = (view.rotation + speedDegrees) % 360
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.post(runnable!!)
    }

    fun stop(hide: Boolean = true) {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        if (hide) view.visibility = View.GONE
    }
}
