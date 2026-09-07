package com.geovault.tracker.data

import android.app.Application
import android.content.Context
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.concurrent.SingleFlightGate
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.net.GeoVaultServerUrl
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
import com.geovault.tracker.TrackerCoordinatesResponse
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.UsersResponse
import com.geovault.tracker.di.TrackerAppServices
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
    private val stateStore: TrackerManagementStateStore,
    scope: CoroutineScope,
) : TrackerManagementRepository, GroupManagementRepository {
    private companion object {
        const val TAG = "ApiTrackerMgmtRepo"
    }

    private val cacheMutex = ReentrantLock()
    @Volatile private var trackersCache: List<Tracker>? = null
    @Volatile private var groupsCache: List<Group>? = null
    @Volatile private var availableToAddCache: AvailableToAddResponse? = null
    @Volatile private var mapVisibilityCache: MapVisibilityResponse? = null
    private val apiCache = GeoVaultHttp.CachedApiHolder<TrackerApi>()
    private val readRequestGate = SingleFlightGate<String, Any>(scope)

    override suspend fun loadTrackers(forceRefresh: Boolean): List<Tracker> {
        if (!forceRefresh) {
            val cachedTrackers = cacheMutex.withLock { trackersCache }
            if (cachedTrackers != null) {
                return cachedTrackers
            }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("trackers") {
            if (!forceRefresh) {
                val cachedTrackers = cacheMutex.withLock { trackersCache }
                if (cachedTrackers != null) {
                    return@run cachedTrackers as Any
                }
            }
            val incoming = executeApiCall { api -> api.getTrackers().execute() }.toDomainModels()
            // GEOMETRY-PRESERVATION: the trackers list endpoint returns metadata only
            // (no geometry, point_params, last_point, bbox). Merge each incoming metadata
            // snapshot onto the existing tracker (when present) so geometry fields survive
            // the bulk refresh untouched.
            val merged = cacheMutex.withLock {
                val existingById = (trackersCache ?: stateStore.trackers.value).associateBy { it.id }
                incoming.map { TrackerGeometryMergePolicy.merged(existing = existingById[it.id], incoming = it) }
            }
            val canonical = stateStore.canonicalizeTrackers(merged)
            cacheMutex.withLock {
                trackersCache = canonical
            }
            stateStore.publishTrackers(canonical)
            canonical as Any
        } as List<Tracker>
    }

    override suspend fun loadAvailableToAdd(forceRefresh: Boolean): AvailableToAddResponse {
        if (!forceRefresh) {
            val cachedAvailable = cacheMutex.withLock { availableToAddCache }
            if (cachedAvailable != null) {
                return cachedAvailable
            }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("available-to-add") {
            if (!forceRefresh) {
                val cachedAvailable = cacheMutex.withLock { availableToAddCache }
                if (cachedAvailable != null) {
                    return@run cachedAvailable as Any
                }
            }
            val response = executeApiCall { api -> api.getAvailableToAdd().execute() }
            cacheMutex.withLock { availableToAddCache = response }
            response as Any
        } as AvailableToAddResponse
    }

    override suspend fun loadTracker(trackerId: String): Tracker {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("tracker:$trackerId") {
            GeoVaultCaptureLog.d(TAG, "Loading tracker details trackerId=$trackerId")
            try {
                val tracker = executeApiCall { api -> api.getTracker(trackerId).execute() }.toDomainModel()
                cacheMutex.withLock {
                    trackersCache = trackersCache
                        ?.filterNot { it.id == trackerId }
                        .orEmpty()
                        .plus(tracker)
                        .distinctBy { it.id }
                        .let(stateStore::canonicalizeTrackers)
                }
                stateStore.publishTracker(tracker)
                GeoVaultCaptureLog.d(
                    TAG,
                    "Loaded tracker details trackerId=$trackerId recentDataWindow=${tracker.settings?.get("recent_data_window")} hidden=${tracker.settings?.get("hidden")}"
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
                val existing = trackersCache?.firstOrNull { it.id == incoming.id }
                    ?: stateStore.trackers.value.firstOrNull { it.id == incoming.id }
                val mergedTracker = TrackerGeometryMergePolicy.merged(existing = existing, incoming = incoming)
                trackersCache = trackersCache
                    ?.filterNot { it.id == mergedTracker.id }
                    .orEmpty()
                    .plus(mergedTracker)
                    .distinctBy { it.id }
                    .let(stateStore::canonicalizeTrackers)
                mergedTracker
            }
            stateStore.publishTracker(merged)
            GeoVaultCaptureLog.i(
                TAG,
                "map_update geometry_status tracker=${merged.id} status=${merged.geometry_status} " +
                    "coords=${merged.geometry?.coordinates?.size ?: 0} params=${merged.point_params?.size ?: 0}"
            )
            merged as Any
        } as Tracker
    }

    override suspend fun loadTrackerCoordinates(trackerId: String): TrackerCoordinatesResponse {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("tracker-coordinates:$trackerId") {
            executeApiCall { api -> api.getTrackerCoordinates(trackerId).execute() }.toDomainModel() as Any
        } as TrackerCoordinatesResponse
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
            }.toDomainModels()
            val mergedTrackers = cacheMutex.withLock {
                val existingById = trackersCache
                    ?.associateBy { it.id }
                    .orEmpty() +
                    stateStore.trackers.value.associateBy { it.id }
                val mergedById = incomingTrackers.associate { incoming ->
                    incoming.id to TrackerGeometryMergePolicy.merged(
                        existing = existingById[incoming.id],
                        incoming = incoming
                    )
                }
                trackersCache = trackersCache
                    ?.filterNot { it.id in mergedById.keys }
                    .orEmpty()
                    .plus(mergedById.values)
                    .distinctBy { it.id }
                    .let(stateStore::canonicalizeTrackers)
                mergedById.values.toList()
            }
            mergedTrackers.forEach { tracker -> stateStore.publishTracker(tracker) }
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
            trackersCache = trackersCache.orEmpty().plus(tracker).let(stateStore::canonicalizeTrackers)
            availableToAddCache = null
        }
        stateStore.publishTracker(tracker)
        return tracker
    }

    override suspend fun updateTrackerSettings(
        trackerId: String,
        request: TrackerSettingsRequest,
        publishToStore: Boolean
    ): Tracker {
        GeoVaultCaptureLog.d(TAG, "Updating tracker settings trackerId=$trackerId request=$request")
        try {
            val incoming = executeApiCall { api -> api.postTrackerSettings(trackerId, request).execute() }.toDomainModel()
            val tracker = cacheMutex.withLock {
                val existing = trackersCache?.firstOrNull { it.id == trackerId }
                    ?: stateStore.trackers.value.firstOrNull { it.id == trackerId }
                val merged = TrackerGeometryMergePolicy.merged(existing = existing, incoming = incoming)
                trackersCache = trackersCache
                    ?.map { if (it.id == trackerId) merged else it }
                    ?.let(stateStore::canonicalizeTrackers)
                availableToAddCache = null
                merged
            }
            stateStore.publishTracker(tracker, emitEvent = publishToStore)
            GeoVaultCaptureLog.d(
                TAG,
                "Updated tracker settings trackerId=$trackerId persistedRecentDataWindow=${tracker.settings?.get("recent_data_window")} persistedHidden=${tracker.settings?.get("hidden")}"
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
            trackersCache = trackersCache?.filterNot { it.id == trackerId }
            availableToAddCache = null
        }
        stateStore.deleteTracker(trackerId)
    }

    override suspend fun clearTrackerHistory(trackerId: String) {
        executeNoBodyCall { api -> api.clearTrackerHistory(trackerId).execute() }
        cacheMutex.withLock {
            trackersCache = null
            availableToAddCache = null
        }
        stateStore.publishHistoryCleared(trackerId)
    }

    override suspend fun leaveShareWithMe(trackerId: String) {
        executeNoBodyCall { api -> api.leaveShareWithMe(trackerId).execute() }
        cacheMutex.withLock {
            trackersCache = trackersCache?.filterNot { it.id == trackerId }
            availableToAddCache = null
        }
        stateStore.deleteTracker(trackerId)
    }

    override suspend fun unsubscribeTracker(trackerId: String) {
        executeNoBodyCall { api -> api.unsubscribeTracker(trackerId).execute() }
        cacheMutex.withLock {
            trackersCache = trackersCache?.filterNot { it.id == trackerId }
            availableToAddCache = null
        }
        stateStore.deleteTracker(trackerId)
    }

    override suspend fun subscribeTracker(trackerId: String): Tracker {
        val tracker = executeApiCall { api -> api.subscribeTracker(trackerId).execute() }.toDomainModel()
        cacheMutex.withLock {
            trackersCache = trackersCache
                ?.filterNot { it.id == trackerId }
                .orEmpty()
                .plus(tracker)
                .distinctBy { it.id }
                .let(stateStore::canonicalizeTrackers)
            availableToAddCache = null
        }
        stateStore.publishTracker(tracker)
        return tracker
    }

    override suspend fun checkTracker(request: TrackerCheckRequest): Boolean {
        return executeApiCall { api -> api.checkTracker(request).execute() }.valid
    }

    override fun clearSelectedTrackerCaches() {
        trackersCache = null
        groupsCache = null
        availableToAddCache = null
        mapVisibilityCache = null
        apiCache.clear()
        readRequestGate.clear()
        stateStore.clearAll()
        TrackerAppServices.from(appContext.applicationContext as Application)
            .trackerHistoryRepository()
            .reset()
    }

    override fun getTrackerFromCache(trackerId: String): Tracker? {
        return cacheMutex.withLock { trackersCache?.firstOrNull { it.id == trackerId } }
            ?: stateStore.trackers.value.firstOrNull { it.id == trackerId }
    }

    override suspend fun fetchTrackerKml(trackerId: String): ByteArray {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("tracker-kml:$trackerId") {
            executeApiCall<ResponseBody> { api -> api.getTrackerKml(trackerId).execute() }.bytes() as Any
        } as ByteArray
    }

    override suspend fun loadUsers(): UsersResponse {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("users") {
            executeApiCall { api -> api.getUsers().execute() } as Any
        } as UsersResponse
    }

    override suspend fun loadMapVisibility(forceRefresh: Boolean): MapVisibilityResponse {
        if (!forceRefresh) {
            val cachedMapVisibility = cacheMutex.withLock { mapVisibilityCache }
            if (cachedMapVisibility != null) {
                return cachedMapVisibility
            }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("map-visibility") {
            if (!forceRefresh) {
                val cachedMapVisibility = cacheMutex.withLock { mapVisibilityCache }
                if (cachedMapVisibility != null) {
                    return@run cachedMapVisibility as Any
                }
            }
            val response = executeApiCall { api -> api.getMapVisibility().execute() }
            cacheMutex.withLock {
                mapVisibilityCache = response
            }
            stateStore.publishMapVisibility(response)
            response as Any
        } as MapVisibilityResponse
    }

    override suspend fun patchMapVisibility(request: MapVisibilityRequest): MapVisibilityResponse {
        val response = executeApiCall { api -> api.patchMapVisibility(request).execute() }
        cacheMutex.withLock {
            mapVisibilityCache = response
        }
        stateStore.publishMapVisibility(response)
        return response
    }

    override suspend fun clearHiddenItems(targetTypes: List<String>?) {
        executeNoBodyCall { api ->
            api.clearHiddenItems(HiddenItemsClearRequest(target_types = targetTypes)).execute()
        }
    }

    override suspend fun loadGroups(forceRefresh: Boolean): List<Group> {
        if (!forceRefresh) {
            val cachedGroups = cacheMutex.withLock { groupsCache }
            if (cachedGroups != null) {
                return cachedGroups
            }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("groups") {
            if (!forceRefresh) {
                val cachedGroups = cacheMutex.withLock { groupsCache }
                if (cachedGroups != null) {
                    return@run cachedGroups as Any
                }
            }
            val sortedGroups = executeApiCall { api -> api.getGroups().execute() }
                .sortedWith(NaturalSort.byName(Locale.getDefault()) { it.name })
            cacheMutex.withLock {
                groupsCache = sortedGroups
            }
            stateStore.publishGroups(sortedGroups)
            sortedGroups as Any
        } as List<Group>
    }

    override suspend fun loadGroup(groupId: String): Group {
        val group = executeApiCall { api -> api.getGroup(groupId).execute() }
        cacheMutex.withLock {
            groupsCache = groupsCache
                ?.filterNot { it.id == groupId }
                .orEmpty()
                .plus(group)
                .distinctBy { it.id }
                .sortedWith(NaturalSort.byName(Locale.getDefault()) { it.name })
        }
        stateStore.publishGroup(group)
        return group
    }

    override suspend fun createGroup(name: String): Group {
        val group = executeApiCall { api -> api.createGroup(GroupCreateRequest(name)).execute() }
        cacheMutex.withLock {
            groupsCache = groupsCache
                .orEmpty()
                .plus(group)
                .sortedWith(NaturalSort.byName(Locale.getDefault()) { it.name })
            availableToAddCache = null
        }
        stateStore.publishGroup(group)
        return group
    }

    override suspend fun patchGroup(
        groupId: String,
        request: GroupPatchRequest,
        publishToStore: Boolean
    ): Group {
        val group = executeApiCall { api -> api.patchGroup(groupId, request).execute() }
        cacheMutex.withLock {
            groupsCache = groupsCache
                ?.map { if (it.id == groupId) group else it }
                ?.sortedWith(NaturalSort.byName(Locale.getDefault()) { it.name })
            availableToAddCache = null
        }
        stateStore.publishGroup(group, emitEvent = publishToStore)
        return group
    }

    override suspend fun deleteGroup(groupId: String) {
        executeNoBodyCall { api -> api.deleteGroup(groupId).execute() }
        cacheMutex.withLock {
            groupsCache = groupsCache?.filterNot { it.id == groupId }
            availableToAddCache = null
        }
        stateStore.deleteGroup(groupId)
    }

    override suspend fun addGroupTrack(groupId: String, trackId: String): Group {
        val group = executeApiCall { api -> api.addGroupTrack(groupId, GroupAddTrackRequest(trackId)).execute() }
        cacheMutex.withLock {
            groupsCache = groupsCache
                ?.map { if (it.id == groupId) group else it }
                ?.sortedWith(NaturalSort.byName(Locale.getDefault()) { it.name })
            availableToAddCache = null
        }
        stateStore.publishGroup(group)
        return group
    }

    override suspend fun removeGroupTrack(groupId: String, trackId: String): Group {
        executeNoBodyCall { api -> api.removeGroupTrack(groupId, trackId).execute() }
        return loadGroup(groupId)
    }

    override suspend fun leaveGroup(groupId: String) {
        executeNoBodyCall { api -> api.leaveGroup(groupId).execute() }
        cacheMutex.withLock {
            groupsCache = groupsCache?.filterNot { it.id == groupId }
            availableToAddCache = null
        }
        stateStore.deleteGroup(groupId)
    }

    override suspend fun acceptGroupShare(groupId: String): Group {
        val group = executeApiCall { api -> api.acceptGroupShare(groupId).execute() }
        cacheMutex.withLock {
            groupsCache = groupsCache
                .orEmpty()
                .filterNot { it.id == groupId }
                .plus(group)
                .sortedWith(NaturalSort.byName(Locale.getDefault()) { it.name })
            availableToAddCache = null
        }
        stateStore.publishGroup(group)
        return group
    }

    private suspend fun <T> executeApiCall(callProvider: (TrackerApi) -> Response<T>): T {
        return withContext(Dispatchers.IO) {
            val api = createApi()
            try {
                val response = callProvider(api)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        body
                    } else {
                        GeoVaultCaptureLog.w(TAG, "Successful API response had no body code=${response.code()}")
                        throw GeoVaultApiFailure(httpCode = response.code(), serverMessage = "Empty response")
                    }
                } else {
                    throw GeoVaultApiFailure.fromRetrofit(response)
                }
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
            val api = createApi()
            try {
                val response = callProvider(api)
                if (response.isSuccessful) {
                    response.body()?.close()
                } else {
                    throw GeoVaultApiFailure.fromRetrofit(response)
                }
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
