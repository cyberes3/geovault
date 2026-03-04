package com.geovault.places

import android.content.Context
import androidx.core.content.ContextCompat
import org.maplibre.android.utils.BitmapUtils

/**
 * Shared marker bitmap logic for map and edit screens. Uses MapLibre's BitmapUtils so vector
 * drawables (e.g. ic_marker_default) render with correct colors and size.
 */
object MapMarkerUtils {

    /**
     * Returns a bitmap of the given marker drawable. Uses the same conversion as the map so
     * markers look identical on the main map and the place edit map.
     */
    fun getMarkerBitmap(context: Context, drawableResId: Int) =
        BitmapUtils.getBitmapFromDrawable(ContextCompat.getDrawable(context, drawableResId))
}
