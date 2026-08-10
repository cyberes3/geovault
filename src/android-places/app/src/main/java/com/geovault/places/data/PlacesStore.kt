package com.geovault.places.data

import android.content.Context
import com.geovault.common.settings.GeoVaultPrefsStore
import com.geovault.common.settings.PrefKey
import com.geovault.places.domain.PlacesOfflineStore
import com.geovault.places.model.Feature
import com.geovault.places.model.FeatureCollection
import com.geovault.places.model.OfflineFeature
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Schema v2: offline entries require [OfflineFeature.clientLocalId]. Incompatible v1 queue JSON
 * (index-era entries without ids) is discarded on read — no migration.
 */
class PlacesStore(context: Context) : PlacesOfflineStore {
    private val store = GeoVaultPrefsStore(
        context = context,
        prefsName = PREFS_NAME,
        schemaVersion = SCHEMA_VERSION,
        registeredKeys = ALL_KEYS,
    )
    private val gson = Gson()
    private val lock = Any()
    private val _snapshot = MutableStateFlow(PlacesSnapshot())
    val snapshot: StateFlow<PlacesSnapshot> = _snapshot.asStateFlow()

    fun preloadOnLaunch() {
        store.preloadAllDataBlocking()
        synchronized(lock) { publishLocked() }
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
            store.putBatchBlocking(
                mapOf(
                    KEY_CACHED_PLACES to gson.toJson(collection),
                    KEY_LAST_SYNC_TIME to lastSyncTime,
                ),
            )
            publishLocked()
        }
    }

    fun setCached(collection: FeatureCollection) {
        setCached(collection, System.currentTimeMillis())
    }

    fun setLastSyncTime(value: Long) {
        synchronized(lock) {
            store.putBlocking(KEY_LAST_SYNC_TIME, value)
            publishLocked()
        }
    }

    override fun applyServerFeature(feature: Feature) {
        synchronized(lock) {
            updateCachedFeatureLocked(feature)
            publishLocked()
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
            val list = readOfflineLocked().toMutableList()
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
            writeQueueLocked(list)
            publishLocked()
        }
    }

    override fun removeOffline(clientLocalId: String) {
        if (clientLocalId.isBlank()) return
        synchronized(lock) {
            val filtered = readOfflineLocked().filterNot { it.clientLocalId == clientLocalId }
            writeQueueLocked(filtered)
            publishLocked()
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
            val list = readCachedLocked().filterNot { cached ->
                val cachedId = cached.properties.database_id
                when {
                    targetId != null && cachedId != null -> targetId == cachedId
                    else -> cached.properties.name == targetName &&
                        cached.geometry.coordinates == targetCoords
                }
            }
            store.putBlocking(KEY_CACHED_PLACES, gson.toJson(FeatureCollection(features = list)))
            publishLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            store.clearBlocking()
            publishLocked()
        }
    }

    private fun updateCachedFeatureLocked(feature: Feature) {
        val id = feature.properties.database_id
        val current = readCachedLocked().toMutableList()
        val idx = if (id != null) current.indexOfFirst { it.properties.database_id == id } else -1
        val updated = if (idx >= 0) {
            current.removeAt(idx)
            feature
        } else {
            stampCreatedAtIfBlank(feature)
        }
        current.add(0, updated)
        store.putBlocking(KEY_CACHED_PLACES, gson.toJson(FeatureCollection(features = current)))
    }

    private fun stampCreatedAtIfBlank(feature: Feature): Feature {
        if (!feature.properties.created_at.isNullOrBlank()) return feature
        val now = DATE_FORMAT.format(Date())
        return feature.copy(properties = feature.properties.copy(created_at = now))
    }

    private fun publishLocked() {
        _snapshot.value = PlacesSnapshot(
            cached = readCachedLocked(),
            offline = readOfflineLocked(),
            lastSyncMillis = store.getBlocking(KEY_LAST_SYNC_TIME),
        )
    }

    private fun readCachedLocked(): List<Feature> {
        val json = store.getBlocking(KEY_CACHED_PLACES)
        if (json.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson(json, FeatureCollection::class.java)?.features ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private fun readOfflineLocked(): List<OfflineFeature> {
        val json = store.getBlocking(KEY_OFFLINE_PLACES)
        val parsed = runCatching {
            gson.fromJson(json, Array<OfflineFeature>::class.java)?.toList() ?: emptyList()
        }.getOrElse { emptyList() }
        // Schema v2: drop incompatible pre-clientLocalId queue entries.
        // Gson leaves clientLocalId null for v1 JSON; that null must not call Kotlin isBlank().
        val valid = parsed.filter { entry ->
            val id = entry.clientLocalId as String?
            !id.isNullOrBlank()
        }
        if (valid.size != parsed.size) {
            writeQueueLocked(emptyList())
            return emptyList()
        }
        return valid
    }

    private fun writeQueueLocked(list: List<OfflineFeature>) {
        store.putBlocking(KEY_OFFLINE_PLACES, gson.toJson(list))
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

        private const val PREFS_NAME = "geovault_places_cache"
        /** Bumped for clientLocalId queue shape; old offline JSON is not migrated. */
        private const val SCHEMA_VERSION = 2
        private val KEY_CACHED_PLACES = PrefKey.StringKey("cached_places")
        private val KEY_OFFLINE_PLACES = PrefKey.StringKey("offline_places", "[]")
        private val KEY_LAST_SYNC_TIME = PrefKey.LongKey("last_sync_time")
        private val ALL_KEYS: Set<PrefKey<*>> = setOf(KEY_CACHED_PLACES, KEY_OFFLINE_PLACES, KEY_LAST_SYNC_TIME)
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
