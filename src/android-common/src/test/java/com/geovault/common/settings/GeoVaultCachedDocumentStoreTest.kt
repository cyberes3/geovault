package com.geovault.common.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoVaultCachedDocumentStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun preloadBlocking_exposesPersistedValue() = runBlocking {
        val fileName = "cached_ready.settings"
        val first = cachedStore(fileName)
        first.preloadBlocking()
        first.update { it.copy(enabled = false) }

        val second = cachedStore(fileName)
        second.preloadBlocking()
        assertEquals(false, second.get().enabled)
        assertEquals(false, second.data.value.enabled)
    }

    @Test
    fun awaitReady_completesAfterDiskRead() = runBlocking {
        val store = cachedStore("cached_await.settings")
        store.awaitReady()
        assertEquals(true, store.get().enabled)
    }

    @Test
    fun update_writesThroughCache() = runBlocking {
        val store = cachedStore("cached_update.settings")
        store.preloadBlocking()
        store.update { it.copy(enabled = false) }
        assertEquals(false, store.data.value.enabled)
        assertTrue(
            java.io.File(context.filesDir, "datastore/cached_update.settings").readText()
                .contains("\"enabled\":false"),
        )
    }

    private fun cachedStore(fileName: String): GeoVaultCachedDocumentStore<CachedSampleDocument> {
        return GeoVaultCachedDocumentStore(
            context = context,
            fileName = fileName,
            documentSerializer = CachedSampleDocument.serializer(),
            defaultValue = CachedSampleDocument(),
            currentVersion = 1,
        )
    }
}

@Serializable
private data class CachedSampleDocument(
    val enabled: Boolean = true,
)
