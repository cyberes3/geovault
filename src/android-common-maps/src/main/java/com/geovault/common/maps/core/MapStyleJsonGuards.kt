package com.geovault.common.maps.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Inspects a fetched style.json **before** we hand it to MapLibre, rejecting
 * any document that contains a URL field MapLibre's HTTP layer cannot parse.
 *
 * MapLibre's `HttpRequestImpl.executeRequest` does a bare `return` when
 * `HttpUrl.parse` fails on a resource URL — the native engine then waits
 * indefinitely for a completion that never comes. Empty strings are the
 * common case in the wild (e.g. `"glyphs": ""`), but **whitespace-only**
 * and **malformed** URLs (e.g. `"glyphs": "fonts/{fontstack}.pbf"` without a
 * scheme) hit the same path.
 *
 * This guard runs upstream of the engine, but [MapResourceUrlTransform] is
 * the final safety net.
 *
 * Fields validated (per MapLibre style spec v8):
 *  - top-level `glyphs` (string)
 *  - top-level `sprite` (string, or array of `{id, url}`)
 *  - each `sources.*.url`
 *  - each `sources.*.data` (when a string — inline GeoJSON objects pass through)
 *  - each entry of `sources.*.tiles[]`
 */
internal object MapStyleJsonGuards {

    fun hasEmptyOrUnparseableResourceUrl(json: String): Boolean {
        if (json.isBlank()) return true
        return try {
            val root = JSONObject(json)
            !validateRoot(root)
        } catch (_: JSONException) {
            true
        }
    }

    private fun validateRoot(root: JSONObject): Boolean {
        if (!validateTopLevelUrlString(root, "glyphs")) return false
        if (!validateSprite(root)) return false
        val sources = root.optJSONObject("sources") ?: return true
        val ids = sources.keys()
        while (ids.hasNext()) {
            val id = ids.next()
            val source = sources.optJSONObject(id) ?: continue
            if (!validateSourceUrls(source)) return false
        }
        return true
    }

    /** Returns true if the field is absent, or present and a valid URL string. */
    private fun validateTopLevelUrlString(root: JSONObject, key: String): Boolean {
        if (!root.has(key)) return true
        val value = root.opt(key) as? String ?: return false
        return isValidHttpUrlTemplate(value)
    }

    /**
     * `sprite` may be (a) absent, (b) a single string, or (c) an array of
     * `{id, url}` objects per the v8 spec. We accept any of these as long as
     * every URL we can identify is a valid HTTP URL.
     */
    private fun validateSprite(root: JSONObject): Boolean {
        if (!root.has("sprite")) return true
        return when (val sprite = root.get("sprite")) {
            is String -> isValidHttpUrlTemplate(sprite)
            is JSONArray -> {
                for (i in 0 until sprite.length()) {
                    val item = sprite.opt(i) as? JSONObject ?: return false
                    val url = item.opt("url") as? String ?: return false
                    if (!isValidHttpUrlTemplate(url)) return false
                }
                true
            }
            else -> false
        }
    }

    private fun validateSourceUrls(source: JSONObject): Boolean {
        if (source.has("url")) {
            val url = source.opt("url") as? String ?: return false
            if (!isValidHttpUrlTemplate(url)) return false
        }
        if (source.has("data")) {
            val data = source.opt("data")
            if (data is String && !isValidHttpUrlTemplate(data)) return false
        }
        val tiles = source.optJSONArray("tiles")
        if (tiles != null) {
            for (i in 0 until tiles.length()) {
                val tile = tiles.opt(i) as? String ?: return false
                if (!isValidHttpUrlTemplate(tile)) return false
            }
        }
        return true
    }

    /**
     * Validates a string that may be an XYZ tile template
     * (e.g. `https://example.com/{z}/{x}/{y}.png`) or a regular URL
     * (e.g. `https://example.com/style.json`).
     *
     * MapLibre substitutes `{z}` / `{x}` / `{y}` (and others such as
     * `{fontstack}` / `{range}` for glyphs) before issuing the request, so we
     * pre-substitute those with placeholder integers/strings that produce a
     * URL OkHttp's `HttpUrl.parse` accepts. Anything that still fails to
     * parse is malformed.
     */
    private fun isValidHttpUrlTemplate(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        val substituted = TEMPLATE_TOKEN_REGEX.replace(trimmed) { match ->
            TEMPLATE_TOKEN_PLACEHOLDERS[match.groupValues[1]] ?: "x"
        }
        return substituted.toHttpUrlOrNull() != null
    }

    private val TEMPLATE_TOKEN_REGEX = Regex("\\{([a-zA-Z0-9_-]+)\\}")
    private val TEMPLATE_TOKEN_PLACEHOLDERS = mapOf(
        "z" to "0",
        "x" to "0",
        "y" to "0",
        "s" to "a",
        "ratio" to "",
        "bbox-epsg-3857" to "0,0,0,0",
        "quadkey" to "0",
        "fontstack" to "Roboto",
        "range" to "0-255",
    )
}
