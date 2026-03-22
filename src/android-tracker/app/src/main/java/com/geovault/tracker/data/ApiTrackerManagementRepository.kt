package com.geovault.tracker.data

import android.content.Context
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.AppError
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.GroupAddTrackRequest
import com.geovault.tracker.GroupCreateRequest
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerApi
import com.geovault.tracker.TrackerCheckRequest
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.UsersResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response

@Singleton
class ApiTrackerManagementRepository @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val stateStore: TrackerManagementStateStore
) : TrackerManagementRepository, GroupManagementRepository {
    private companion object {
        const val TAG = "ApiTrackerMgmtRepo"
    }

    private val cacheMutex = Mutex()
    private var trackersCache: List<Tracker>? = null
    private var groupsCache: List<Group>? = null
    private var mapVisibilityCache: MapVisibilityResponse? = null

    override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> {
        return cacheMutex.withLock {
            if (!forceRefresh && trackersCache != null) {
                return@withLock RepositoryResult.Success(trackersCache!!)
            }
            val result = executeApiCall { api -> api.getTrackers().execute() }
            if (result is RepositoryResult.Success) {
                trackersCache = result.data
                stateStore.publishTrackers(result.data)
            }
            result
        }
    }

    override suspend fun loadAvailableToAdd(forceRefresh: Boolean): RepositoryResult<AvailableToAddResponse> {
        return executeApiCall { api -> api.getAvailableToAdd().execute() }
    }

    override suspend fun loadTracker(trackerId: String): RepositoryResult<Tracker> {
        val result = executeApiCall { api -> api.getTracker(trackerId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                trackersCache = trackersCache
                    ?.filterNot { it.id == trackerId }
                    .orEmpty()
                    .plus(result.data)
                    .distinctBy { it.id }
                    .sortedBy { it.name.lowercase() }
            }
            stateStore.publishTracker(result.data)
        }
        return result
    }

    override suspend fun loadTrackerGeometry(trackerId: String): RepositoryResult<Tracker> {
        return executeApiCall { api -> api.getTrackerGeometry(trackerId).execute() }
    }

    override suspend fun createTracker(request: TrackerCreateRequest): RepositoryResult<Tracker> {
        val result = executeApiCall { api -> api.createTracker(request).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                trackersCache = trackersCache.orEmpty().plus(result.data).sortedBy { it.name.lowercase() }
            }
            stateStore.publishTracker(result.data)
        }
        return result
    }

    override suspend fun updateTrackerSettings(
        trackerId: String,
        request: TrackerSettingsRequest,
        publishToStore: Boolean
    ): RepositoryResult<Tracker> {
        val result = executeApiCall { api -> api.postTrackerSettings(trackerId, request).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                trackersCache = trackersCache
                    ?.map { if (it.id == trackerId) result.data else it }
                    ?.sortedBy { it.name.lowercase() }
            }
            stateStore.publishTracker(result.data, emitEvent = publishToStore)
        }
        return result
    }

    override suspend fun deleteTracker(trackerId: String): RepositoryResult<Unit> {
        val result = executeNoBodyCall { api -> api.deleteTracker(trackerId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                trackersCache = trackersCache?.filterNot { it.id == trackerId }
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
            }
            TrackerRepository.clearGeometryCache()
            stateStore.publishHistoryCleared(trackerId)
        }
        return result
    }

    override suspend fun leaveShareWithMe(trackerId: String): RepositoryResult<Unit> {
        val result = executeNoBodyCall { api -> api.leaveShareWithMe(trackerId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                trackersCache = trackersCache?.filterNot { it.id == trackerId }
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
            }
            stateStore.deleteTracker(trackerId)
        }
        return result
    }

    override suspend fun subscribeTracker(trackerId: String): RepositoryResult<Tracker> {
        val result = executeApiCall { api -> api.subscribeTracker(trackerId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                trackersCache = trackersCache
                    ?.filterNot { it.id == trackerId }
                    .orEmpty()
                    .plus(result.data)
                    .distinctBy { it.id }
                    .sortedBy { it.name.lowercase() }
            }
            stateStore.publishTracker(result.data)
        }
        return result
    }

    override suspend fun checkTracker(request: TrackerCheckRequest): RepositoryResult<Boolean> {
        val result = executeApiCall { api -> api.checkTracker(request).execute() }
        return when (result) {
            is RepositoryResult.Success -> RepositoryResult.Success(result.data.valid)
            is RepositoryResult.Failure -> result
        }
    }

    override fun clearSelectedTrackerCaches() {
        TrackerRepository.clearSelectedTrackerCaches()
    }

    override fun getTrackerFromCache(trackerId: String): Tracker? {
        return trackersCache?.firstOrNull { it.id == trackerId }
            ?: stateStore.trackers.value.firstOrNull { it.id == trackerId }
    }

    override suspend fun fetchTrackerKml(trackerId: String): RepositoryResult<ByteArray> {
        val result = executeApiCall<ResponseBody> { api -> api.getTrackerKml(trackerId).execute() }
        return when (result) {
            is RepositoryResult.Success -> RepositoryResult.Success(result.data.bytes())
            is RepositoryResult.Failure -> result
        }
    }

    override suspend fun loadUsers(): RepositoryResult<UsersResponse> {
        return executeApiCall { api -> api.getUsers().execute() }
    }

    override suspend fun loadMapVisibility(forceRefresh: Boolean): RepositoryResult<MapVisibilityResponse> {
        return cacheMutex.withLock {
            if (!forceRefresh && mapVisibilityCache != null) {
                return@withLock RepositoryResult.Success(mapVisibilityCache!!)
            }
            val result = executeApiCall { api -> api.getMapVisibility().execute() }
            if (result is RepositoryResult.Success) {
                mapVisibilityCache = result.data
                stateStore.publishMapVisibility(result.data)
            }
            result
        }
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

    override suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> {
        return cacheMutex.withLock {
            if (!forceRefresh && groupsCache != null) {
                return@withLock RepositoryResult.Success(groupsCache!!)
            }
            val result = executeApiCall { api -> api.getGroups().execute() }
            if (result is RepositoryResult.Success) {
                groupsCache = result.data
                stateStore.publishGroups(result.data)
            }
            result
        }
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
                    .sortedBy { it.name.lowercase() }
            }
            stateStore.publishGroup(result.data)
        }
        return result
    }

    override suspend fun createGroup(name: String): RepositoryResult<Group> {
        val result = executeApiCall { api -> api.createGroup(GroupCreateRequest(name)).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                groupsCache = groupsCache.orEmpty().plus(result.data).sortedBy { it.name.lowercase() }
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
                    ?.sortedBy { it.name.lowercase() }
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
            }
            stateStore.deleteGroup(groupId)
        }
        return result
    }

    override suspend fun addGroupTrack(groupId: String, trackId: String): RepositoryResult<Group> {
        val result = executeApiCall { api -> api.addGroupTrack(groupId, GroupAddTrackRequest(trackId)).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                groupsCache = groupsCache?.map { if (it.id == groupId) result.data else it }?.sortedBy { it.name.lowercase() }
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
                        groupsCache = groupsCache?.map { if (it.id == groupId) updated.data else it }?.sortedBy { it.name.lowercase() }
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
            }
            stateStore.deleteGroup(groupId)
        }
        return result
    }

    override suspend fun acceptGroupShare(groupId: String): RepositoryResult<Group> {
        val result = executeApiCall { api -> api.acceptGroupShare(groupId).execute() }
        if (result is RepositoryResult.Success) {
            cacheMutex.withLock {
                groupsCache = groupsCache.orEmpty().filterNot { it.id == groupId }.plus(result.data).sortedBy { it.name.lowercase() }
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
                        RepositoryResult.Failure(AppError.Unknown)
                    }
                } else {
                    RepositoryResult.Failure(mapError(response.code(), response.errorBody()?.string()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "API call failed with transport exception", e)
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
                Log.e(TAG, "API no-body call failed with transport exception", e)
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
        return RetrofitClient.getClient(appContext, baseUrl).create(TrackerApi::class.java)
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
            Log.w(TAG, "Could not parse validation error body", e)
            errorBody.take(200)
        }
    }
}
