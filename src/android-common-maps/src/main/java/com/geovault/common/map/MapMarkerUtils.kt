package com.geovault.common.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import org.maplibre.android.utils.BitmapUtils

/**
 * Shared marker bitmap logic for maps. Uses MapLibre's BitmapUtils so vector
 * drawables render with correct colors and size.
 */
object MapMarkerUtils {

    /**
     * Returns a bitmap of the given marker drawable. Uses the same conversion as the map so
     * markers look identical on the main map.
     */
    fun getMarkerBitmap(context: Context, drawableResId: Int) =
        BitmapUtils.getBitmapFromDrawable(ContextCompat.getDrawable(context, drawableResId))

    /**
     * Returns a bitmap of the drawable with the given tint applied (e.g. for tracker-colored icons).
     * Use with drawables that use a single fill color (e.g. white) so the tint replaces it.
     */
    fun getMarkerBitmap(context: Context, drawableResId: Int, tintColor: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableResId) ?: return null
        val wrapped = DrawableCompat.wrap(drawable.mutate())
        DrawableCompat.setTint(wrapped, tintColor)
        DrawableCompat.setTintMode(wrapped, PorterDuff.Mode.SRC_IN)
        return BitmapUtils.getBitmapFromDrawable(wrapped)
    }

    /**
     * Composites background, then tinted foreground fill, then foreground stroke (no tint) so the
     * fill gets the tracker color and the stroke stays black. Use for track direction icon.
     *
     * @param backgroundAlpha Alpha for the background drawable (white circle + border), 1f = opaque.
     *        Use a value &lt; 1f (e.g. 0.5f) to make the circle and border slightly transparent.
     */
    fun getMarkerBitmapWithTintedForeground(
        context: Context,
        backgroundResId: Int,
        foregroundFillResId: Int,
        foregroundStrokeResId: Int,
        tintColor: Int,
        backgroundAlpha: Float = 1f
    ): Bitmap? {
        val background = ContextCompat.getDrawable(context, backgroundResId) ?: return null
        val fillDrawable = ContextCompat.getDrawable(context, foregroundFillResId) ?: return null
        val strokeDrawable = ContextCompat.getDrawable(context, foregroundStrokeResId) ?: return null
        val sizeBitmap = BitmapUtils.getBitmapFromDrawable(background) ?: return null
        val w = sizeBitmap.width
        val h = sizeBitmap.height
        if (w <= 0 || h <= 0) return null
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        if (backgroundAlpha < 1f) {
            val paint = Paint().apply { alpha = (backgroundAlpha * 255).toInt().coerceIn(0, 255) }
            canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), paint)
            background.setBounds(0, 0, w, h)
            background.draw(canvas)
            canvas.restore()
        } else {
            background.setBounds(0, 0, w, h)
            background.draw(canvas)
        }
        val tintedFill = DrawableCompat.wrap(fillDrawable.mutate())
        DrawableCompat.setTint(tintedFill, tintColor)
        DrawableCompat.setTintMode(tintedFill, PorterDuff.Mode.SRC_IN)
        tintedFill.setBounds(0, 0, w, h)
        tintedFill.draw(canvas)
        strokeDrawable.setBounds(0, 0, w, h)
        strokeDrawable.draw(canvas)
        return result
    }
}
