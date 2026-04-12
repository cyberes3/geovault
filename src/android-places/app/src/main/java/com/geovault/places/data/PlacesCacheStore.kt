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

class PlacesCacheStore(context: Context) : PlacesOfflineStore {
    private val store = GeoVaultPrefsStore(
        context = context,
        prefsName = PREFS_NAME,
        schemaVersion = SCHEMA_VERSION,
        registeredKeys = ALL_KEYS
    )
    private val gson = Gson()

    fun preloadOnLaunch() {
        store.preloadAllDataBlocking()
    }

    override fun getCachedFeatures(): List<Feature> {
        val json = store.getBlocking(KEY_CACHED_PLACES)
        if (json.isBlank()) return emptyList()
        return runCatching {
            val collection = gson.fromJson(json, FeatureCollection::class.java)
            collection?.features ?: emptyList()
        }.getOrElse { emptyList() }
    }

    override fun getOfflineFeatures(): List<OfflineFeature> {
        val json = store.getBlocking(KEY_OFFLINE_PLACES)
        return runCatching {
            val parsed = gson.fromJson(json, Array<OfflineFeature>::class.java)
            parsed?.toList() ?: emptyList()
        }.getOrElse { emptyList() }
    }

    fun getDisplayFeatures(): List<Feature> = buildList {
        addAll(getOfflineFeatures().map { it.feature })
        addAll(getCachedFeatures())
    }

    fun getLastSyncTime(): Long = store.getBlocking(KEY_LAST_SYNC_TIME)

    override fun setCached(collection: FeatureCollection, lastSyncTime: Long) {
        store.putBatchBlocking(mapOf(
            KEY_CACHED_PLACES to gson.toJson(collection),
            KEY_LAST_SYNC_TIME to lastSyncTime
        ))
    }

    fun setCached(collection: FeatureCollection) {
        setCached(collection, System.currentTimeMillis())
    }

    fun setLastSyncTime(value: Long) {
        store.putBlocking(KEY_LAST_SYNC_TIME, value)
    }

    fun updateCachedFeature(feature: Feature) {
        val id = feature.properties.database_id
        val current = getCachedFeatures().toMutableList()
        val idx = if (id != null) current.indexOfFirst { it.properties.database_id == id } else -1
        if (idx >= 0) {
            current[idx] = feature
        } else {
            val now = DATE_FORMAT.format(Date())
            val withDate = if (feature.properties.created_at.isNullOrBlank()) {
                feature.copy(properties = feature.properties.copy(created_at = now))
            } else {
                feature
            }
            current.add(0, withDate)
        }
        store.putBlocking(KEY_CACHED_PLACES, gson.toJson(FeatureCollection(features = current)))
    }

    fun addOrUpdateOffline(feature: Feature, original: Feature?, offlineEditIndex: Int = -1) {
        val list = getOfflineFeatures().toMutableList()
        val replacementIndex = when {
            offlineEditIndex in list.indices -> offlineEditIndex
            feature.properties.database_id != null -> list.indexOfFirst {
                it.feature.properties.database_id == feature.properties.database_id
            }.takeIf { it >= 0 }
            else -> null
        }
        val item = if (replacementIndex != null) {
            OfflineFeature(feature = feature, original = list[replacementIndex].original ?: original)
        } else {
            OfflineFeature(feature = feature, original = original)
        }
        if (replacementIndex != null) {
            list[replacementIndex] = item
        } else {
            list.add(item)
        }
        store.putBlocking(KEY_OFFLINE_PLACES, gson.toJson(list))
    }

    override fun removeOffline(item: OfflineFeature) {
        val list = getOfflineFeatures().toMutableList()
        list.remove(item)
        store.putBlocking(KEY_OFFLINE_PLACES, gson.toJson(list))
    }

    fun removeOfflineByFeature(feature: Feature) {
        val targetId = feature.properties.database_id
        val targetName = feature.properties.name
        val targetCoords = feature.geometry.coordinates
        val list = getOfflineFeatures().toMutableList()
        val filtered = list.filterNot { offline ->
            val offlineFeature = offline.feature
            val offlineId = offlineFeature.properties.database_id
            when {
                targetId != null && offlineId != null -> targetId == offlineId
                else -> offlineFeature.properties.name == targetName &&
                    offlineFeature.geometry.coordinates == targetCoords
            }
        }
        store.putBlocking(KEY_OFFLINE_PLACES, gson.toJson(filtered))
    }

    fun removeCachedFeature(feature: Feature) {
        val targetId = feature.properties.database_id
        val targetName = feature.properties.name
        val targetCoords = feature.geometry.coordinates
        val list = getCachedFeatures()
            .filterNot { cached ->
                val cachedId = cached.properties.database_id
                when {
                    targetId != null && cachedId != null -> targetId == cachedId
                    else -> cached.properties.name == targetName &&
                        cached.geometry.coordinates == targetCoords
                }
            }
        store.putBlocking(KEY_CACHED_PLACES, gson.toJson(FeatureCollection(features = list)))
    }

    fun clear() {
        store.clearBlocking()
    }

    companion object {
        private const val PREFS_NAME = "geovault_places_cache"
        private const val SCHEMA_VERSION = 1
        private val KEY_CACHED_PLACES = PrefKey.StringKey("cached_places")
        private val KEY_OFFLINE_PLACES = PrefKey.StringKey("offline_places", "[]")
        private val KEY_LAST_SYNC_TIME = PrefKey.LongKey("last_sync_time")
        private val ALL_KEYS: Set<PrefKey<*>> = setOf(KEY_CACHED_PLACES, KEY_OFFLINE_PLACES, KEY_LAST_SYNC_TIME)
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
