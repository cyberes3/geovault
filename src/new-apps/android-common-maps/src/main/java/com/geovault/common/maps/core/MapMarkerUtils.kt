package com.geovault.common.maps.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

object MapMarkerUtils {
    fun getMarkerBitmap(context: Context, drawableRes: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableRes) ?: return null
        return drawable.toBitmap()
    }

    private fun Drawable.toBitmap(): Bitmap {
        val width = intrinsicWidth.takeIf { it > 0 } ?: 64
        val height = intrinsicHeight.takeIf { it > 0 } ?: 64
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }
}
