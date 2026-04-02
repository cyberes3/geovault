package com.geovault.places.data

import android.content.Context
import com.geovault.places.model.Feature
import com.geovault.places.model.FeatureCollection
import com.geovault.places.model.OfflineFeature
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlacesCacheStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getCachedFeatures(): List<Feature> {
        val json = prefs.getString(KEY_CACHED_PLACES, null) ?: return emptyList()
        return runCatching { gson.fromJson(json, FeatureCollection::class.java).features }.getOrElse { emptyList() }
    }

    fun getOfflineFeatures(): List<OfflineFeature> {
        val json = prefs.getString(KEY_OFFLINE_PLACES, "[]") ?: "[]"
        return runCatching { gson.fromJson(json, Array<OfflineFeature>::class.java).toList() }.getOrElse { emptyList() }
    }

    fun getDisplayFeatures(): List<Feature> = buildList {
        addAll(getOfflineFeatures().map { it.feature })
        addAll(getCachedFeatures())
    }

    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)

    fun setCached(collection: FeatureCollection, lastSyncTime: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_CACHED_PLACES, gson.toJson(collection))
            .putLong(KEY_LAST_SYNC_TIME, lastSyncTime)
            .apply()
    }

    fun setLastSyncTime(value: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()
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
        prefs.edit().putString(KEY_CACHED_PLACES, gson.toJson(FeatureCollection(features = current))).commit()
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
        prefs.edit().putString(KEY_OFFLINE_PLACES, gson.toJson(list)).commit()
    }

    fun removeOffline(item: OfflineFeature) {
        val list = getOfflineFeatures().toMutableList()
        list.remove(item)
        prefs.edit().putString(KEY_OFFLINE_PLACES, gson.toJson(list)).commit()
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
        prefs.edit().putString(KEY_OFFLINE_PLACES, gson.toJson(filtered)).commit()
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
        prefs.edit().putString(KEY_CACHED_PLACES, gson.toJson(FeatureCollection(features = list))).commit()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_CACHED_PLACES)
            .remove(KEY_OFFLINE_PLACES)
            .remove(KEY_LAST_SYNC_TIME)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "geovault_prefs"
        private const val KEY_CACHED_PLACES = "cached_places"
        private const val KEY_OFFLINE_PLACES = "offline_places"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
