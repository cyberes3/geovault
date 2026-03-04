package com.geovault.places

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.MotionEvent
import android.graphics.Rect
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.gson.Gson
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.Serializable
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_ID_FROM_MAP = "selected_id_from_map"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var refreshOverlay: View
    private lateinit var syncSpinner: android.widget.ImageView
    private lateinit var syncText: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchClear: View
    private lateinit var searchDivider: View
    private lateinit var fabAdd: View
    private lateinit var fabMap: View
    private lateinit var syncErrorBar: View
    private lateinit var syncErrorBarText: TextView
    private lateinit var adapter: PlacesAdapter
    private val placesList = mutableListOf<Feature>()
    private val offlinePlacesList = mutableListOf<OfflineFeature>()
    private var refreshCall: Call<FeatureCollection>? = null
    private var initialLoadDone = false
    private var searchQuery: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rotationHelper: RotationHelper
    private var lastSyncTime: Long = 0
    private val timeoutRunnable = Runnable {
        if (swipeRefresh.isRefreshing) {
            cancelRefresh("Refresh timed out (10s)")
        }
    }

    private val executor = Executors.newSingleThreadExecutor()

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    }

    private val mapLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedId = result.data?.getIntExtra("selected_id", -1) ?: -1
            if (selectedId != -1) {
                adapter.selectedId = selectedId
                val index = placesList.indexOfFirst { it.properties.database_id == selectedId }
                if (index != -1) {
                    adapter.notifyDataSetChanged()
                    
                    // Center the item
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    // Put item at 1/3 of the screen height effectively centering it with context
                    val offset = recyclerView.height / 3
                    layoutManager.scrollToPositionWithOffset(index, offset)
                }
            }
        }
    }

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val offlineFeature = result.data?.getParcelableExtra("offline_feature", Feature::class.java)
            if (offlineFeature != null) {
                val originalFeature = result.data?.getParcelableExtra("original_feature", Feature::class.java)
                val offlineEditIndex = result.data?.getIntExtra("offline_edit_index", -1) ?: -1
                handleOfflineSave(offlineFeature, originalFeature, offlineEditIndex)
            } else {
                loadPlaces()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Handle window insets
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerView = findViewById<View>(R.id.headerLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerView.updatePadding(top = insets.top + 20)
            // Apply bottom padding to avoid navigation bar overlap
            view.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            safeNoAnimation()
        }

        recyclerView = findViewById(R.id.placesRecyclerView)
        emptyState = findViewById(R.id.emptyState)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.surface)
        swipeRefresh.setColorSchemeResources(R.color.primary_blue)
        refreshOverlay = findViewById(R.id.refreshOverlay)
        syncSpinner = findViewById(R.id.syncSpinner)
        syncText = findViewById(R.id.syncText)
        searchInput = findViewById(R.id.searchInput)
        searchClear = findViewById(R.id.searchClear)
        searchDivider = findViewById(R.id.searchDivider)
        fabAdd = findViewById(R.id.fab_add)
        fabMap = findViewById(R.id.fab_map)
        syncErrorBar = findViewById(R.id.syncErrorBar)
        syncErrorBarText = findViewById(R.id.syncErrorBarText)
        syncErrorBar.setOnClickListener { syncErrorBar.visibility = View.GONE }
        rotationHelper = RotationHelper(syncSpinner)

        adapter = PlacesAdapter(
            placesList, 
            offlinePlacesList.map { it.feature },
            offlinePlacesList,
            { feature: Feature ->
                navigateToPlace(feature)
            }, 
            { feature: Feature ->
                val intent = Intent(this, PlaceEditActivity::class.java)
                intent.putExtra("feature", feature)
                editLauncher.launch(intent)
                safeNoAnimation()
            },
            { offlineFeature: OfflineFeature ->
                val index = offlinePlacesList.indexOf(offlineFeature)
                val intent = Intent(this, PlaceEditActivity::class.java)
                intent.putExtra("feature", offlineFeature.feature)
                intent.putExtra("original_feature", offlineFeature.original)
                intent.putExtra("is_offline_edit", true)
                intent.putExtra("offline_edit_index", index)
                editLauncher.launch(intent)
                safeNoAnimation()
            },
            { feature: Feature ->
                deletePlace(feature)
            },
            { offlineFeature: OfflineFeature ->
                revertOfflineChanges(offlineFeature)
            },
            { placeName: String, description: String ->
                val intent = DescriptionViewActivity.intent(this, placeName, description)
                startActivity(intent)
                safeNoAnimation()
            },
            { feature: Feature ->
                openMapToPlace(feature)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val canScrollUp = recyclerView.canScrollVertically(-1)
                searchDivider.visibility = if (canScrollUp) View.VISIBLE else View.INVISIBLE
            }
        })

        searchInput.addTextChangedListener { text ->
            searchQuery = text?.toString()?.trim() ?: ""
            updateList()
            searchClear.visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
        }

        searchClear.setOnClickListener {
            searchInput.text.clear()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        }

        swipeRefresh.setOnRefreshListener {
            loadPlaces()
        }

        fabAdd.setOnClickListener {
            val intent = Intent(this, PlaceEditActivity::class.java)
            editLauncher.launch(intent)
            safeNoAnimation()
        }

        fabMap.setOnClickListener {
            val intent = Intent(this, MapActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putParcelableArrayListExtra("features", ArrayList<Feature>().apply {
                    addAll(offlinePlacesList.map { it.feature })
                    addAll(placesList)
                })
            }
            mapLauncher.launch(intent)
            safeNoAnimation()
        }

        // Tap overlay to cancel (works for initial sync and for offline-item sync)
        refreshOverlay.setOnClickListener {
            cancelRefresh(
                if (swipeRefresh.isRefreshing) "Cancelled - using cached data"
                else "Syncing cancelled"
            )
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (intent?.getBooleanExtra(EXTRA_SHOW_EXPORT_SAVED_MESSAGE, false) == true) {
            intent?.removeExtra(EXTRA_SHOW_EXPORT_SAVED_MESSAGE)
            Toast.makeText(this, getString(R.string.offline_data_saved_to_files), Toast.LENGTH_LONG).show()
        }
        val selectedIdFromMap = intent?.getIntExtra(EXTRA_SELECTED_ID_FROM_MAP, -1) ?: -1
        if (selectedIdFromMap != -1) {
            intent?.removeExtra(EXTRA_SELECTED_ID_FROM_MAP)
            adapter.selectedId = selectedIdFromMap
            val index = placesList.indexOfFirst { it.properties.database_id == selectedIdFromMap }
            if (index != -1) {
                adapter.notifyDataSetChanged()
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                if (layoutManager != null) {
                    val offset = recyclerView.height / 3
                    layoutManager.scrollToPositionWithOffset(index, offset)
                }
            }
        }
        checkConfigAndLoad()
    }

    override fun onPause() {
        super.onPause()
        clearSelection()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun clearSelection() {
        if (adapter.selectedId != null) {
            adapter.selectedId = null
            adapter.notifyDataSetChanged()
        }
    }

    private fun checkConfigAndLoad() {
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        if (serverUrl.isEmpty() || !GeovaultAuthManager.isLoggedIn(this)) {
            startActivity(Intent(this, SettingsActivity::class.java))
            safeNoAnimation()
        } else {
            // Always load from cache/offline for immediate display when resuming
            loadOfflinePlaces()
            loadFromCache()
            // Sync from server only on first load; refresh token first so first request doesn't get 401
            if (!initialLoadDone) {
                initialLoadDone = true
                executor.execute {
                    val token = GeovaultAuthManager.getValidAccessToken(this@MainActivity)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (!token.isNullOrBlank()) {
                            handler.post { loadPlaces() }
                        } else {
                            showSnackbar("Session expired. Sign in again in Settings.")
                            updateList()
                        }
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    fun safeNoAnimation() {
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    private fun cancelRefresh(message: String) {
        refreshCall?.cancel()
        refreshCall = null
        handler.removeCallbacks(timeoutRunnable)
        hideSyncOverlayAndReset()
        showSnackbar(message)
        // If we cancelled, ensure we at least show what's in cache if lists are empty
        if (placesList.isEmpty() && offlinePlacesList.isEmpty()) {
            loadOfflinePlaces()
            loadFromCache()
        }
    }

    private fun hideSyncOverlayAndReset() {
        refreshOverlay.visibility = View.GONE
        stopSyncAnimation()
        swipeRefresh.isRefreshing = false
        fabAdd.isEnabled = true
        fabMap.isEnabled = true
    }

    private fun loadFromCache() {
        val cachedJson = prefs.getString("cached_places", null)
        lastSyncTime = prefs.getLong("last_sync_time", 0L)
        
        if (cachedJson != null) {
            try {
                val collection = Gson().fromJson(cachedJson, FeatureCollection::class.java)
                placesList.clear()
                placesList.addAll(collection.features)
                updateLastSyncUI()
                updateList()
            } catch (e: Exception) {}
        }
    }

    private fun loadOfflinePlaces() {
        val json = prefs.getString("offline_places", "[]") ?: "[]"
        try {
            val items = Gson().fromJson(json, Array<OfflineFeature>::class.java).toList()
            offlinePlacesList.clear()
            offlinePlacesList.addAll(items)
        } catch (e: Exception) {}
    }

    private fun saveOfflinePlace(feature: Feature, original: Feature? = null) {
        // If we already have an offline edit for this ID, update it instead of adding a new one
        val existingIndex = if (feature.properties.database_id != null) {
            offlinePlacesList.indexOfFirst { it.feature.properties.database_id == feature.properties.database_id }
        } else -1

        val newOfflineFeature = OfflineFeature(feature, original)
        if (existingIndex != -1) {
            // Keep the very first 'original' as the baseline for conflict detection
            val firstOriginal = offlinePlacesList[existingIndex].original ?: original
            offlinePlacesList[existingIndex] = OfflineFeature(feature, firstOriginal)
        } else {
            offlinePlacesList.add(newOfflineFeature)
        }
        
        val json = Gson().toJson(offlinePlacesList)
        prefs.edit().putString("offline_places", json).apply()
        updateList()
        Toast.makeText(this, "Saved offline. Pull to sync.", Toast.LENGTH_LONG).show()
    }

    private fun removeOfflinePlace(offlineFeature: OfflineFeature) {
        offlinePlacesList.remove(offlineFeature)
        val json = Gson().toJson(offlinePlacesList)
        prefs.edit().putString("offline_places", json).apply()
    }

    private fun saveToCache(collection: FeatureCollection) {
        val json = Gson().toJson(collection)
        prefs.edit()
            .putString("cached_places", json)
            .putLong("last_sync_time", lastSyncTime)
            .apply()
    }

    private fun updateList() {
        val query = searchQuery

        val filteredOfflineFeatures: List<OfflineFeature>
        val filteredPlaces: List<Feature>

        if (query.isBlank()) {
            filteredOfflineFeatures = offlinePlacesList
            filteredPlaces = placesList
        } else {
            fun matches(feature: Feature): Boolean {
                val name = feature.properties.name ?: ""
                val desc = feature.properties.description ?: ""
                return name.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)
            }

            filteredOfflineFeatures = offlinePlacesList.filter { matches(it.feature) }
            filteredPlaces = placesList.filter { matches(it) }
        }

        adapter.updateData(
            filteredPlaces,
            filteredOfflineFeatures.map { it.feature },
            filteredOfflineFeatures
        )
        
        if (filteredPlaces.isEmpty() && filteredOfflineFeatures.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun loadPlaces(syncOffline: Boolean = true) {
        clearSelection()
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"

        refreshCall?.cancel()
        val api = RetrofitClient.getClient(this, baseUrl).create(GeovaultApi::class.java)

        syncSpinner.visibility = View.VISIBLE
        refreshOverlay.visibility = View.VISIBLE
        startSyncAnimation()
        swipeRefresh.isRefreshing = true
        syncText.text = "Syncing..."
        fabAdd.isEnabled = false
        fabMap.isEnabled = false

        // 10s timeout
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 10000)

        refreshCall = api.getPlaces()
        refreshCall?.enqueue(object : Callback<FeatureCollection> {
            override fun onResponse(call: Call<FeatureCollection>, response: Response<FeatureCollection>) {
                if (call.isCanceled) return

                handler.removeCallbacks(timeoutRunnable)
                refreshCall = null

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        lastSyncTime = System.currentTimeMillis()
                        saveToCache(body)
                        placesList.clear()
                        placesList.addAll(body.features)
                        updateLastSyncUI()
                        updateList()
                    }
                    // If we have offline items, keep overlay up and run offline sync (no vanish/reappear)
                    if (syncOffline && offlinePlacesList.isNotEmpty()) {
                        syncText.text = "Syncing ${offlinePlacesList.size} offline ${if (offlinePlacesList.size == 1) "item" else "items"}..."
                        runPendingSync(serverUrl)
                    } else {
                        hideSyncOverlayAndReset()
                    }
                } else {
                    hideSyncOverlayAndReset()
                    if (response.code() == 401) {
                        exportThenResetOnAuthFailure(this@MainActivity)
                        return
                    }
                    showSnackbar("Server Error: ${response.code()}")
                    loadFromCache() // Use cache on error
                }
            }

            override fun onFailure(call: Call<FeatureCollection>, t: Throwable) {
                if (call.isCanceled) return

                handler.removeCallbacks(timeoutRunnable)
                refreshCall = null
                hideSyncOverlayAndReset()

                showSnackbar("Network failed: ${t.message}")
                loadOfflinePlaces()
                loadFromCache() // Use cache on failure
                updateList()
            }
        })
    }

    private fun startSyncAnimation() {
        rotationHelper.start()
    }

    private fun stopSyncAnimation() {
        rotationHelper.stop(hide = false)
    }

    private fun updateLastSyncUI() {
        if (lastSyncTime == 0L) return
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val timeStr = sdf.format(java.util.Date(lastSyncTime))
        // We'll add this view to activity_main.xml next
        findViewById<TextView>(R.id.lastSyncText)?.text = "Last synced: $timeStr"
    }

    private fun runPendingSync(serverUrl: String) {
        syncOfflinePlaces()
        NavigationHelper.flushPendingNavigations(this, serverUrl)
    }

    private fun syncOfflinePlaces() {
        if (offlinePlacesList.isEmpty()) return

        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(this, baseUrl).create(GeovaultApi::class.java)

        // Show syncing status in overlay
        syncSpinner.visibility = View.VISIBLE
        refreshOverlay.visibility = View.VISIBLE
        startSyncAnimation()
        syncText.text = "Syncing ${offlinePlacesList.size} offline ${if (offlinePlacesList.size == 1) "item" else "items"}..."

        val toSync = ArrayList(offlinePlacesList)
        var syncedCount = 0
        var successCount = 0
        
        fun checkSyncComplete(success: Boolean = false) {
            syncedCount++
            if (success) successCount++
            
            if (syncedCount >= toSync.size) {
                if (successCount > 0 || offlinePlacesList.isEmpty()) {
                    loadPlaces(syncOffline = false)
                } else {
                    hideSyncOverlayAndReset()
                }
            }
        }

        for (offlineItem in toSync) {
            val feature = offlineItem.feature
            val original = offlineItem.original
            val dbId = feature.properties.database_id

            if (dbId != null && original != null) {
                // UPDATE scenario: fetch current server state and check for conflicts before updating
                api.getPlace(dbId).enqueue(object : Callback<Feature> {
                    override fun onResponse(call: Call<Feature>, response: Response<Feature>) {
                        val currentServer = response.body()
                        if (!response.isSuccessful || currentServer == null) {
                            // Could not fetch current state — leave offline, do not overwrite
                            showSnackbar(syncErrorMessage(response))
                            checkSyncComplete(success = false)
                            return
                        }
                        if (isChanged(original, currentServer)) {
                            // CONFLICT: server changed since we went offline — save our version as new, keep server as-is
                            val conflictedFeature = feature.copy(
                                properties = feature.properties.copy(
                                    database_id = null,
                                    name = (feature.properties.name ?: "Place") + " - Conflicted"
                                )
                            )
                            api.createPlace(conflictedFeature).enqueue(object : Callback<Feature> {
                                override fun onResponse(createCall: Call<Feature>, createResponse: Response<Feature>) {
                                    if (createResponse.isSuccessful) {
                                        removeOfflinePlace(offlineItem)
                                        updateList()
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Conflict detected: '${feature.properties.name}' saved as new item",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        checkSyncComplete(success = true)
                                    } else {
                                        showSnackbar(syncErrorMessage(createResponse))
                                        checkSyncComplete(success = false)
                                    }
                                }
                                override fun onFailure(createCall: Call<Feature>, t: Throwable) {
                                    showSnackbar("Sync failed: ${t.message ?: "Network error"}")
                                    checkSyncComplete(success = false)
                                }
                            })
                            return
                        }
                        // No conflict: proceed with update
                        api.updatePlace(dbId, feature).enqueue(object : Callback<Feature> {
                            override fun onResponse(updateCall: Call<Feature>, updateResponse: Response<Feature>) {
                                if (updateResponse.isSuccessful) {
                                    removeOfflinePlace(offlineItem)
                                    updateList()
                                    checkSyncComplete(success = true)
                                } else {
                                    showSnackbar(syncErrorMessage(updateResponse))
                                    checkSyncComplete(success = false)
                                }
                            }
                            override fun onFailure(updateCall: Call<Feature>, t: Throwable) {
                                showSnackbar("Sync failed: ${t.message ?: "Network error"}")
                                checkSyncComplete(success = false)
                            }
                        })
                    }
                    override fun onFailure(call: Call<Feature>, t: Throwable) {
                        // Network error fetching place — skip this item (keep offline), don't overwrite
                        showSnackbar("Sync failed: ${t.message ?: "Network error"}")
                        checkSyncComplete(success = false)
                    }
                })
            } else {
                // CREATE scenario
                api.createPlace(feature).enqueue(object : Callback<Feature> {
                    override fun onResponse(call: Call<Feature>, response: Response<Feature>) {
                        if (response.isSuccessful) {
                            removeOfflinePlace(offlineItem)
                            updateList()
                            checkSyncComplete(success = true)
                        } else {
                            showSnackbar(syncErrorMessage(response))
                            checkSyncComplete(success = false)
                        }
                    }
                    override fun onFailure(call: Call<Feature>, t: Throwable) {
                        showSnackbar("Sync failed: ${t.message ?: "Network error"}")
                        checkSyncComplete(success = false)
                    }
                })
            }
        }
    }

    private fun isChanged(f1: Feature, f2: Feature): Boolean {
        if (f1.properties.name != f2.properties.name) return true
        if (f1.properties.description != f2.properties.description) return true
        if (f1.properties.address != f2.properties.address) return true
        if (f1.geometry.coordinates != f2.geometry.coordinates) return true
        return false
    }

    private fun navigateToPlace(feature: Feature) {
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        NavigationHelper.navigateToPlace(this, feature, serverUrl)
    }

    private fun openMapToPlace(feature: Feature) {
        val intent = Intent(this, MapActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putParcelableArrayListExtra("features", ArrayList<Feature>().apply {
                addAll(offlinePlacesList.map { it.feature })
                addAll(placesList)
            })
            val coords = feature.geometry.coordinates
            if (coords.size >= 2) {
                putExtra("zoom_to_lat", coords[1])
                putExtra("zoom_to_lon", coords[0])
                putExtra("zoom_to_id", feature.properties.database_id ?: -1)
            }
        }
        mapLauncher.launch(intent)
        safeNoAnimation()
    }

    fun handleOfflineSave(feature: Feature, original: Feature? = null, offlineEditIndex: Int = -1) {
        val indexToReplace = when {
            offlineEditIndex in offlinePlacesList.indices -> offlineEditIndex
            feature.properties.database_id != null -> {
                offlinePlacesList.indexOfFirst { it.feature.properties.database_id == feature.properties.database_id }
                    .takeIf { it >= 0 }
            }
            else -> null
        }
        if (indexToReplace != null) {
            val firstOriginal = offlinePlacesList[indexToReplace].original ?: original
            offlinePlacesList[indexToReplace] = OfflineFeature(feature, firstOriginal)
            val json = Gson().toJson(offlinePlacesList)
            prefs.edit().putString("offline_places", json).apply()
            updateList()
            Toast.makeText(this, "Saved offline. Pull to sync.", Toast.LENGTH_LONG).show()
        } else {
            saveOfflinePlace(feature, original)
        }
    }

    private fun deletePlace(feature: Feature) {
        val dbId = feature.properties.database_id
        if (dbId == null) {
            // This is an offline-only item, just remove it from offline list
            val offlineItem = offlinePlacesList.find { it.feature.properties.name == feature.properties.name }
            if (offlineItem != null) {
                removeOfflinePlace(offlineItem)
                updateList()
                Toast.makeText(this, "Offline place discarded", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(this, baseUrl).create(GeovaultApi::class.java)

        api.deletePlace(dbId).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    // Remove from offline list if it exists there
                    val offlineItem = offlinePlacesList.find { it.feature.properties.database_id == dbId }
                    if (offlineItem != null) {
                        removeOfflinePlace(offlineItem)
                    }
                    // Refresh the list from server
                    loadPlaces()
                    Toast.makeText(this@MainActivity, "Place deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to delete: Server error", Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Toast.makeText(
                    this@MainActivity,
                    "Cannot delete while offline. Please try again when connected.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun revertOfflineChanges(offlineFeature: OfflineFeature) {
        removeOfflinePlace(offlineFeature)
        updateList()
        
        if (offlineFeature.feature.properties.database_id != null) {
            showSnackbar("Changes reverted - showing original")
        } else {
            showSnackbar("Offline place discarded")
        }
    }

    private fun showSnackbar(message: String) {
        syncErrorBarText.text = message
        syncErrorBar.visibility = View.VISIBLE
    }

    /** Parse API error body (e.g. {"error": "..."}) for a user-facing sync failure message. */
    private fun syncErrorMessage(response: Response<*>): String {
        val body = response.errorBody()?.string() ?: return "Sync failed: server error ${response.code()}"
        return try {
            val json = org.json.JSONObject(body)
            val msg = json.optString("error", "").trim()
            if (msg.isNotEmpty()) msg else "Sync failed: server error ${response.code()}"
        } catch (_: Exception) {
            "Sync failed: server error ${response.code()}"
        }
    }
}

data class OfflineFeature(
    val feature: Feature,
    val original: Feature? = null
) : Serializable
