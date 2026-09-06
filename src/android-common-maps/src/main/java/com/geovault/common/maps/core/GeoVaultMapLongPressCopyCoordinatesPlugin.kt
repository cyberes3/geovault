package com.geovault.common.maps.core

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.widget.Toast
import com.geovault.common.util.ClipboardCopyHelper
import com.geovault.common.maps.R
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap

/**
 * Long-press on the basemap copies formatted lat/lon to the clipboard and shows a short toast.
 * Registered by default on [GeoVaultBaseMap].
 */
class GeoVaultMapLongPressCopyCoordinatesPlugin : GeoVaultMapPlugin {
    private var map: MapLibreMap? = null
    private var clipboardHelper: ClipboardCopyHelper? = null
    private var toastContext: Context? = null

    private val longClickListener = MapLibreMap.OnMapLongClickListener { latLng ->
        val text = formatMapLongPressCoordinates(latLng.latitude, latLng.longitude)
            ?: return@OnMapLongClickListener false
        val helper = clipboardHelper ?: return@OnMapLongClickListener false
        val ctx = toastContext ?: return@OnMapLongClickListener false
        val copied = helper.copyText(text, label = "Coordinates")
        if (copied) {
            performMapCoordinateCopyHaptic(ctx)
            Toast.makeText(
                ctx,
                ctx.getString(R.string.gv_common_map_coordinates_copied_toast),
                Toast.LENGTH_SHORT,
            ).show()
        }
        true
    }

    override fun onMapAttached(map: MapLibreMap) {
        this.map = map
    }

    override fun onMapViewAttached(map: MapLibreMap, mapView: MapView) {
        clipboardHelper = ClipboardCopyHelper(mapView.context)
        toastContext = mapView.context
        map.addOnMapLongClickListener(longClickListener)
    }

    override fun onMapDetached() {
        map?.removeOnMapLongClickListener(longClickListener)
        map = null
        clipboardHelper = null
        toastContext = null
    }
}

private fun performMapCoordinateCopyHaptic(context: Context) {
    val vibratorManager = context.getSystemService(VibratorManager::class.java) ?: return
    val vibrator = vibratorManager.defaultVibrator
    if (!vibrator.hasVibrator()) return
    val timings = longArrayOf(0L, 22L, 42L, 24L, 42L, 22L)
    val amplitudes = intArrayOf(
        0,
        VibrationEffect.DEFAULT_AMPLITUDE,
        0,
        VibrationEffect.DEFAULT_AMPLITUDE,
        0,
        VibrationEffect.DEFAULT_AMPLITUDE,
    )
    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
}
