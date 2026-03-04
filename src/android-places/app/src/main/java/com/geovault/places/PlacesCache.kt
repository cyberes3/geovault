package com.geovault.places

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS_NAME = "geovault_prefs"
private const val KEY_CACHED_PLACES = "cached_places"
private const val KEY_OFFLINE_PLACES = "offline_places"
private const val KEY_LAST_SYNC_TIME = "last_sync_time"

private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/**
 * Single source of truth for places data. All reads and writes go through this cache.
 * Map and list always read from here; all flows (sync, offline save, edit-from-map, delete) write here.
 */
class PlacesCache(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Merged list (offline first, then cached) for the map and any single-list consumer. */
    fun getDisplayFeatures(): List<Feature> {
        val result = mutableListOf<Feature>()
        result.addAll(getOfflineFeatures().map { it.feature })
        result.addAll(getCachedFeatures())
        return result
    }

    /** Server snapshot for the list "SAVED PLACES" and sync. */
    fun getCachedFeatures(): List<Feature> {
        val json = prefs.getString(KEY_CACHED_PLACES, null) ?: return emptyList()
        return try {
            val collection = gson.fromJson(json, FeatureCollection::class.java)
            collection.features
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Pending offline for "WAITING TO SYNC" and sync/conflict logic. */
    fun getOfflineFeatures(): List<OfflineFeature> {
        val json = prefs.getString(KEY_OFFLINE_PLACES, "[]") ?: "[]"
        return try {
            gson.fromJson(json, Array<OfflineFeature>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)

    /** After API load. */
    fun setCached(collection: FeatureCollection, lastSyncTime: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_CACHED_PLACES, gson.toJson(collection))
            .putLong(KEY_LAST_SYNC_TIME, lastSyncTime)
            .apply()
    }

    fun setLastSyncTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, time).apply()
    }

    /** Patch one feature (e.g. online save from map). Replaces by database_id or appends if new. */
    fun updateCachedFeature(feature: Feature) {
        val id = feature.properties.database_id
        val current = getCachedFeatures().toMutableList()
        val idx = if (id != null) current.indexOfFirst { it.properties.database_id == id } else -1
        if (idx >= 0) {
            current[idx] = feature
        } else {
            val withDate = if (feature.properties.created_at.isNullOrBlank()) {
                val now = DATE_FORMAT.format(Date())
                feature.copy(properties = feature.properties.copy(created_at = now))
            } else {
                feature
            }
            current.add(0, withDate)
        }
        val collection = FeatureCollection(type = "FeatureCollection", features = current)
        prefs.edit().putString(KEY_CACHED_PLACES, gson.toJson(collection)).commit()
    }

    /** Offline save (same semantics as handleOfflineSave / persistOfflineSave). */
    fun addOrUpdateOffline(feature: Feature, original: Feature?, offlineEditIndex: Int) {
        val list = getOfflineFeatures().toMutableList()
        val indexToReplace = when {
            offlineEditIndex in list.indices -> offlineEditIndex
            feature.properties.database_id != null -> list.indexOfFirst { it.feature.properties.database_id == feature.properties.database_id }.takeIf { it >= 0 }
            else -> null
        }
        val newItem = if (indexToReplace != null) {
            val firstOriginal = list[indexToReplace].original ?: original
            OfflineFeature(feature, firstOriginal)
        } else {
            OfflineFeature(feature, original)
        }
        if (indexToReplace != null) {
            list[indexToReplace] = newItem
        } else {
            list.add(newItem)
        }
        prefs.edit().putString(KEY_OFFLINE_PLACES, gson.toJson(list)).commit()
    }

    fun removeOffline(offlineFeature: OfflineFeature) {
        val list = getOfflineFeatures().toMutableList()
        list.remove(offlineFeature)
        prefs.edit().putString(KEY_OFFLINE_PLACES, gson.toJson(list)).commit()
    }

    /** Clear all places data (e.g. on auth failure reset). */
    fun clear() {
        prefs.edit()
            .remove(KEY_CACHED_PLACES)
            .remove(KEY_OFFLINE_PLACES)
            .remove(KEY_LAST_SYNC_TIME)
            .apply()
    }
}
