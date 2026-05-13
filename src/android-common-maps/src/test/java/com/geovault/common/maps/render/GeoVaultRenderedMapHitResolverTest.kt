package com.geovault.common.maps.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultRenderedMapHitResolverTest {
    @Test
    fun resolve_empty_returnsNone() {
        val resolution = GeoVaultRenderedMapHitResolver.resolve(emptyList())

        assertTrue(resolution is GeoVaultRenderedMapHitResolution.None)
    }

    @Test
    fun resolve_single_returnsSingleHit() {
        val hit = hit(id = "point-1", title = "Point 1")

        val resolution = GeoVaultRenderedMapHitResolver.resolve(
            listOf(GeoVaultRenderedMapHitCandidate(hit)),
        )

        assertTrue(resolution is GeoVaultRenderedMapHitResolution.Single)
        assertEquals(hit, (resolution as GeoVaultRenderedMapHitResolution.Single).hit)
    }

    @Test
    fun resolve_multiple_dedupesByKeyAndKeepsOrder() {
        val first = hit(id = "point-1", title = "First")
        val duplicate = hit(id = "point-1", title = "Duplicate")
        val second = hit(id = "point-2", title = "Second")

        val resolution = GeoVaultRenderedMapHitResolver.resolve(
            listOf(
                GeoVaultRenderedMapHitCandidate(first),
                GeoVaultRenderedMapHitCandidate(duplicate),
                GeoVaultRenderedMapHitCandidate(second),
            ),
        )

        assertTrue(resolution is GeoVaultRenderedMapHitResolution.Multiple)
        assertEquals(
            listOf(first, second),
            (resolution as GeoVaultRenderedMapHitResolution.Multiple).hits,
        )
    }

    private fun hit(id: String, title: String, overlapListLabel: String = title): GeoVaultRenderedMapHit =
        GeoVaultRenderedMapHit(
            id = id,
            title = title,
            overlapListLabel = overlapListLabel,
            kind = GeoVaultRenderedMapHitKind.Point,
            latitude = 1.0,
            longitude = 2.0,
        )
}
