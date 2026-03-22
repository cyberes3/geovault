package com.geovault.places

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ImportantMessageSnackbar
import com.geovault.common.LoadingOverlayView
import com.geovault.common.RetrofitClient
import android.content.Intent
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
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.Serializable
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.geovault.common.ClipboardCopyHelper

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_SELECTED_ID_FROM_MAP = "selected_id_from_map"
        const val EXTRA_SHOW_EXPORT_SAVED_MESSAGE = "show_export_saved_message"
        const val EXTRA_OAUTH_ERROR = "oauth_error"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var refreshOverlay: LoadingOverlayView
    private lateinit var searchInput: EditText
    private lateinit var searchClear: View
    private lateinit var searchDivider: View
    private lateinit var fabAdd: View
    private lateinit var fabMap: View
    private lateinit var importantMessageSnackbar: ImportantMessageSnackbar
    private lateinit var adapter: PlacesAdapter
    private var refreshCall: Call<FeatureCollection>? = null
    private var initialLoadDone = false
    private var searchQuery: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (isDestroyed || !::swipeRefresh.isInitialized) return@Runnable
        if (swipeRefresh.isRefreshing) {
            cancelRefresh("Refresh timed out (10s)")
        }
    }

    private val executor = Executors.newSingleThreadExecutor()

    private val cache: PlacesCache
        get() = (application as PlacesApplication).placesCache

    private val mapLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedId = result.data?.getIntExtra("selected_id", -1) ?: -1
            if (selectedId != -1) {
                adapter.selectedId = selectedId
                val index = displayIndexForId(selectedId)
                if (index != -1) {
                    adapter.notifyDataSetChanged()
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val offset = recyclerView.height / 3
                    layoutManager.scrollToPositionWithOffset(index, offset)
                }
            }
        }
    }

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d(TAG, "editLauncher: resultCode=${result.resultCode}, data=${result.data}")
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data
        val hasOffline = data?.getParcelableExtra("offline_feature", Feature::class.java) != null
        val hasUpdated = data?.getParcelableExtra("updated_feature", Feature::class.java) != null
        Log.d(TAG, "editLauncher: data null=${data == null}, hasOffline=$hasOffline, hasUpdated=$hasUpdated")
        when {
            hasOffline -> {
                val offlineFeature = data!!.getParcelableExtra("offline_feature", Feature::class.java)!!
                val originalFeature = data.getParcelableExtra("original_feature", Feature::class.java)
                val offlineEditIndex = data.getIntExtra("offline_edit_index", -1)
                handleOfflineSave(offlineFeature, originalFeature, offlineEditIndex)
            }
            hasUpdated -> {
                val updated = data!!.getParcelableExtra("updated_feature", Feature::class.java)!!
                Log.d(TAG, "editLauncher: got updated_feature id=${updated.properties.database_id}, name=${updated.properties.name}")
                cache.updateCachedFeature(updated)
                Log.d(TAG, "editLauncher: after updateCachedFeature, cache size=${cache.getCachedFeatures().size}")
                searchQuery = ""
                searchInput.setText("")
                searchClear.visibility = View.GONE
                val id = updated.properties.database_id
                handler.post {
                    refreshListFromCache()
                    if (id != null) {
                        val index = displayIndexForId(id)
                        Log.d(TAG, "editLauncher: displayIndexForId($id)=$index")
                        if (index >= 0) {
                            recyclerView.post {
                                (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
                            }
                        }
                    }
                }
            }
            else -> {
                Log.d(TAG, "editLauncher: else branch, only refreshListFromCache()")
                refreshListFromCache()
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
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
            headerView.updatePadding(top = systemBars.top + 20)
            // Keep bottom content above nav bar and keyboard.
            view.updatePadding(bottom = bottomInset)
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
        refreshOverlay.setTitle("Syncing...")
        refreshOverlay.setSubtext("Tap to cancel")
        refreshOverlay.setOnOverlayClickListener {
            cancelRefresh(
                if (swipeRefresh.isRefreshing) "Cancelled - using cached data"
                else "Syncing cancelled"
            )
        }
        searchInput = findViewById(R.id.searchInput)
        searchClear = findViewById(R.id.searchClear)
        searchDivider = findViewById(R.id.searchDivider)
        fabAdd = findViewById(R.id.fab_add)
        fabMap = findViewById(R.id.fab_map)
        importantMessageSnackbar = findViewById(R.id.importantMessageSnackbar)
        val copyHelper = ClipboardCopyHelper(this)
        copyHelper.prewarm(recyclerView)
        adapter = PlacesAdapter(
            emptyList(),
            emptyList(),
            emptyList(),
            copyHelper,
            lifecycleScope,
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
                val index = cache.getOfflineFeatures().indexOfFirst { of ->
                    of.feature.properties.database_id == offlineFeature.feature.properties.database_id &&
                        (of.feature.properties.database_id != null || of.feature.properties.name == offlineFeature.feature.properties.name)
                }.takeIf { it >= 0 } ?: -1
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
                searchDivider.visibility = if (canScrollUp) View.VISIBLE else View.GONE
            }
        })

        searchInput.addTextChangedListener { text ->
            searchQuery = text?.toString()?.trim() ?: ""
            refreshListFromCache()
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
            }
            mapLauncher.launch(intent)
            safeNoAnimation()
        }

    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        intent?.getStringExtra(EXTRA_OAUTH_ERROR)?.let { message ->
            showSnackbar(message)
            intent?.removeExtra(EXTRA_OAUTH_ERROR)
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_EXPORT_SAVED_MESSAGE, false) == true) {
            intent?.removeExtra(EXTRA_SHOW_EXPORT_SAVED_MESSAGE)
            Toast.makeText(this, getString(R.string.offline_data_saved_to_files), Toast.LENGTH_SHORT).show()
        }
        val selectedIdFromMap = intent?.getIntExtra(EXTRA_SELECTED_ID_FROM_MAP, -1) ?: -1
        if (selectedIdFromMap != -1) {
            intent?.removeExtra(EXTRA_SELECTED_ID_FROM_MAP)
            adapter.selectedId = selectedIdFromMap
            val index = displayIndexForId(selectedIdFromMap)
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
        handler.removeCallbacks(timeoutRunnable)
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
            // Defer so activity result (e.g. from PlaceEditActivity) is delivered first, then we refresh
            handler.post { refreshListFromCache() }
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
                            refreshListFromCache()
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
        if (!::swipeRefresh.isInitialized || isDestroyed) return
        refreshCall?.cancel()
        refreshCall = null
        handler.removeCallbacks(timeoutRunnable)
        hideSyncOverlayAndReset()
        showSnackbar(message)
        if (cache.getCachedFeatures().isEmpty() && cache.getOfflineFeatures().isEmpty()) {
            refreshListFromCache()
        }
    }

    private fun hideSyncOverlayAndReset() {
        if (!::refreshOverlay.isInitialized || isDestroyed) return
        refreshOverlay.hide()
        swipeRefresh.isRefreshing = false
        fabAdd.isEnabled = true
        fabMap.isEnabled = true
    }

    private data class PlacesListSections(
        val savedPlaces: List<Feature>,
        val offlinePlaceFeatures: List<Feature>,
        val offlineFeatures: List<OfflineFeature>
    )

    private fun buildPlacesListSections(): PlacesListSections {
        val cached = cache.getCachedFeatures()
        val offline = cache.getOfflineFeatures()
        val q = searchQuery
        val (filteredCached, filteredOffline) = if (q.isNotBlank()) {
            fun matches(feature: Feature): Boolean {
                val name = feature.properties.name ?: ""
                val desc = feature.properties.description ?: ""
                return name.contains(q, ignoreCase = true) || desc.contains(q, ignoreCase = true)
            }
            cached.filter(::matches) to offline.filter { matches(it.feature) }
        } else {
            cached to offline
        }
        val offlineEditIds = filteredOffline.mapNotNull { it.feature.properties.database_id }.toSet()
        val savedPlaces = filteredCached
            .filter { it.properties.database_id !in offlineEditIds }
        return PlacesListSections(
            savedPlaces,
            filteredOffline.map { it.feature },
            filteredOffline
        )
    }

    private fun refreshListFromCache() {
        if (!::recyclerView.isInitialized) return
        val sections = buildPlacesListSections()
        Log.d(TAG, "refreshListFromCache: saved=${sections.savedPlaces.size}, offline=${sections.offlineFeatures.size}")
        val q = searchQuery
        Log.d(TAG, "refreshListFromCache: searchQuery='$q'")
        adapter.updateData(
            sections.savedPlaces,
            sections.offlinePlaceFeatures,
            sections.offlineFeatures
        )
        if (sections.savedPlaces.isEmpty() && sections.offlineFeatures.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
        updateLastSyncUI()
    }

    /** Returns adapter display position for the item with given database_id, or -1. Matches adapter order: header, offline items, header, saved (cached minus offline ids). */
    private fun displayIndexForId(id: Int): Int {
        val sections = buildPlacesListSections()
        val filteredOffline = sections.offlineFeatures
        val saved = sections.savedPlaces
        val offlineIndex = filteredOffline.indexOfFirst { it.feature.properties.database_id == id }
        if (offlineIndex >= 0) return 1 + offlineIndex
        val savedIndex = saved.indexOfFirst { it.properties.database_id == id }
        if (savedIndex >= 0) {
            return if (filteredOffline.isEmpty()) savedIndex else 2 + filteredOffline.size + savedIndex
        }
        return -1
    }

    private fun saveOfflinePlace(feature: Feature, original: Feature? = null) {
        cache.addOrUpdateOffline(feature, original, -1)
        refreshListFromCache()
        Toast.makeText(this, "Saved offline. Pull to sync.", Toast.LENGTH_LONG).show()
    }

    private fun removeOfflinePlace(offlineFeature: OfflineFeature) {
        cache.removeOffline(offlineFeature)
        refreshListFromCache()
    }

    private fun loadPlaces(syncOffline: Boolean = true) {
        clearSelection()
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"

        refreshCall?.cancel()
        val api = RetrofitClient.getClient(this, baseUrl).create(GeovaultApi::class.java)

        refreshOverlay.setTitle("Syncing...")
        refreshOverlay.show()
        swipeRefresh.isRefreshing = true
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
                        cache.setCached(body, System.currentTimeMillis())
                        refreshListFromCache()
                        val offlineCount = cache.getOfflineFeatures().size
                        if (syncOffline && offlineCount > 0) {
                            refreshOverlay.setTitle("Syncing $offlineCount offline ${if (offlineCount == 1) "item" else "items"}...")
                            runPendingSync(serverUrl)
                        } else {
                            hideSyncOverlayAndReset()
                        }
                    } else {
                        hideSyncOverlayAndReset()
                        showSnackbar("Server returned no data")
                        refreshListFromCache()
                    }
                } else {
                    hideSyncOverlayAndReset()
                    if (response.code() == 401) {
                        GeovaultAuthManager.handleAuthFailure(this@MainActivity)
                        return
                    }
                    showSnackbar("Server Error: ${response.code()}")
                    refreshListFromCache()
                }
            }

            override fun onFailure(call: Call<FeatureCollection>, t: Throwable) {
                if (call.isCanceled) return

                handler.removeCallbacks(timeoutRunnable)
                refreshCall = null
                hideSyncOverlayAndReset()

                showSnackbar("Network failed: ${t.message}")
                refreshListFromCache()
            }
        })
    }

    private fun updateLastSyncUI() {
        val lastSync = cache.getLastSyncTime()
        if (lastSync == 0L) return
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val timeStr = sdf.format(java.util.Date(lastSync))
        findViewById<TextView>(R.id.lastSyncText)?.text = "Last synced: $timeStr"
    }

    private fun runPendingSync(serverUrl: String) {
        syncOfflinePlaces()
        NavigationHelper.flushPendingNavigations(this, serverUrl)
    }

    private fun syncOfflinePlaces() {
        val toSync = cache.getOfflineFeatures().toList()
        if (toSync.isEmpty()) return

        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(this, baseUrl).create(GeovaultApi::class.java)

        refreshOverlay.setTitle("Syncing ${toSync.size} offline ${if (toSync.size == 1) "item" else "items"}...")
        refreshOverlay.show()

        var syncedCount = 0
        var successCount = 0

        fun checkSyncComplete(success: Boolean = false) {
            syncedCount++
            if (success) successCount++
            if (syncedCount >= toSync.size) {
                if (successCount > 0 || cache.getOfflineFeatures().isEmpty()) {
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
                                        showSnackbar("Conflict detected: '${feature.properties.name}' saved as new item")
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
        cache.addOrUpdateOffline(feature, original, offlineEditIndex)
        refreshListFromCache()
        Toast.makeText(this, "Saved offline. Pull to sync.", Toast.LENGTH_LONG).show()
    }

    private fun deletePlace(feature: Feature) {
        val dbId = feature.properties.database_id
        if (dbId == null) {
            val offlineItem = cache.getOfflineFeatures().find { it.feature.properties.name == feature.properties.name }
            if (offlineItem != null) {
                cache.removeOffline(offlineItem)
                refreshListFromCache()
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
                    val offlineItem = cache.getOfflineFeatures().find { it.feature.properties.database_id == dbId }
                    if (offlineItem != null) {
                        cache.removeOffline(offlineItem)
                        refreshListFromCache()
                    }
                    loadPlaces()
                } else {
                    showSnackbar("Failed to delete: Server error")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                showSnackbar("Cannot delete while offline. Please try again when connected.")
            }
        })
    }

    private fun revertOfflineChanges(offlineFeature: OfflineFeature) {
        removeOfflinePlace(offlineFeature)
        if (offlineFeature.feature.properties.database_id != null) {
            Toast.makeText(this, "Changes reverted - showing original", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Offline place discarded", Toast.LENGTH_SHORT).show()
        }
    }

    fun showSnackbar(message: String) {
        importantMessageSnackbar.showMessage(message)
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
