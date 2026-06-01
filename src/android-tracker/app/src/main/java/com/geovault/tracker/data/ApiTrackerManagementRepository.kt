package com.geovault.tracker.data

import android.app.Application
import android.content.Context
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.NaturalSort
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.AppError
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.GroupAddTrackRequest
import com.geovault.tracker.GroupCreateRequest
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.HiddenItemsClearRequest
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ApiTrackerManagementRepository(
    private val appContext: Context,
    private val stateStore: TrackerManagementStateStore
) : TrackerManagementRepository, GroupManagementRepository {
    private companion object {
        const val TAG = "ApiTrackerMgmtRepo"
    }

    private val cacheMutex = ReentrantLock()
    @Volatile private var trackersCache: List<Tracker>? = null
    @Volatile private var groupsCache: List<Group>? = null
    @Volatile private var availableToAddCache: AvailableToAddResponse? = null
    @Volatile private var mapVisibilityCache: MapVisibilityResponse? = null
    @Volatile private var cachedApiBaseUrl: String? = null
    @Volatile private var cachedApi: TrackerApi? = null
    private val readRequestGate = SingleFlightRequestGate<String, Any>()

    override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> {
        if (!forceRefresh) {
            val cachedTrackers = cacheMutex.withLock { trackersCache }
            if (cachedTrackers != null) {
                return RepositoryResult.Success(cachedTrackers)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("trackers") {
            if (!forceRefresh) {
                val cachedTrackers = cacheMutex.withLock { trackersCache }
                if (cachedTrackers != null) {
                    return@run RepositoryResult.Success(cachedTrackers) as Any
                }
            }
            val networkResult = executeApiCall { api -> api.getTrackers().execute() }
            if (networkResult is RepositoryResult.Success) {
                // GEOMETRY-PRESERVATION: the trackers list endpoint returns metadata only
                // (no geometry, point_params, last_point, bbox). If we blindly replaced the
                // cache after every list refresh — which `runMutationAndRefresh` triggers
                // after every settings edit — we would strip previously merged geometry from
                // the store and the next map render would have to refetch every tracker's
                // points from scratch. Merge each incoming metadata snapshot onto the
                // existing tracker (when present) so the geometry fields survive the bulk
                // refresh untouched.
                val incoming = networkResult.data.toDomainModels()
                val merged = cacheMutex.withLock {
                    val existingById = (trackersCache ?: stateStore.trackers.value).associateBy { it.id }
                    incoming.map { TrackerGeometryMergePolicy.merged(existing = existingById[it.id], incoming = it) }
                }
                val canonical = stateStore.canonicalizeTrackers(merged)
                cacheMutex.withLock {
                    trackersCache = canonical
                }
                stateStore.publishTrackers(canonical)
                return@run RepositoryResult.Success(canonical) as Any
            }
            networkResult as Any
        } as RepositoryResult<List<Tracker>>
    }

    override suspend fun loadAvailableToAdd(forceRefresh: Boolean): RepositoryResult<AvailableToAddResponse> {
        if (!forceRefresh) {
            val cachedAvailable = cacheMutex.withLock { availableToAddCache }
            if (cachedAvailable != null) {
                return RepositoryResult.Success(cachedAvailable)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("available-to-add") {
            if (!forceRefresh) {
                val cachedAvailable = cacheMutex.withLock { availableToAddCache }
                if (cachedAvailable != null) {
                    return@run RepositoryResult.Success(cachedAvailable) as Any
                }
            }
            val networkResult = executeApiCall { api -> api.getAvailableToAdd().execute() }
            if (networkResult is RepositoryResult.Success) {
                cacheMutex.withLock { availableToAddCache = networkResult.data }
            }
            networkResult as Any
        } as RepositoryResult<AvailableToAddResponse>
    }

    override suspend fun loadTracker(trackerId: String): RepositoryResult<Tracker> {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("tracker:$trackerId") {
            GeoVaultCaptureLog.d(TAG, "Loading tracker details trackerId=$trackerId")
            val networkResult = executeApiCall { api -> api.getTracker(trackerId).execute() }
            if (networkResult is RepositoryResult.Success) {
                val tracker = networkResult.data.toDomainModel()
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
                return@run RepositoryResult.Success(tracker) as Any
            } else if (networkResult is RepositoryResult.Failure) {
                GeoVaultCaptureLog.e(TAG, "Failed loading tracker details trackerId=$trackerId error=${networkResult.error}")
            }
            networkResult as Any
        } as RepositoryResult<Tracker>
    }

    override suspend fun loadTrackerGeometry(trackerId: String): RepositoryResult<Tracker> {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("tracker-geometry:$trackerId") {
            when (val networkResult = executeApiCall { api -> api.getTrackerGeometry(trackerId).execute() }) {
                is RepositoryResult.Success -> {
                    val incoming = networkResult.data.toDomainModel()
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
                    RepositoryResult.Success(merged) as Any
                }
                is RepositoryResult.Failure -> networkResult as Any
            }
        } as RepositoryResult<Tracker>
    }

    override suspend fun loadTrackerCoordinates(trackerId: String): RepositoryResult<TrackerCoordinatesResponse> {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("tracker-coordinates:$trackerId") {
            when (val networkResult = executeApiCall { api -> api.getTrackerCoordinates(trackerId).execute() }) {
                is RepositoryResult.Success -> RepositoryResult.Success(networkResult.data.toDomainModel()) as Any
                is RepositoryResult.Failure -> networkResult as Any
            }
        } as RepositoryResult<TrackerCoordinatesResponse>
    }

    override suspend fun loadTrackersGeometry(trackerIds: List<String>): RepositoryResult<List<Tracker>> {
        val normalizedIds = trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) {
            return RepositoryResult.Success(emptyList())
        }
        val key = "trackers-geometry:${normalizedIds.sorted().joinToString(",")}"
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run(key) {
            when (
                val networkResult = executeApiCall {
                    api -> api.getTrackersGeometry(TrackerBulkGeometryRequest(tracker_ids = normalizedIds)).execute()
                }
            ) {
                is RepositoryResult.Success -> {
                    val incomingTrackers = networkResult.data.toDomainModels()
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
                    RepositoryResult.Success(mergedTrackers) as Any
                }
                is RepositoryResult.Failure -> networkResult as Any
            }
        } as RepositoryResult<List<Tracker>>
    }

    override suspend fun createTracker(request: TrackerCreateRequest): RepositoryResult<Tracker> {
        val networkResult = executeApiCall { api -> api.createTracker(request).execute() }
        if (networkResult is RepositoryResult.Success) {
            val tracker = networkResult.data.toDomainModel()
            cacheMutex.withLock {
                trackersCache = trackersCache.orEmpty().plus(tracker).let(stateStore::canonicalizeTrackers)
                availableToAddCache = null
            }
            stateStore.publishTracker(tracker)
            return RepositoryResult.Success(tracker)
        }
        return when (networkResult) {
            is RepositoryResult.Success -> error("Unexpected success branch")
            is RepositoryResult.Failure -> networkResult
        }
    }

    override suspend fun updateTrackerSettings(
        trackerId: String,
        request: TrackerSettingsRequest,
        publishToStore: Boolean
    ): RepositoryResult<Tracker> {
        GeoVaultCaptureLog.d(TAG, "Updating tracker settings trackerId=$trackerId request=$request")
        val networkResult = executeApiCall { api -> api.postTrackerSettings(trackerId, request).execute() }
        if (networkResult is RepositoryResult.Success) {
            val incoming = networkResult.data.toDomainModel()
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
            return RepositoryResult.Success(tracker)
        } else if (networkResult is RepositoryResult.Failure) {
            GeoVaultCaptureLog.e(TAG, "Failed updating tracker settings trackerId=$trackerId error=${networkResult.error}")
        }
        return when (networkResult) {
            is RepositoryResult.Success -> error("Unexpected success branch")
            is RepositoryResult.Failure -> networkResult
        }
    }

    override suspend fun deleteTracker(trackerId: String): RepositoryResult<Unit> {
        val result = executeNoBodyCall { api -> api.deleteTracker(trackerId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                trackersCache = trackersCache?.filterNot { it.id == trackerId }
                availableToAddCache = null
            }
            stateStore.deleteTracker(trackerId)
        }
        return result
    }

    override suspend fun clearTrackerHistory(trackerId: String): RepositoryResult<Unit> {
        val result = executeNoBodyCall { api -> api.clearTrackerHistory(trackerId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                trackersCache = null
                availableToAddCache = null
            }
            stateStore.publishHistoryCleared(trackerId)
        }
        return result
    }

    override suspend fun leaveShareWithMe(trackerId: String): RepositoryResult<Unit> {
        val result = executeNoBodyCall { api -> api.leaveShareWithMe(trackerId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                trackersCache = trackersCache?.filterNot { it.id == trackerId }
                availableToAddCache = null
            }
            stateStore.deleteTracker(trackerId)
        }
        return result
    }

    override suspend fun unsubscribeTracker(trackerId: String): RepositoryResult<Unit> {
        val result = executeNoBodyCall { api -> api.unsubscribeTracker(trackerId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                trackersCache = trackersCache?.filterNot { it.id == trackerId }
                availableToAddCache = null
            }
            stateStore.deleteTracker(trackerId)
        }
        return result
    }

    override suspend fun subscribeTracker(trackerId: String): RepositoryResult<Tracker> {
        val networkResult = executeApiCall { api -> api.subscribeTracker(trackerId).execute() }
        if (networkResult is RepositoryResult.Success) {
            val tracker = networkResult.data.toDomainModel()
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
            return RepositoryResult.Success(tracker)
        }
        return when (networkResult) {
            is RepositoryResult.Success -> error("Unexpected success branch")
            is RepositoryResult.Failure -> networkResult
        }
    }

    override suspend fun checkTracker(request: TrackerCheckRequest): RepositoryResult<Boolean> {
        val result = executeApiCall { api -> api.checkTracker(request).execute() }
        return when (result) {
            is RepositoryResult.Success -> RepositoryResult.Success(result.data.valid)
            is RepositoryResult.Failure -> result
        }
    }

    override fun clearSelectedTrackerCaches() {
        trackersCache = null
        groupsCache = null
        availableToAddCache = null
        mapVisibilityCache = null
        cachedApiBaseUrl = null
        cachedApi = null
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

    override suspend fun fetchTrackerKml(trackerId: String): RepositoryResult<ByteArray> {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("tracker-kml:$trackerId") {
            val result = executeApiCall<ResponseBody> { api -> api.getTrackerKml(trackerId).execute() }
            when (result) {
                is RepositoryResult.Success -> RepositoryResult.Success(result.data.bytes()) as Any
                is RepositoryResult.Failure -> result as Any
            }
        } as RepositoryResult<ByteArray>
    }

    override suspend fun loadUsers(): RepositoryResult<UsersResponse> {
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("users") {
            executeApiCall { api -> api.getUsers().execute() } as Any
        } as RepositoryResult<UsersResponse>
    }

    override suspend fun loadMapVisibility(forceRefresh: Boolean): RepositoryResult<MapVisibilityResponse> {
        if (!forceRefresh) {
            val cachedMapVisibility = cacheMutex.withLock { mapVisibilityCache }
            if (cachedMapVisibility != null) {
                return RepositoryResult.Success(cachedMapVisibility)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("map-visibility") {
            if (!forceRefresh) {
                val cachedMapVisibility = cacheMutex.withLock { mapVisibilityCache }
                if (cachedMapVisibility != null) {
                    return@run RepositoryResult.Success(cachedMapVisibility) as Any
                }
            }
            val result = executeApiCall { api -> api.getMapVisibility().execute() }
            if (result is RepositoryResult.Success) {
                cacheMutex.withLock {
                    mapVisibilityCache = result.data
                }
                stateStore.publishMapVisibility(result.data)
            }
            result as Any
        } as RepositoryResult<MapVisibilityResponse>
    }

    override suspend fun patchMapVisibility(request: MapVisibilityRequest): RepositoryResult<MapVisibilityResponse> {
        val result = executeApiCall { api -> api.patchMapVisibility(request).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                mapVisibilityCache = result.data
            }
            stateStore.publishMapVisibility(result.data)
        }
        return result
    }

    override suspend fun clearHiddenItems(targetTypes: List<String>?): RepositoryResult<Unit> {
        return executeNoBodyCall { api ->
            api.clearHiddenItems(HiddenItemsClearRequest(target_types = targetTypes)).execute()
        }
    }

    override suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> {
        if (!forceRefresh) {
            val cachedGroups = cacheMutex.withLock { groupsCache }
            if (cachedGroups != null) {
                return RepositoryResult.Success(cachedGroups)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return readRequestGate.run("groups") {
            if (!forceRefresh) {
                val cachedGroups = cacheMutex.withLock { groupsCache }
                if (cachedGroups != null) {
                    return@run RepositoryResult.Success(cachedGroups) as Any
                }
            }
            val result = executeApiCall { api -> api.getGroups().execute() }
            if (result is RepositoryResult.Success) {
                val sortedGroups = result.data.sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })
                cacheMutex.withLock {
                    groupsCache = sortedGroups
                }
                stateStore.publishGroups(sortedGroups)
            }
            result as Any
        } as RepositoryResult<List<Group>>
    }

    override suspend fun loadGroup(groupId: String): RepositoryResult<Group> {
        val result = executeApiCall { api -> api.getGroup(groupId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                groupsCache = groupsCache
                    ?.filterNot { it.id == groupId }
                    .orEmpty()
                    .plus(result.data)
                    .distinctBy { it.id }
                    .sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })
            }
            stateStore.publishGroup(result.data)
        }
        return result
    }

    override suspend fun createGroup(name: String): RepositoryResult<Group> {
        val result = executeApiCall { api -> api.createGroup(GroupCreateRequest(name)).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                groupsCache = groupsCache
                    .orEmpty()
                    .plus(result.data)
                    .sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })
                availableToAddCache = null
            }
            stateStore.publishGroup(result.data)
        }
        return result
    }

    override suspend fun patchGroup(
        groupId: String,
        request: GroupPatchRequest,
        publishToStore: Boolean
    ): RepositoryResult<Group> {
        val result = executeApiCall { api -> api.patchGroup(groupId, request).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                groupsCache = groupsCache
                    ?.map { if (it.id == groupId) result.data else it }
                    ?.sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })
                availableToAddCache = null
            }
            stateStore.publishGroup(result.data, emitEvent = publishToStore)
        }
        return result
    }

    override suspend fun deleteGroup(groupId: String): RepositoryResult<Unit> {
        val result = executeNoBodyCall { api -> api.deleteGroup(groupId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                groupsCache = groupsCache?.filterNot { it.id == groupId }
                availableToAddCache = null
            }
            stateStore.deleteGroup(groupId)
        }
        return result
    }

    override suspend fun addGroupTrack(groupId: String, trackId: String): RepositoryResult<Group> {
        val result = executeApiCall { api -> api.addGroupTrack(groupId, GroupAddTrackRequest(trackId)).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                groupsCache = groupsCache
                    ?.map { if (it.id == groupId) result.data else it }
                    ?.sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })
                availableToAddCache = null
            }
            stateStore.publishGroup(result.data)
        }
        return result
    }

    override suspend fun removeGroupTrack(groupId: String, trackId: String): RepositoryResult<Group> {
        val result = executeNoBodyCall { api -> api.removeGroupTrack(groupId, trackId).execute() }
        if (result is RepositoryResult.Success) {
            return loadGroup(groupId).also { updated ->
                if (updated is RepositoryResult.Success) {
                    cacheMutex.withLock {
                        groupsCache = groupsCache
                            ?.map { if (it.id == groupId) updated.data else it }
                            ?.sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })
                        availableToAddCache = null
                    }
                    stateStore.publishGroup(updated.data)
                }
            }
        }
        return RepositoryResult.Failure((result as RepositoryResult.Failure).error)
    }

    override suspend fun leaveGroup(groupId: String): RepositoryResult<Unit> {
        val result = executeNoBodyCall { api -> api.leaveGroup(groupId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                groupsCache = groupsCache?.filterNot { it.id == groupId }
                availableToAddCache = null
            }
            stateStore.deleteGroup(groupId)
        }
        return result
    }

    override suspend fun acceptGroupShare(groupId: String): RepositoryResult<Group> {
        val result = executeApiCall { api -> api.acceptGroupShare(groupId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                groupsCache = groupsCache
                    .orEmpty()
                    .filterNot { it.id == groupId }
                    .plus(result.data)
                    .sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })
                availableToAddCache = null
            }
            stateStore.publishGroup(result.data)
        }
        return result
    }

    private suspend fun <T> executeApiCall(callProvider: (TrackerApi) -> Response<T>): RepositoryResult<T> {
        return withContext(Dispatchers.IO) {
            val api = createApi() ?: return@withContext RepositoryResult.Failure(AppError.MissingServerUrl)
            try {
                val response = callProvider(api)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        RepositoryResult.Success(body)
                    } else {
                        GeoVaultCaptureLog.w(TAG, "Successful API response had no body code=${response.code()}")
                        RepositoryResult.Failure(AppError.Unknown)
                    }
                } else {
                    RepositoryResult.Failure(mapError(response.code(), response.errorBody()?.string()))
                }
            } catch (e: Exception) {
                GeoVaultCaptureLog.e(TAG, "API call failed with transport exception", e)
                RepositoryResult.Failure(AppError.Network)
            }
        }
    }

    private suspend fun executeNoBodyCall(
        callProvider: (TrackerApi) -> Response<ResponseBody>
    ): RepositoryResult<Unit> {
        return withContext(Dispatchers.IO) {
            val api = createApi() ?: return@withContext RepositoryResult.Failure(AppError.MissingServerUrl)
            try {
                val response = callProvider(api)
                if (response.isSuccessful) {
                    response.body()?.close()
                    RepositoryResult.Success(Unit)
                } else {
                    RepositoryResult.Failure(mapError(response.code(), response.errorBody()?.string()))
                }
            } catch (e: Exception) {
                GeoVaultCaptureLog.e(TAG, "API no-body call failed with transport exception", e)
                RepositoryResult.Failure(AppError.Network)
            }
        }
    }

    private fun createApi(): TrackerApi? {
        val serverUrl = GeovaultAuthManager.getServerUrl(appContext)
        if (serverUrl.isBlank()) {
            return null
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val existingApi = cachedApi
        if (existingApi != null && cachedApiBaseUrl == baseUrl) {
            return existingApi
        }
        return synchronized(this) {
            val synchronizedExistingApi = cachedApi
            if (synchronizedExistingApi != null && cachedApiBaseUrl == baseUrl) {
                synchronizedExistingApi
            } else {
                RetrofitClient.getClientOmitNulls(appContext, baseUrl).create(TrackerApi::class.java).also { createdApi ->
                    cachedApiBaseUrl = baseUrl
                    cachedApi = createdApi
                }
            }
        }
    }

    private fun mapError(code: Int, errorBody: String?): AppError {
        return when (code) {
            401 -> AppError.Unauthorized
            404 -> AppError.NotFound
            400 -> AppError.Validation(extractValidationMessage(errorBody))
            in 500..599 -> AppError.Server(code)
            else -> AppError.Server(code)
        }
    }

    private fun extractValidationMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) {
            return null
        }
        return try {
            val json = JSONObject(errorBody)
            json.optString("detail", json.optString("error", json.optString("name", errorBody.take(200))))
        } catch (e: Exception) {
            GeoVaultCaptureLog.w(TAG, "Could not parse validation error body", e)
            errorBody.take(200)
        }
    }
}
