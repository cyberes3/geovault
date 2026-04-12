package com.geovault.common.maps.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.geovault.common.maps.R
import com.geovault.common.maps.render.MapMarkerStyle
import org.maplibre.android.utils.BitmapUtils

object MapMarkerUtils {
    private const val MARKER_SIZE_DP = 16f
    private const val OUTER_RADIUS_RATIO = 0.5f
    private const val INNER_RADIUS_RATIO = 7.25f / 16f
    private const val CENTER_RADIUS_RATIO = 6f / 16f

    fun getMarkerBitmap(context: Context, drawableRes: Int): Bitmap? {
        return BitmapUtils.getBitmapFromDrawable(ContextCompat.getDrawable(context, drawableRes))
    }

    fun getMarkerBitmap(context: Context, drawableResId: Int, tintColor: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableResId) ?: return null
        val wrapped = DrawableCompat.wrap(drawable.mutate())
        DrawableCompat.setTint(wrapped, tintColor)
        DrawableCompat.setTintMode(wrapped, PorterDuff.Mode.SRC_IN)
        return BitmapUtils.getBitmapFromDrawable(wrapped)
    }

    fun getMarkerBitmapWithTintedForeground(
        context: Context,
        backgroundResId: Int,
        foregroundFillResId: Int,
        foregroundStrokeResId: Int,
        tintColor: Int,
        backgroundAlpha: Float = 1f,
    ): Bitmap? {
        val background = ContextCompat.getDrawable(context, backgroundResId) ?: return null
        val fillDrawable = ContextCompat.getDrawable(context, foregroundFillResId) ?: return null
        val strokeDrawable = ContextCompat.getDrawable(context, foregroundStrokeResId) ?: return null
        val sizeBitmap = BitmapUtils.getBitmapFromDrawable(background) ?: return null
        val width = sizeBitmap.width
        val height = sizeBitmap.height
        if (width <= 0 || height <= 0) return null
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        if (backgroundAlpha < 1f) {
            val paint = Paint().apply { alpha = (backgroundAlpha * 255).toInt().coerceIn(0, 255) }
            canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), paint)
            background.setBounds(0, 0, width, height)
            background.draw(canvas)
            canvas.restore()
        } else {
            background.setBounds(0, 0, width, height)
            background.draw(canvas)
        }
        val tintedFill = DrawableCompat.wrap(fillDrawable.mutate())
        DrawableCompat.setTint(tintedFill, tintColor)
        DrawableCompat.setTintMode(tintedFill, PorterDuff.Mode.SRC_IN)
        tintedFill.setBounds(0, 0, width, height)
        tintedFill.draw(canvas)
        strokeDrawable.setBounds(0, 0, width, height)
        strokeDrawable.draw(canvas)
        return result
    }

    fun getDefaultMarkerBitmap(context: Context): Bitmap? {
        return getMarkerBitmap(context, R.drawable.gv_common_ic_marker_default)
    }

    fun getSelectedMarkerBitmap(context: Context): Bitmap? {
        return getMarkerBitmap(context, R.drawable.gv_common_ic_marker_selected)
    }

    fun buildMarkerBitmap(context: Context, style: MapMarkerStyle): Bitmap {
        val density = context.resources.displayMetrics.density
        val sizePx = (MARKER_SIZE_DP * density).toInt().coerceAtLeast(1)
        val center = sizePx / 2f
        val outerRadius = sizePx * OUTER_RADIUS_RATIO
        val innerRadius = sizePx * INNER_RADIUS_RATIO
        val centerRadius = sizePx * CENTER_RADIUS_RATIO
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = style.outerBorderColorInt
        canvas.drawCircle(center, center, outerRadius, paint)
        paint.color = style.innerBorderColorInt
        canvas.drawCircle(center, center, innerRadius, paint)
        paint.color = style.centerColorInt
        canvas.drawCircle(center, center, centerRadius, paint)
        return bitmap
    }
}
