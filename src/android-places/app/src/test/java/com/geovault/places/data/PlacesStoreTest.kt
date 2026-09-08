package com.geovault.places.data

import android.app.Application
import com.geovault.places.model.PendingChange
import com.geovault.places.model.PlaceKey
import com.geovault.places.samplePlace
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class PlacesStoreTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun commitSyncedReplacesLocalKeyInOneMutation() = runTest {
        val store = PlacesStore(context, fileName = uniqueFile())
        store.preloadOnLaunch()
        val localKey = PlaceKey.local("draft-1")
        val draft = samplePlace(
            key = localKey,
            serverId = null,
            name = "Draft",
            pending = PendingChange.Create,
        )
        store.upsert(draft)
        val server = draft.syncedFromServer(44, "2026-04-04")

        store.commitSynced(localKey, server)

        assertEquals(1, store.places().size)
        assertEquals(PlaceKey.server(44), store.places().single().key)
        assertNull(store.find(localKey))
        assertEquals(44, store.find(PlaceKey.server(44))?.serverId)
    }

    @Test
    fun applyServerSnapshotKeepsPendingAndUpdatesLastSync() = runTest {
        val store = PlacesStore(context, fileName = uniqueFile())
        store.preloadOnLaunch()
        val pending = samplePlace(
            name = "Local Edit",
            pending = PendingChange.Update(samplePlace(name = "Baseline").content),
        )
        store.upsert(pending)

        store.applyServerSnapshot(
            listOf(samplePlace(name = "Server Edit"), samplePlace(key = PlaceKey.server(2), serverId = 2, name = "Other")),
            lastSyncMillis = 99L,
        )

        assertEquals("Local Edit", store.find(PlaceKey.server(1))?.content?.name)
        assertEquals("Other", store.find(PlaceKey.server(2))?.content?.name)
        assertEquals(99L, store.lastSyncMillis())
    }

    private fun uniqueFile(): String = "places_store_test_${UUID.randomUUID()}.settings"
}
