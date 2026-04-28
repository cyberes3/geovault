package com.geovault.common.maps.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A fully-resolved, validated basemap selection ready to hand to MapLibre.
 *
 * The point of this type is to make "empty / blank / unparseable URL"
 * **unrepresentable** in the basemap pipeline: every code path that
 * applies a basemap consumes one of these values, and any of them is
 * guaranteed to carry a non-blank URL (and, for vectors, a parseable
 * absolute one).
 *
 * Constructed only via the [Companion] factories, which return `null`
 * for anything that would have produced a degenerate URL further down.
 */
sealed class ResolvedBasemap {
    abstract val sourceId: String

    /**
     * Stable cache key used by the source-apply planner to decide whether
     * the requested basemap is already applied / pending. The format is
     * intentionally documented because [MapSourceApplyPlanner] inspects
     * the leading prefix.
     */
    abstract val cacheKey: String

    /**
     * Raster basemap. [tileTemplate] is an XYZ template such as
     * `https://tile.openstreetmap.org/{z}/{x}/{y}.png` — MapLibre's
     * `TileSet` substitutes the placeholders, so we don't validate it as
     * a parseable [HttpUrl] (the braces are not RFC 3986 allowed chars).
     * The factory only enforces non-blank.
     */
    @ConsistentCopyVisibility
    data class Raster internal constructor(
        override val sourceId: String,
        val tileTemplate: String,
    ) : ResolvedBasemap() {
        override val cacheKey: String = "$RASTER_KEY_PREFIX$sourceId:$tileTemplate"
    }

    /**
     * Vector basemap. [styleUrl] is the absolute URL of the style.json
     * document and is always a parseable [HttpUrl].
     */
    @ConsistentCopyVisibility
    data class Vector internal constructor(
        override val sourceId: String,
        val styleUrl: HttpUrl,
    ) : ResolvedBasemap() {
        override val cacheKey: String = "$VECTOR_KEY_PREFIX$sourceId:$styleUrl"
    }

    companion object {
        const val RASTER_KEY_PREFIX = "raster:"
        const val VECTOR_KEY_PREFIX = "vector:"

        /**
         * @param rawTileUrl XYZ-template URL string. May be `null`/blank
         * (returns `null`) but is otherwise stored verbatim — MapLibre's
         * `TileSet` performs `{z}/{x}/{y}` substitution and will produce
         * fully-formed URLs that the engine-level transform validates.
         */
        fun raster(sourceId: String, rawTileUrl: String?): Raster? {
            if (sourceId.isBlank()) return null
            val template = rawTileUrl?.trim().orEmpty()
            if (template.isEmpty()) return null
            return Raster(sourceId, template)
        }

        /**
         * @param rawStyleUrl Absolute URL of a style.json document.
         * `null`/blank/unparseable returns `null`.
         */
        fun vector(sourceId: String, rawStyleUrl: String?): Vector? {
            if (sourceId.isBlank()) return null
            val trimmed = rawStyleUrl?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val parsed = trimmed.toHttpUrlOrNull() ?: return null
            return Vector(sourceId, parsed)
        }
    }
}
