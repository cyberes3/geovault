package com.geovault.places.data

import android.content.Context
import com.geovault.common.settings.GeoVaultDocumentStore
import com.geovault.places.domain.PlacesOfflineStore
import com.geovault.places.model.Feature
import com.geovault.places.model.FeatureCollection
import com.geovault.places.model.OfflineFeature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

data class PlacesSnapshot(
    val cached: List<Feature> = emptyList(),
    val offline: List<OfflineFeature> = emptyList(),
    val lastSyncMillis: Long = 0L,
) {
    val displayFeatures: List<Feature>
        get() = PlacesStore.computeDisplayFeatures(cached = cached, offline = offline)
}

/**
 * Single source of truth for cached server places and the offline queue.
 *
 * Offline entries require [OfflineFeature.clientLocalId]. Incompatible queue JSON
 * without ids is discarded on read.
 */
class PlacesStore(context: Context) : PlacesOfflineStore {
    private val store = GeoVaultDocumentStore(
        context = context,
        fileName = PlacesCacheDocument.FILE_NAME,
        documentSerializer = PlacesCacheDocument.serializer(),
        defaultValue = PlacesCacheDocument(),
        currentVersion = PlacesCacheDocument.SCHEMA_VERSION,
        legacyMapper = PlacesCacheDocument::fromLegacy,
    )
    private val lock = Any()
    private val _snapshot = MutableStateFlow(PlacesSnapshot())
    val snapshot: StateFlow<PlacesSnapshot> = _snapshot.asStateFlow()

    fun preloadOnLaunch() {
        val document = runBlocking(Dispatchers.IO) {
            val loaded = store.get()
            val sanitized = loaded.sanitized()
            if (sanitized != loaded) {
                store.update { sanitized }
            }
            sanitized
        }
        synchronized(lock) {
            _snapshot.value = PlacesSnapshot(
                cached = document.cached,
                offline = document.offline,
                lastSyncMillis = document.lastSyncMillis,
            )
        }
    }

    override fun getCachedFeatures(): List<Feature> = snapshot.value.cached

    override fun getOfflineFeatures(): List<OfflineFeature> = snapshot.value.offline

    fun getDisplayFeatures(): List<Feature> = snapshot.value.displayFeatures

    fun getLastSyncTime(): Long = snapshot.value.lastSyncMillis

    fun findOffline(clientLocalId: String): OfflineFeature? {
        if (clientLocalId.isBlank()) return null
        return getOfflineFeatures().firstOrNull { it.clientLocalId == clientLocalId }
    }

    /**
     * Match a display [Feature] to a queued offline entry by database id, else by name+coords
     * for unsynced creates.
     */
    fun findOfflineForFeature(feature: Feature): OfflineFeature? {
        val databaseId = feature.properties.database_id
        if (databaseId != null) {
            getOfflineFeatures().firstOrNull { it.feature.properties.database_id == databaseId }
                ?.let { return it }
        }
        val name = feature.properties.name
        val coords = feature.geometry.coordinates
        return getOfflineFeatures().firstOrNull { offline ->
            offline.feature.properties.database_id == null &&
                offline.feature.properties.name == name &&
                offline.feature.geometry.coordinates == coords
        }
    }

    override fun setCached(collection: FeatureCollection, lastSyncTime: Long) {
        synchronized(lock) {
            _snapshot.value = _snapshot.value.copy(
                cached = collection.features,
                lastSyncMillis = lastSyncTime,
            )
            persistLocked()
        }
    }

    fun setCached(collection: FeatureCollection) {
        setCached(collection, System.currentTimeMillis())
    }

    fun setLastSyncTime(value: Long) {
        synchronized(lock) {
            _snapshot.value = _snapshot.value.copy(lastSyncMillis = value)
            persistLocked()
        }
    }

    override fun applyServerFeature(feature: Feature) {
        synchronized(lock) {
            updateCachedFeatureLocked(feature)
            persistLocked()
        }
    }

    fun updateCachedFeature(feature: Feature) {
        applyServerFeature(feature)
    }

