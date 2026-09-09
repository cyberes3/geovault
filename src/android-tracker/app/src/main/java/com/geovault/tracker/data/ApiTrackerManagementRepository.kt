package com.geovault.tracker.data

import android.content.Context
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.concurrent.SingleFlightGate
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.net.GeoVaultServerUrl
import com.geovault.common.net.bodyOrThrow
import com.geovault.common.net.successOrThrow
import com.geovault.common.sort.NaturalSort
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.GroupAddTrackRequest
import com.geovault.tracker.GroupCreateRequest
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.HiddenItemsClearRequest
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerApi
import com.geovault.tracker.TrackerBulkGeometryRequest
import com.geovault.tracker.TrackerCheckRequest
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.UserItem
import com.geovault.tracker.toDomainModel
import com.geovault.tracker.toDomainModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ApiTrackerManagementRepository(
    private val appContext: Context,
    private val catalog: CatalogStateStore,
    scope: CoroutineScope,
) : TrackerManagementRepository, GroupManagementRepository, CatalogTransport {
    private companion object {
        const val TAG = "ApiTrackerMgmtRepo"
    }

    private val cacheMutex = ReentrantLock()
    private val apiCache = GeoVaultHttp.CachedApiHolder<TrackerApi>()
    private val readRequestGate = SingleFlightGate<String, Any>(scope)

    override suspend fun loadTrackers(forceRefresh: Boolean): List<Tracker> {
        if (!forceRefresh) {
            catalog.cachedTrackers()?.let { return it }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("trackers") {
            if (!forceRefresh) {
                catalog.cachedTrackers()?.let { return@run it as Any }
            }
            val incoming = executeApiCall { api -> api.getTrackers().execute() }.items.toDomainModels()
            // GEOMETRY-PRESERVATION: the trackers list endpoint returns metadata only
            // (no geometry, point_params, last_point, bbox). Merge each incoming metadata
            // snapshot onto the existing tracker (when present) so geometry fields survive
            // the bulk refresh untouched.
            val merged = cacheMutex.withLock {
                val existingById = (catalog.cachedTrackers() ?: catalog.trackers.value).associateBy { it.id }
                incoming.map { TrackerGeometryMergePolicy.merged(existing = existingById[it.id], incoming = it) }
            }
            val canonical = catalog.canonicalizeTrackers(merged)
            catalog.replaceTrackers(canonical)
            canonical as Any
        } as List<Tracker>
    }

    override suspend fun loadAvailableToAdd(forceRefresh: Boolean): AvailableToAddResponse {
        if (!forceRefresh) {
            val cachedAvailable = catalog.state.value.availableToAdd
            if (cachedAvailable != null) {
                return cachedAvailable
            }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("available-to-add") {
            if (!forceRefresh) {
                val cachedAvailable = catalog.state.value.availableToAdd
                if (cachedAvailable != null) {
                    return@run cachedAvailable as Any
                }
            }
            val response = executeApiCall { api -> api.getAvailableToAdd().execute() }
            catalog.setAvailableToAdd(response)
            response as Any
        } as AvailableToAddResponse
    }

    override suspend fun loadTracker(trackerId: String): Tracker {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("tracker:$trackerId") {
            GeoVaultCaptureLog.d(TAG, "Loading tracker details trackerId=$trackerId")
            try {
                val tracker = executeApiCall { api -> api.getTracker(trackerId).execute() }.toDomainModel()
                GeoVaultCaptureLog.d(
                    TAG,
                    "Loaded tracker details trackerId=$trackerId recentDataWindow=${tracker.catalogSettings.recentDataWindow} hidden=${tracker.catalogSettings.hidden}"
                )
                tracker as Any
            } catch (e: GeoVaultApiFailure) {
                GeoVaultCaptureLog.e(TAG, "Failed loading tracker details trackerId=$trackerId error=$e", e)
                throw e
            }
        } as Tracker
    }

    override suspend fun loadTrackerGeometry(trackerId: String): Tracker {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("tracker-geometry:$trackerId") {
            val incoming = executeApiCall { api -> api.getTrackerGeometry(trackerId).execute() }.toDomainModel()
            val merged = cacheMutex.withLock {
                val existing = catalog.tracker(incoming.id)
                val mergedTracker = TrackerGeometryMergePolicy.merged(existing = existing, incoming = incoming)
                catalog.upsertTracker(mergedTracker)
                mergedTracker
            }
            GeoVaultCaptureLog.i(
                TAG,
                "map_update geometry_status tracker=${merged.id} status=${merged.geometry_status} " +
                    "coords=${merged.geometry?.coordinates?.size ?: 0} params=${merged.point_params?.size ?: 0}"
            )
            merged as Any
        } as Tracker
    }

    override suspend fun loadTrackersGeometry(trackerIds: List<String>): List<Tracker> {
        val normalizedIds = trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) {
            return emptyList()
        }
        val key = "trackers-geometry:${normalizedIds.sorted().joinToString(",")}"
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run(key) {
            val incomingTrackers = executeApiCall {
                api -> api.getTrackersGeometry(TrackerBulkGeometryRequest(tracker_ids = normalizedIds)).execute()
            }.items.toDomainModels()
            val mergedTrackers = cacheMutex.withLock {
                val existingById = (catalog.cachedTrackers() ?: catalog.trackers.value).associateBy { it.id }
                val mergedById = incomingTrackers.associate { incoming ->
                    incoming.id to TrackerGeometryMergePolicy.merged(
                        existing = existingById[incoming.id],
                        incoming = incoming
                    )
                }
                mergedById.values.forEach { catalog.upsertTracker(it) }
                mergedById.values.toList()
            }
            mergedTrackers.forEach { tracker ->
                GeoVaultCaptureLog.i(
                    TAG,
                    "map_update geometry_status tracker=${tracker.id} status=${tracker.geometry_status} " +
                        "coords=${tracker.geometry?.coordinates?.size ?: 0} params=${tracker.point_params?.size ?: 0}"
                )
            }
            mergedTrackers as Any
        } as List<Tracker>
    }

    override suspend fun createTracker(request: TrackerCreateRequest): Tracker {
        val tracker = executeApiCall { api -> api.createTracker(request).execute() }.toDomainModel()
        cacheMutex.withLock {
            catalog.upsertTracker(tracker)
            catalog.setAvailableToAdd(null)
        }
        return tracker
    }

    override suspend fun updateTrackerSettings(
        trackerId: String,
        request: TrackerSettingsRequest,
    ): Tracker {
        GeoVaultCaptureLog.d(TAG, "Updating tracker settings trackerId=$trackerId request=$request")
        try {
            val incoming = executeApiCall { api -> api.postTrackerSettings(trackerId, request).execute() }.toDomainModel()
            val tracker = cacheMutex.withLock {
                val existing = catalog.tracker(trackerId)
                val merged = TrackerGeometryMergePolicy.merged(existing = existing, incoming = incoming)
                catalog.upsertTracker(merged)
                catalog.setAvailableToAdd(null)
                merged
            }
            GeoVaultCaptureLog.d(
                TAG,
                "Updated tracker settings trackerId=$trackerId persistedRecentDataWindow=${tracker.catalogSettings.recentDataWindow} persistedHidden=${tracker.catalogSettings.hidden}"
            )
            return tracker
        } catch (e: GeoVaultApiFailure) {
            GeoVaultCaptureLog.e(TAG, "Failed updating tracker settings trackerId=$trackerId error=$e", e)
            throw e
        }
    }

    override suspend fun deleteTracker(trackerId: String) {
        executeNoBodyCall { api -> api.deleteTracker(trackerId).execute() }
        cacheMutex.withLock {
            catalog.removeTracker(trackerId)
            catalog.setAvailableToAdd(null)
        }
    }

    override suspend fun clearTrackerHistory(trackerId: String) {
        executeNoBodyCall { api -> api.clearTrackerHistory(trackerId).execute() }
        cacheMutex.withLock {
            val existing = catalog.tracker(trackerId)
            if (existing != null) {
                catalog.upsertTracker(
                    existing.copy(
                        geometry = null,
                        point_params = emptyList(),
                        last_point = null,
                        bbox = null,
                        geometry_status = null,
                    ),
                )
            }
            catalog.setAvailableToAdd(null)
        }
        catalog.publishHistoryCleared(trackerId)
    }

    override suspend fun leaveShareWithMe(trackerId: String) {
        executeNoBodyCall { api -> api.leaveShareWithMe(trackerId).execute() }
        cacheMutex.withLock {
            catalog.removeTracker(trackerId)
            catalog.setAvailableToAdd(null)
        }
    }

    override suspend fun unsubscribeTracker(trackerId: String) {
        executeNoBodyCall { api -> api.unsubscribeTracker(trackerId).execute() }
        cacheMutex.withLock {
            catalog.removeTracker(trackerId)
            catalog.setAvailableToAdd(null)
        }
    }

    override suspend fun subscribeTracker(trackerId: String): Tracker {
        val tracker = executeApiCall { api -> api.subscribeTracker(trackerId).execute() }.toDomainModel()
        cacheMutex.withLock {
            catalog.upsertTracker(tracker)
            catalog.setAvailableToAdd(null)
        }
        return tracker
    }

    override suspend fun checkTracker(request: TrackerCheckRequest): Boolean {
        return executeApiCall { api -> api.checkTracker(request).execute() }.valid
    }

    override fun clearSelectedTrackerCaches() {
        apiCache.clear()
        readRequestGate.clear()
        catalog.clearAll()
    }

    override fun getTrackerFromCache(trackerId: String): Tracker? {
        return catalog.tracker(trackerId)
    }

    override suspend fun fetchTrackerKml(trackerId: String): ByteArray {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("tracker-kml:$trackerId") {
            executeApiCall<ResponseBody> { api -> api.getTrackerKml(trackerId).execute() }.bytes() as Any
        } as ByteArray
    }

    override suspend fun loadUsers(): List<UserItem> {
        return executeApiCall { api -> api.getUsers().execute() }.items
    }

    override suspend fun loadMapVisibility(forceRefresh: Boolean): MapVisibilityResponse {
        if (!forceRefresh) {
            catalog.cachedMapVisibility()?.let { return it }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("map-visibility") {
            if (!forceRefresh) {
                catalog.cachedMapVisibility()?.let { return@run it as Any }
            }
            val response = executeApiCall { api -> api.getMapVisibility().execute() }
            catalog.replaceMapVisibility(response)
            response as Any
        } as MapVisibilityResponse
    }

    override suspend fun patchMapVisibility(request: MapVisibilityRequest): MapVisibilityResponse {
        val response = executeApiCall { api -> api.patchMapVisibility(request).execute() }
        catalog.replaceMapVisibility(response)
        return response
    }

    override suspend fun clearHiddenItems(targetTypes: List<String>?) {
        executeNoBodyCall { api ->
            api.clearHiddenItems(HiddenItemsClearRequest(target_types = targetTypes)).execute()
        }
    }

    override suspend fun loadGroups(forceRefresh: Boolean): List<Group> {
        if (!forceRefresh) {
            catalog.cachedGroups()?.let { return it }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("groups") {
            if (!forceRefresh) {
                catalog.cachedGroups()?.let { return@run it as Any }
            }
            val sortedGroups = executeApiCall { api -> api.getGroups().execute() }
                .items
                .sortedWith(NaturalSort.byName(Locale.getDefault()) { it.name })
            catalog.replaceGroups(sortedGroups)
            sortedGroups as Any
        } as List<Group>
    }

    override suspend fun loadGroup(groupId: String): Group {
        val group = executeApiCall { api -> api.getGroup(groupId).execute() }
        catalog.upsertGroup(group)
        return group
    }

    override suspend fun createGroup(name: String): Group {
        val group = executeApiCall { api -> api.createGroup(GroupCreateRequest(name)).execute() }
        cacheMutex.withLock {
            catalog.upsertGroup(group)
            catalog.setAvailableToAdd(null)
        }
        return group
    }

    override suspend fun patchGroup(
        groupId: String,
        request: GroupPatchRequest,
    ): Group {
        val group = executeApiCall { api -> api.patchGroup(groupId, request).execute() }
        cacheMutex.withLock {
            catalog.upsertGroup(group)
            catalog.setAvailableToAdd(null)
        }
        return group
    }

    override suspend fun deleteGroup(groupId: String) {
        executeNoBodyCall { api -> api.deleteGroup(groupId).execute() }
        cacheMutex.withLock {
            catalog.removeGroup(groupId)
            catalog.setAvailableToAdd(null)
        }
    }

    override suspend fun addGroupTrack(groupId: String, trackId: String): Group {
        val group = executeApiCall { api -> api.addGroupTrack(groupId, GroupAddTrackRequest(trackId)).execute() }
        cacheMutex.withLock {
            catalog.upsertGroup(group)
            catalog.setAvailableToAdd(null)
        }
        return group
    }

    override suspend fun removeGroupTrack(groupId: String, trackId: String): Group {
        executeNoBodyCall { api -> api.removeGroupTrack(groupId, trackId).execute() }
        return loadGroup(groupId)
    }

    override suspend fun leaveGroup(groupId: String) {
        executeNoBodyCall { api -> api.leaveGroup(groupId).execute() }
        cacheMutex.withLock {
            catalog.removeGroup(groupId)
            catalog.setAvailableToAdd(null)
        }
    }

    override suspend fun acceptGroupShare(groupId: String): Group {
        val group = executeApiCall { api -> api.acceptGroupShare(groupId).execute() }
        cacheMutex.withLock {
            catalog.upsertGroup(group)
            catalog.setAvailableToAdd(null)
        }
        return group
    }

    private suspend fun <T> executeApiCall(callProvider: (TrackerApi) -> Response<T>): T {
        return withContext(Dispatchers.IO) {
            try {
                callProvider(createApi()).bodyOrThrow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeoVaultApiFailure) {
                throw e
            } catch (e: Exception) {
                GeoVaultCaptureLog.e(TAG, "API call failed with transport exception", e)
                throw GeoVaultApiFailure.fromThrowable(e)
            }
        }
    }

    private suspend fun executeNoBodyCall(
        callProvider: (TrackerApi) -> Response<ResponseBody>
    ) {
        withContext(Dispatchers.IO) {
            try {
                val response = callProvider(createApi())
                response.successOrThrow()
                response.body()?.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeoVaultApiFailure) {
                throw e
            } catch (e: Exception) {
                GeoVaultCaptureLog.e(TAG, "API no-body call failed with transport exception", e)
                throw GeoVaultApiFailure.fromThrowable(e)
            }
        }
    }

    private fun createApi(): TrackerApi {
        val serverUrl = GeoVaultAuthSession.get().getServerUrl()
        if (serverUrl.isBlank()) {
            throw GeoVaultApiFailure(httpCode = null, serverMessage = "Missing server URL")
        }
        val parsed = GeoVaultServerUrl.parse(serverUrl)
            ?: throw GeoVaultApiFailure(httpCode = null, serverMessage = "Missing server URL")
        return GeoVaultHttp.createCachedApi(parsed, TrackerApi::class.java, apiCache)
    }
}
