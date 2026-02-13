package com.geovault.places

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.activity.result.contract.ActivityResultContracts
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: PlacesAdapter
    private val placesList = mutableListOf<Feature>()

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

        adapter = PlacesAdapter(placesList) { feature ->
            navigateToPlace(feature)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener {
            loadPlaces()
        }

        findViewById<View>(R.id.fab_map).setOnClickListener {
            val intent = Intent(this, MapActivity::class.java)
            intent.putParcelableArrayListExtra("features", ArrayList(placesList))
            mapLauncher.launch(intent)
            safeNoAnimation()
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
            loadPlaces()
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

    private fun loadPlaces() {
        val serverUrl = prefs.getString("server_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        
        // Ensure URL ends with /
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"

        val api = RetrofitClient.getClient(baseUrl, apiKey).create(GeovaultApi::class.java)
        
        swipeRefresh.isRefreshing = true
        api.getPlaces().enqueue(object : Callback<FeatureCollection> {
            override fun onResponse(call: Call<FeatureCollection>, response: Response<FeatureCollection>) {
                swipeRefresh.isRefreshing = false
                if (response.isSuccessful) {
                    val features = response.body()?.features ?: emptyList()
                    placesList.clear()
                    placesList.addAll(features)
                    adapter.notifyDataSetChanged()
                    
                    if (placesList.isEmpty()) {
                        emptyText.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        emptyText.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Error: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<FeatureCollection>, t: Throwable) {
                swipeRefresh.isRefreshing = false
                Toast.makeText(this@MainActivity, "Failed: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }
    
    private fun navigateToPlace(feature: Feature) {
        val coords = feature.geometry.coordinates
        if (coords.size >= 2) {
            val lon = coords[0]
            val lat = coords[1]
            val label = feature.properties.name ?: "Place"
            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
            
            // Track navigation
            val serverUrl = prefs.getString("server_url", "") ?: ""
            val apiKey = prefs.getString("api_key", "") ?: ""
            val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
            val api = RetrofitClient.getClient(baseUrl, apiKey).create(GeovaultApi::class.java)
            api.trackNavigation(feature.properties.database_id).enqueue(object : Callback<Void> {
                override fun onResponse(call: Call<Void>, response: Response<Void>) {}
                override fun onFailure(call: Call<Void>, t: Throwable) {}
            })
        }
    }
}
