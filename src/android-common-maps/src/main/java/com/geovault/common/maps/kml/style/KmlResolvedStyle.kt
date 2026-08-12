package com.geovault.common.maps.kml.style

/**
 * Resolved KML style fields the survey importer persists as `imported_*` properties.
 *
 * Mirrors the subset of backend `extract_style()` that the apps actually render:
 * line/poly paint and icon href. IconStyle `color` is intentionally omitted — pin
 * colour comes from the icon image, not KML's tint.
 */
data class KmlResolvedStyle(
    val strokeColor: String? = null,
    val strokeOpacity: Double? = null,
    val strokeWidth: Double? = null,
    val fillColor: String? = null,
    val fillOpacity: Double? = null,
    val iconHref: String? = null,
) {

    /**
     * Later style wins for each non-null field, matching togeojson merging
     * cascaded `styleUrl` then inline `<Style>`.
     */
    fun overlay(inline: KmlResolvedStyle): KmlResolvedStyle = KmlResolvedStyle(
        strokeColor = inline.strokeColor ?: strokeColor,
        strokeOpacity = inline.strokeOpacity ?: strokeOpacity,
        strokeWidth = inline.strokeWidth ?: strokeWidth,
        fillColor = inline.fillColor ?: fillColor,
        fillOpacity = inline.fillOpacity ?: fillOpacity,
        iconHref = inline.iconHref ?: iconHref,
    )

    companion object {
        val Empty = KmlResolvedStyle()
    }
}
