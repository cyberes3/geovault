package com.geovault.places

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
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
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var refreshOverlay: View
    private lateinit var syncText: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchClear: View
    private lateinit var fabAdd: View
    private lateinit var fabMap: View
    private lateinit var adapter: PlacesAdapter
    private val placesList = mutableListOf<Feature>()
    private val offlinePlacesList = mutableListOf<OfflineFeature>()
    private var refreshCall: Call<FeatureCollection>? = null
    private var initialLoadDone = false
    private var searchQuery: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (swipeRefresh.isRefreshing) {
            cancelRefresh("Refresh timed out (10s)")
        }
    }

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
            val offlineFeature = if (android.os.Build.VERSION.SDK_INT >= 33) {
                result.data?.getParcelableExtra("offline_feature", Feature::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra("offline_feature")
            }
            
            if (offlineFeature != null) {
                val originalFeature = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    result.data?.getParcelableExtra("original_feature", Feature::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    result.data?.getParcelableExtra("original_feature")
                }
                handleOfflineSave(offlineFeature, originalFeature)
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
        emptyText = findViewById(R.id.emptyText)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        refreshOverlay = findViewById(R.id.refreshOverlay)
        syncText = findViewById(R.id.syncText)
        searchInput = findViewById(R.id.searchInput)
        searchClear = findViewById(R.id.searchClear)
        fabAdd = findViewById(R.id.fab_add)
        fabMap = findViewById(R.id.fab_map)

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
                val intent = Intent(this, PlaceEditActivity::class.java)
                intent.putExtra("feature", offlineFeature.feature)
                intent.putExtra("original_feature", offlineFeature.original)
                intent.putExtra("is_offline_edit", true)
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
            val intent = Intent(this, MapActivity::class.java)
            val allFeatures = ArrayList<Feature>()
            allFeatures.addAll(offlinePlacesList.map { it.feature })
            allFeatures.addAll(placesList)
            intent.putParcelableArrayListExtra("features", allFeatures)
            mapLauncher.launch(intent)
            safeNoAnimation()
        }

        // Tap overlay to cancel refresh and use cache
        refreshOverlay.setOnClickListener {
            if (swipeRefresh.isRefreshing) {
                cancelRefresh("Cancelled - using cached data")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkConfigAndLoad()
    }

    private fun checkConfigAndLoad() {
        val serverUrl = prefs.getString("server_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""

        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            safeNoAnimation()
        } else {
            // Always load from cache/offline for immediate display when resuming
            loadOfflinePlaces()
            loadFromCache()
            // Sync from server only on first load; returning from edit/new triggers sync via editLauncher
            if (!initialLoadDone) {
                initialLoadDone = true
                loadPlaces()
            }
        }
    }

    fun safeNoAnimation() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun cancelRefresh(message: String) {
        refreshCall?.cancel()
        refreshCall = null
        handler.removeCallbacks(timeoutRunnable)
        swipeRefresh.isRefreshing = false
        refreshOverlay.visibility = View.GONE
        fabAdd.isEnabled = true
        fabMap.isEnabled = true
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        // If we cancelled, ensure we at least show what's in cache if lists are empty
        if (placesList.isEmpty() && offlinePlacesList.isEmpty()) {
            loadOfflinePlaces()
            loadFromCache()
        }
    }

    private fun loadFromCache() {
        val cachedJson = prefs.getString("cached_places", null)
        if (cachedJson != null) {
            try {
                val collection = Gson().fromJson(cachedJson, FeatureCollection::class.java)
                placesList.clear()
                placesList.addAll(collection.features)
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
        prefs.edit().putString("cached_places", json).apply()
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
                val name = feature.properties.name ?: "Unnamed Place"
                return name.contains(query, ignoreCase = true)
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
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun loadPlaces() {
        val serverUrl = prefs.getString("server_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"

        refreshCall?.cancel()
        val api = RetrofitClient.getClient(baseUrl, apiKey).create(GeovaultApi::class.java)
        
        swipeRefresh.isRefreshing = true
        refreshOverlay.visibility = View.VISIBLE
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
                swipeRefresh.isRefreshing = false
                refreshOverlay.visibility = View.GONE
                fabAdd.isEnabled = true
                fabMap.isEnabled = true
                refreshCall = null

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        saveToCache(body)
                        placesList.clear()
                        placesList.addAll(body.features)
                        updateList()
                        // Sync offline items AFTER we have fresh server data for conflict detection
                        syncOfflinePlaces()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Server Error: ${response.code()}", Toast.LENGTH_SHORT).show()
                    loadFromCache() // Use cache on error
                }
            }

            override fun onFailure(call: Call<FeatureCollection>, t: Throwable) {
                if (call.isCanceled) return
                
                handler.removeCallbacks(timeoutRunnable)
                swipeRefresh.isRefreshing = false
                refreshOverlay.visibility = View.GONE
                fabAdd.isEnabled = true
                fabMap.isEnabled = true
                refreshCall = null

                Toast.makeText(this@MainActivity, "Network failed: ${t.message}", Toast.LENGTH_SHORT).show()
                loadOfflinePlaces()
                loadFromCache() // Use cache on failure
                updateList()
            }
        })
        
        syncOfflinePlaces()
    }

    private fun syncOfflinePlaces() {
        if (offlinePlacesList.isEmpty()) return

        val serverUrl = prefs.getString("server_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(baseUrl, apiKey).create(GeovaultApi::class.java)

        // Show syncing status in overlay
        refreshOverlay.visibility = View.VISIBLE
        syncText.text = "Syncing ${offlinePlacesList.size} offline item(s)..."

        // Process one by one
        val toSync = ArrayList(offlinePlacesList)
        var syncedCount = 0
        
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
                            syncedCount++
                            if (syncedCount >= toSync.size) refreshOverlay.visibility = View.GONE
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
                                        syncedCount++
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Conflict detected: '${feature.properties.name}' saved as new item",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        if (offlinePlacesList.isEmpty()) {
                                            refreshOverlay.visibility = View.GONE
                                            loadPlaces()
                                        }
                                    }
                                }
                                override fun onFailure(createCall: Call<Feature>, t: Throwable) {
                                    syncedCount++
                                    if (syncedCount >= toSync.size) refreshOverlay.visibility = View.GONE
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
                                    syncedCount++
                                    if (offlinePlacesList.isEmpty()) {
                                        refreshOverlay.visibility = View.GONE
                                        loadPlaces()
                                    }
                                }
                            }
                            override fun onFailure(updateCall: Call<Feature>, t: Throwable) {
                                syncedCount++
                                if (syncedCount >= toSync.size) refreshOverlay.visibility = View.GONE
                            }
                        })
                    }
                    override fun onFailure(call: Call<Feature>, t: Throwable) {
                        // Network error fetching place — skip this item (keep offline), don't overwrite
                        syncedCount++
                        if (syncedCount >= toSync.size) refreshOverlay.visibility = View.GONE
                    }
                })
            } else {
                // CREATE scenario
                api.createPlace(feature).enqueue(object : Callback<Feature> {
                    override fun onResponse(call: Call<Feature>, response: Response<Feature>) {
                        if (response.isSuccessful) {
                            removeOfflinePlace(offlineItem)
                            updateList()
                            syncedCount++
                            if (offlinePlacesList.isEmpty()) {
                                refreshOverlay.visibility = View.GONE
                                loadPlaces()
                            }
                        }
                    }
                    override fun onFailure(call: Call<Feature>, t: Throwable) {
                        syncedCount++
                        if (syncedCount >= toSync.size) {
                            refreshOverlay.visibility = View.GONE
                        }
                    }
                })
            }
        }
    }

    private fun isChanged(f1: Feature, f2: Feature): Boolean {
        if (f1.properties.name != f2.properties.name) return true
        if (f1.properties.description != f2.properties.description) return true
        if (f1.geometry.coordinates != f2.geometry.coordinates) return true
        return false
    }

    private fun navigateToPlace(feature: Feature) {
        val serverUrl = prefs.getString("server_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        NavigationHelper.navigateToPlace(this, feature, apiKey, serverUrl)
    }

    private fun openMapToPlace(feature: Feature) {
        val intent = Intent(this, MapActivity::class.java)
        val allFeatures = ArrayList<Feature>()
        allFeatures.addAll(offlinePlacesList.map { it.feature })
        allFeatures.addAll(placesList)
        intent.putParcelableArrayListExtra("features", allFeatures)
        val coords = feature.geometry.coordinates
        if (coords.size >= 2) {
            intent.putExtra("zoom_to_lat", coords[1])
            intent.putExtra("zoom_to_lon", coords[0])
            intent.putExtra("zoom_to_id", feature.properties.database_id ?: -1)
        }
        mapLauncher.launch(intent)
        safeNoAnimation()
    }

    fun handleOfflineSave(feature: Feature, original: Feature? = null) {
        saveOfflinePlace(feature, original)
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

        val serverUrl = prefs.getString("server_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(baseUrl, apiKey).create(GeovaultApi::class.java)

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
            Toast.makeText(this, "Changes reverted - showing original", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Offline place discarded", Toast.LENGTH_SHORT).show()
        }
    }
}

data class OfflineFeature(
    val feature: Feature,
    val original: Feature? = null
) : Serializable
