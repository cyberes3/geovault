package com.geovault.common.maps.render

enum class GeoVaultRenderedMapHitKind {
    Point,
    Overlay,
}

data class GeoVaultRenderedMapHit(
    val id: String,
    /** Map label text from GeoJSON `title`; empty when omitted. Never the internal feature [id]. */
    val title: String,
    /** Picker row text for overlapping points; user-facing only, never the internal [id]. */
    val overlapListLabel: String,
    val kind: GeoVaultRenderedMapHitKind,
    val latitude: Double?,
    val longitude: Double?,
)

internal data class GeoVaultRenderedMapHitCandidate(
    val hit: GeoVaultRenderedMapHit,
    val dedupeKey: String = hit.id,
)

internal sealed class GeoVaultRenderedMapHitResolution {
    data object None : GeoVaultRenderedMapHitResolution()
    data class Single(val hit: GeoVaultRenderedMapHit) : GeoVaultRenderedMapHitResolution()
    data class Multiple(val hits: List<GeoVaultRenderedMapHit>) : GeoVaultRenderedMapHitResolution()
}

internal object GeoVaultRenderedMapHitResolver {
    fun resolve(candidates: List<GeoVaultRenderedMapHitCandidate>): GeoVaultRenderedMapHitResolution {
        val hits = candidates
            .distinctBy { it.dedupeKey }
            .map { it.hit }
        return when (hits.size) {
            0 -> GeoVaultRenderedMapHitResolution.None
            1 -> GeoVaultRenderedMapHitResolution.Single(hits.first())
            else -> GeoVaultRenderedMapHitResolution.Multiple(hits)
        }
    }
}