    /**
     * Upsert offline queue entry by [clientLocalId] only. Stamps local [created_at] when blank
     * (display-only; never sent on write).
     */
    fun upsertOffline(
        clientLocalId: String,
        feature: Feature,
        original: Feature?,
    ) {
        require(clientLocalId.isNotBlank()) { "clientLocalId required" }
        synchronized(lock) {
            val list = _snapshot.value.offline.toMutableList()
            val stamped = stampCreatedAtIfBlank(feature)
            val existingIndex = list.indexOfFirst { it.clientLocalId == clientLocalId }
            val item = if (existingIndex >= 0) {
                OfflineFeature(
                    clientLocalId = clientLocalId,
                    feature = stamped,
                    original = list[existingIndex].original ?: original,
                )
            } else {
                OfflineFeature(
                    clientLocalId = clientLocalId,
                    feature = stamped,
                    original = original,
                )
            }
            if (existingIndex >= 0) {
                list[existingIndex] = item
            } else {
                list.add(item)
            }
            _snapshot.value = _snapshot.value.copy(offline = list)
            persistLocked()
        }
    }

    override fun removeOffline(clientLocalId: String) {
        if (clientLocalId.isBlank()) return
        synchronized(lock) {
            val filtered = _snapshot.value.offline.filterNot { it.clientLocalId == clientLocalId }
            _snapshot.value = _snapshot.value.copy(offline = filtered)
            persistLocked()
        }
    }

    fun removeOfflineByFeature(feature: Feature) {
        val match = findOfflineForFeature(feature) ?: return
        removeOffline(match.clientLocalId)
    }

    fun removeCachedFeature(feature: Feature) {
        synchronized(lock) {
            val targetId = feature.properties.database_id
            val targetName = feature.properties.name
            val targetCoords = feature.geometry.coordinates
            val list = _snapshot.value.cached.filterNot { cached ->
                val cachedId = cached.properties.database_id
                when {
                    targetId != null && cachedId != null -> targetId == cachedId
                    else -> cached.properties.name == targetName &&
                        cached.geometry.coordinates == targetCoords
                }
            }
            _snapshot.value = _snapshot.value.copy(cached = list)
            persistLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            _snapshot.value = PlacesSnapshot()
            persistLocked()
        }
    }

    private fun updateCachedFeatureLocked(feature: Feature) {
        val id = feature.properties.database_id
        val current = _snapshot.value.cached.toMutableList()
        val idx = if (id != null) current.indexOfFirst { it.properties.database_id == id } else -1
        val updated = if (idx >= 0) {
            current.removeAt(idx)
            feature
        } else {
            stampCreatedAtIfBlank(feature)
        }
        current.add(0, updated)
        _snapshot.value = _snapshot.value.copy(cached = current)
    }

    private fun stampCreatedAtIfBlank(feature: Feature): Feature {
        if (!feature.properties.created_at.isNullOrBlank()) return feature
        val now = DATE_FORMAT.format(Date())
        return feature.copy(properties = feature.properties.copy(created_at = now))
    }

    private fun persistLocked() {
        val snapshot = _snapshot.value
        runBlocking(Dispatchers.IO) {
            store.update {
                PlacesCacheDocument(
                    cached = snapshot.cached,
                    offline = snapshot.offline,
                    lastSyncMillis = snapshot.lastSyncMillis,
                )
            }
        }
    }

    companion object {
        fun computeDisplayFeatures(
            cached: List<Feature>,
            offline: List<OfflineFeature>,
        ): List<Feature> = buildList {
            val offlineDatabaseIds = offline.mapNotNull { it.feature.properties.database_id }.toSet()
            addAll(offline.map { it.feature })
            addAll(cached.filterNot { it.properties.database_id in offlineDatabaseIds })
        }

        fun retainValidOfflineEntries(parsed: List<OfflineFeature>): List<OfflineFeature> {
            return parsed.filter { entry ->
                val id = entry.clientLocalId as String?
                !id.isNullOrBlank()
            }
        }

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
