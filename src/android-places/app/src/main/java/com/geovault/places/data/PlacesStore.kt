package com.geovault.places.data

import android.content.Context
import com.geovault.common.settings.GeoVaultCachedDocumentStore
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

interface PlacesMutableStore {
    fun places(): List<Place>
    fun find(key: PlaceKey): Place? = places().firstOrNull { it.key == key }
    suspend fun upsert(place: Place)
    suspend fun remove(key: PlaceKey)
    suspend fun commitSynced(oldKey: PlaceKey, serverPlace: Place)
    suspend fun applyServerSnapshot(server: List<Place>, lastSyncMillis: Long = System.currentTimeMillis())
}

class PlacesStore(
    context: Context,
    fileName: String = PlacesCacheDocument.FILE_NAME,
) : PlacesMutableStore {
    private val cached = GeoVaultCachedDocumentStore(
        context = context.applicationContext,
        fileName = fileName,
        documentSerializer = PlacesCacheDocument.serializer(),
        defaultValue = PlacesCacheDocument(),
        currentVersion = PlacesCacheDocument.SCHEMA_VERSION,
        migrations = PlacesCacheDocument.MIGRATIONS,
        legacyMapper = PlacesCacheDocument::fromLegacy,
    )

    val document: StateFlow<PlacesCacheDocument> = cached.data

    override fun places(): List<Place> = document.value.toDomain()

    fun lastSyncMillis(): Long = document.value.lastSyncMillis

    override fun find(key: PlaceKey): Place? = places().firstOrNull { it.key == key }

    fun preloadOnLaunch() {
        cached.preloadBlocking()
    }

    suspend fun awaitReady() {
        cached.awaitReady()
    }

    override suspend fun upsert(place: Place) {
        cached.update { doc ->
            val stamped = stampCreatedAtIfBlank(place)
            doc.copy(places = PlacesRecords.upsert(doc.toDomain(), stamped).map(PlaceRecordDto::from))
        }
    }

    override suspend fun remove(key: PlaceKey) {
        cached.update { doc ->
            doc.copy(places = PlacesRecords.remove(doc.toDomain(), key).map(PlaceRecordDto::from))
        }
    }

    override suspend fun commitSynced(oldKey: PlaceKey, serverPlace: Place) {
        cached.update { doc ->
            doc.copy(
                places = PlacesRecords.commitSynced(doc.toDomain(), oldKey, serverPlace)
                    .map(PlaceRecordDto::from),
            )
        }
    }

    override suspend fun applyServerSnapshot(
        server: List<Place>,
        lastSyncMillis: Long,
    ) {
        cached.update { doc ->
            doc.copy(
                places = PlacesRecords.applyServerSnapshot(doc.toDomain(), server)
                    .map(PlaceRecordDto::from),
                lastSyncMillis = lastSyncMillis,
            )
        }
    }

    fun clearBlocking() {
        runBlocking {
            cached.update { PlacesCacheDocument() }
        }
    }

    private fun stampCreatedAtIfBlank(place: Place): Place {
        if (!place.createdAt.isNullOrBlank()) return place
        return place.copy(createdAt = DATE_FORMAT.format(Date()))
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
