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
import com.geovault.common.maps.render.MapSymbolIconStyle
import com.geovault.common.maps.render.StationMarkerSymbol
import kotlin.math.min
import org.maplibre.android.utils.BitmapUtils

object MapMarkerUtils {
    private const val MARKER_SIZE_DP = 16f
    private const val OUTER_RADIUS_RATIO = 0.5f
    private const val INNER_RADIUS_RATIO = 7.25f / 16f
    private const val CENTER_RADIUS_RATIO = 6f / 16f
    private const val STATION_HEAD_CENTER_Y_RATIO = 0.39f
    private const val STATION_SYMBOL_EXTENT_RATIO = 0.16f
    private const val STATION_SYMBOL_STROKE_RATIO = 0.075f

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

    fun buildSymbolIconBitmap(context: Context, style: MapSymbolIconStyle): Bitmap {
        val drawable = requireNotNull(ContextCompat.getDrawable(context, style.backgroundDrawableResId)) {
            "Missing map symbol icon drawable: ${style.backgroundDrawableResId}"
        }
        val wrapped = DrawableCompat.wrap(drawable.mutate())
        DrawableCompat.setTint(wrapped, style.backgroundTintColorInt)
        DrawableCompat.setTintMode(wrapped, PorterDuff.Mode.SRC_IN)

        val base = requireNotNull(BitmapUtils.getBitmapFromDrawable(wrapped)) {
            "Unable to rasterize map symbol icon drawable: ${style.backgroundDrawableResId}"
        }
        val composed = base.copy(base.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(composed)
        val strokeRes = style.overlayStrokeDrawableResId
        if (strokeRes != null) {
            val strokeDrawable = ContextCompat.getDrawable(context, strokeRes)
            strokeDrawable?.setBounds(0, 0, composed.width, composed.height)
            strokeDrawable?.draw(canvas)
        }
        drawStationMarkerSymbol(canvas, composed, style)
        return composed
    }

    private fun drawStationMarkerSymbol(canvas: Canvas, bitmap: Bitmap, style: MapSymbolIconStyle) {
        val symbol = style.stationMarkerSymbol ?: return
        val size = min(bitmap.width, bitmap.height).toFloat()
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height * STATION_HEAD_CENTER_Y_RATIO
        val extent = size * STATION_SYMBOL_EXTENT_RATIO
        val strokeWidth = (size * STATION_SYMBOL_STROKE_RATIO).coerceAtLeast(2f)
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.stationMarkerSymbolHaloColorInt
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.strokeWidth = strokeWidth * 1.65f
        }
        val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.stationMarkerSymbolColorInt
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.strokeWidth = strokeWidth
        }
        drawStationMarkerSymbolShape(canvas, symbol, centerX, centerY, extent, haloPaint)
        drawStationMarkerSymbolShape(canvas, symbol, centerX, centerY, extent, symbolPaint)
    }

    private fun drawStationMarkerSymbolShape(
        canvas: Canvas,
        symbol: StationMarkerSymbol,
        centerX: Float,
        centerY: Float,
        extent: Float,
        paint: Paint,
    ) {
        when (symbol) {
            StationMarkerSymbol.Plus -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(centerX - extent, centerY, centerX + extent, centerY, paint)
                canvas.drawLine(centerX, centerY - extent, centerX, centerY + extent, paint)
            }
            StationMarkerSymbol.Minus -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(centerX - extent, centerY, centerX + extent, centerY, paint)
            }
            StationMarkerSymbol.Pipe -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(centerX, centerY - extent, centerX, centerY + extent, paint)
            }
            StationMarkerSymbol.Disk -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(centerX, centerY, extent * 0.72f, paint)
            }
            StationMarkerSymbol.Intersection -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(centerX - extent, centerY - extent, centerX + extent, centerY + extent, paint)
                canvas.drawLine(centerX + extent, centerY - extent, centerX - extent, centerY + extent, paint)
            }
        }
    }
}
