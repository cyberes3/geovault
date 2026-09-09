package com.geovault.tracker.data

/**
 * HTTP-only catalog edge. [ApiTrackerManagementRepository] implements the network calls;
 * [CatalogStateStore] owns published documents. `loadTracker` is a detail read and must not
 * publish a last-100 stump into the catalog.
 */
interface CatalogTransport : TrackerManagementRepository, GroupManagementRepository
