package com.geovault.common.maps.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Normalizes server-backed MapLibre styles before validation and handoff to MapLibre.
 *
 * GeoVault owns the glyph endpoint, so server styles that contain text layers should always
 * have a deterministic glyph template even when an upstream provider omits it.
 */
internal object MapStyleJsonNormalizer {
    private const val GEOVAULT_GLYPHS_TEMPLATE = "/api/fonts/{fontstack}/{range}.pbf"

    fun normalizeServerStyle(json: String): String {
        return try {
            val root = JSONObject(json)
            if (requiresGlyphs(root) && root.optString("glyphs").isBlank()) {
                root.put("glyphs", GEOVAULT_GLYPHS_TEMPLATE)
            }
            root.toString()
        } catch (_: JSONException) {
            json
        }
    }

    private fun requiresGlyphs(root: JSONObject): Boolean {
        val layers = root.optJSONArray("layers") ?: return false
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            if (layer.optString("type") != "symbol") continue
            val layout = layer.optJSONObject("layout") ?: continue
            val textField = layout.opt("text-field") ?: continue
            if (textField is String && textField.isBlank()) continue
            if (textField is JSONArray && textField.length() == 0) continue
            return true
        }
        return false
    }
}
