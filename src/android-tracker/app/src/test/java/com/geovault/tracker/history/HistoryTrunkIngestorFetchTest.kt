package com.geovault.tracker.history

import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCatalogSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrunkIngestorFetchTest {
    @Test
    fun fetchFailure_commitsDegradedAndDoesNotStampFreshness() = runBlocking {
        val repository = TrackerHistoryRepository()
        val ingestor = HistoryTrunkIngestor(TrackerHistoryIntentDispatcher(repository), repository)
        val outcome = ingestor.fetchAndCommit(
            trackerIds = listOf("t1"),
            catalogTrackers = listOf(Tracker(id = "t1", name = "One", color = null)),
            loadGeometry = { throw GeoVaultApiFailure(httpCode = null, serverMessage = "network") },
            activeSessionStartMsFor = { null },
        )
        assertTrue(outcome.failure is GeoVaultApiFailure)
        assertTrue(outcome.committedNewDegrade)
        assertEquals(null, repository.lastTrunkFetchedAtMs("t1"))
        assertTrue(
            repository.snapshotFor(TrackerHistoryKey("t1", TrackerHistoryWindow("all")))!!.degradedLocalOnly,
        )
    }

    @Test
    fun fetchSuccess_commitsServerTrunkAndStampsFreshness() = runBlocking {
        val repository = TrackerHistoryRepository()
        val ingestor = HistoryTrunkIngestor(TrackerHistoryIntentDispatcher(repository), repository)
        val outcome = ingestor.fetchAndCommit(
            trackerIds = listOf("t1"),
            catalogTrackers = emptyList(),
            loadGeometry = {
                listOf(
                    Tracker(
                        id = "t1",
                        name = "One",
                        color = null,
                        settings = TrackerCatalogSettings(recentDataWindow = "all"),
                        geometry = GeoJsonLineString(
                            type = "LineString",
                            coordinates = listOf(listOf(1.0, 2.0, 0.0)),
                        ),
                    ),
                )
            },
            activeSessionStartMsFor = { null },
        )
        assertEquals(null, outcome.failure)
        assertFalse(outcome.committedNewDegrade)
        assertTrue(repository.lastTrunkFetchedAtMs("t1") != null)
        val snapshot = repository.snapshotFor(TrackerHistoryKey("t1", TrackerHistoryWindow("all")))!!
        assertFalse(snapshot.degradedLocalOnly)
        assertTrue(snapshot.trunk.isNotEmpty())
    }

    @Test
    fun fetchFailure_keepsAuthoritativeTrunk() = runBlocking {
        val repository = TrackerHistoryRepository()
        val dispatcher = TrackerHistoryIntentDispatcher(repository)
        val window = TrackerHistoryWindow("all")
        dispatcher.dispatch(
            TrackerHistoryIntent.CommitTrunk(
                batch = TrackerHistorySourceBatch(
                    trackerId = "t1",
                    window = window,
                    sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
                    points = listOf(
                        TrackerHistoryPoint(
                            trackerId = "t1",
                            timestampMs = 1L,
                            latitude = 1.0,
                            longitude = 2.0,
                            provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
                        ),
                    ),
                    complete = true,
                ),
                activeSessionStartMs = null,
            ),
        )
        val fetchedAt = repository.lastTrunkFetchedAtMs("t1")
        val ingestor = HistoryTrunkIngestor(dispatcher, repository)
        val outcome = ingestor.fetchAndCommit(
            trackerIds = listOf("t1"),
            catalogTrackers = listOf(Tracker(id = "t1", name = "One", color = null)),
            loadGeometry = { throw GeoVaultApiFailure(httpCode = null, serverMessage = "network") },
            activeSessionStartMsFor = { null },
        )
        assertTrue(outcome.failure is GeoVaultApiFailure)
        assertFalse(outcome.committedNewDegrade)
        assertEquals(fetchedAt, repository.lastTrunkFetchedAtMs("t1"))
        val snapshot = repository.snapshotFor(TrackerHistoryKey("t1", window))!!
        assertFalse(snapshot.degradedLocalOnly)
        assertEquals(1, snapshot.trunk.size)
    }
}
