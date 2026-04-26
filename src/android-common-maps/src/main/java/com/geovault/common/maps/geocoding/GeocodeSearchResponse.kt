package com.geovault.common.maps.geocoding

/** Top-level JSON envelope for geocoding search. */
data class GeocodeSearchResponse(
    val data: GeocodeSearchData?,
)

data class GeocodeSearchData(
    val query: String? = null,
    val features: List<GeocodeSearchResult>? = null,
)
