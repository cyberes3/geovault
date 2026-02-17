package com.geovault.uploader

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView

/**
 * Utility to manually animate rotation of a view.
 * Used for spinners to ensure consistent animation regardless of system settings.
 */
class RotationHelper(private val view: ImageView) {
    private val handler = Handler(Looper.getMainLooper())
    private var rotationRunnable: Runnable? = null

    fun start() {
        if (rotationRunnable != null) return
        rotationRunnable = object : Runnable {
            override fun run() {
                view.rotation = (view.rotation + 10) % 360
                handler.postDelayed(this, 30)
            }
        }
        handler.post(rotationRunnable!!)
    }

    fun stop() {
        rotationRunnable?.let {
            handler.removeCallbacks(it)
            rotationRunnable = null
        }
    }
}
