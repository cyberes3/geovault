package com.geovault.common.maps.external

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.geovault.common.R
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Builds and launches Google Maps for a WGS84 location using an exact-pin [q] URL
 * (`maps.google.com/?q=lat,lon`), not the Maps Search API (`/maps/search/?api=1&query=...`),
 * which can geocode to the wrong place.
 */
object GeoVaultExternalMapLauncher {

    private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val MAPS_BASE = "https://maps.google.com/"
    private const val QUERY_PARAM_Q = "q"
    private const val COORDINATE_PRECISION_DP = 8

    fun buildMapsUrl(latitude: Double, longitude: Double, label: String? = null): String {
        val query = buildQueryValue(latitude = latitude, longitude = longitude, label = label)
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "$MAPS_BASE?$QUERY_PARAM_Q=$encoded"
    }

    fun buildMapsUri(latitude: Double, longitude: Double, label: String? = null): Uri {
        return Uri.parse(buildMapsUrl(latitude = latitude, longitude = longitude, label = label))
    }

    /**
     * Opens Google Maps at [latitude]/[longitude]. Shows a standard toast when no map app
     * can handle the intent.
     *
     * @return `true` when the launch succeeded.
     */
    fun open(
        context: Context,
        latitude: Double,
        longitude: Double,
        label: String? = null,
    ): Boolean {
        return open(
            context = context,
            latitude = latitude,
            longitude = longitude,
            label = label,
            onUnavailable = { showDefaultUnavailableMessage(context) },
        )
    }

    /**
     * Opens Google Maps at [latitude]/[longitude].
     *
     * @return `true` when the launch succeeded.
     */
    fun open(
        context: Context,
        latitude: Double,
        longitude: Double,
        label: String? = null,
        onUnavailable: () -> Unit,
    ): Boolean {
        val activity = context as? Activity ?: return false
        val uri = buildMapsUri(latitude = latitude, longitude = longitude, label = label)
        return launchMap(activity = activity, uri = uri, onUnavailable = onUnavailable)
    }

    internal fun buildQueryValue(latitude: Double, longitude: Double, label: String?): String {
        val latString = String.format(Locale.US, "%.${COORDINATE_PRECISION_DP}f", latitude)
        val lonString = String.format(Locale.US, "%.${COORDINATE_PRECISION_DP}f", longitude)
        val coordinates = "$latString,$lonString"
        val trimmedLabel = label?.trim().orEmpty()
        if (trimmedLabel.isEmpty()) {
            return coordinates
        }
        val safeLabel = trimmedLabel.replace('(', ' ').replace(')', ' ')
        return "$coordinates($safeLabel)"
    }

    private fun launchMap(activity: Activity, uri: Uri, onUnavailable: () -> Unit): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).let { base ->
            val mapsIntent = Intent(base).setPackage(GOOGLE_MAPS_PACKAGE)
            if (mapsIntent.resolveActivity(activity.packageManager) != null) {
                mapsIntent
            } else {
                base
            }
        }
        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
            false
        }
    }

    private fun showDefaultUnavailableMessage(context: Context) {
        val activity = context as? Activity ?: return
        Toast.makeText(
            activity,
            activity.getString(R.string.gv_external_map_unavailable),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
