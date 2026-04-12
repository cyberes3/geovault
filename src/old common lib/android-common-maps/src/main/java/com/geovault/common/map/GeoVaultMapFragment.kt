package com.geovault.common.map

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.maps.R
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style

/**
 * Universal map fragment that encapsulates MapView lifecycle, style loading,
 * fail handling, source toggling, and base settings. Apps embed it and
 * receive [Callback.onMapReady] when the style is loaded.
 */
class GeoVaultMapFragment : Fragment(), OnMapReadyCallback, MapView.OnDidFailLoadingMapListener {

    private var _mapView: MapView? = null
    val mapView: MapView
        get() = _mapView!!
    val mapViewOrNull: MapView?
        get() = _mapView

    lateinit var mapManager: MapLibreManager
        private set

    private var _maplibreMap: MapLibreMap? = null
    val maplibreMap: MapLibreMap?
        get() = _maplibreMap

    private var mapReady = false
    private var callback: Callback? = null
    private var forceOsmOnly = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var styleLoadWatchdog: Runnable? = null
    private var styleLoadGeneration: Long = 0L
    private var styleDeliveredForGeneration = false

    fun setCallback(cb: Callback?) {
        callback = cb
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.gv_common_fragment_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _mapView = view.findViewById(R.id.gv_common_mapView)
        val mapViewRef = _mapView!!
        mapViewRef.overScrollMode = View.OVER_SCROLL_NEVER
        mapViewRef.foreground = ColorDrawable(ContextCompat.getColor(requireContext(), R.color.gv_common_map_underlay))
        mapViewRef.addOnDidFailLoadingMapListener(this)
        mapViewRef.onCreate(savedInstanceState)

        mapManager = MapLibreManager(requireActivity(), mapViewRef)
        mapManager.onStyleLoaded = { map, style ->
            if (isAdded && this@GeoVaultMapFragment.view != null) {
                styleDeliveredForGeneration = true
                clearStyleLoadWatchdog()
                val paddingPx = (DEFAULT_PADDING_DP * resources.displayMetrics.density).toInt()
                mapManager.defaultPadding = doubleArrayOf(
                    paddingPx.toDouble(), paddingPx.toDouble(),
                    paddingPx.toDouble(), paddingPx.toDouble()
                )
                callback?.onMapReady(map, style)
            }
        }

        val showToggle = arguments?.getBoolean(ARG_SHOW_TOGGLE, true) ?: true
        forceOsmOnly = arguments?.getBoolean(ARG_FORCE_OSM_ONLY, false) ?: false
        if (forceOsmOnly) {
            mapManager.sourceManager.setOsmOnly()
        }
        val toggleCard = view.findViewById<View>(R.id.gv_common_mapToggleCard)
        toggleCard.visibility = if (showToggle) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.gv_common_mapToggle).setOnClickListener {
            mapManager.sourceManager.setSelectedSourceId(mapManager.sourceManager.getNextSourceId())
            _maplibreMap?.let { mapManager.applySelectedSource(it) }
        }

        mapViewRef.getMapAsync(this)
        if (!forceOsmOnly) {
            mapManager.fetchMapSources {
                if (mapReady) {
                    _maplibreMap?.let { map ->
                        if (!mapManager.isCurrentSourceApplied(map)) {
                            mapManager.applySelectedSource(map)
                        }
                    }
                }
            }
        }
        applyOverScrollNever()
    }

    private fun applyOverScrollNever() {
        _mapView?.let { applyOverScrollNever(it) }
    }

    private fun applyOverScrollNever(mapViewRef: MapView) {
        mapViewRef.overScrollMode = View.OVER_SCROLL_NEVER
        var parent = mapViewRef.parent as? View
        while (parent != null) {
            parent.overScrollMode = View.OVER_SCROLL_NEVER
            parent = parent.parent as? View
        }
    }

    override fun onDidFailLoadingMap(errorMessage: String) {
        Log.e(TAG, "Map style load failed: $errorMessage")
        if (!isAdded) return
        styleDeliveredForGeneration = true
        clearStyleLoadWatchdog()
        val map = _maplibreMap ?: return
        val effectiveId = mapManager.sourceManager.getEffectiveSourceId()
        if (mapManager.sourceManager.isVectorSource(effectiveId)) {
            Toast.makeText(requireContext(), getString(R.string.gv_common_map_style_unavailable_fallback_osm), Toast.LENGTH_SHORT).show()
            mapManager.loadOsmFallback(map)
        } else {
            Toast.makeText(requireContext(), "Map failed: $errorMessage", Toast.LENGTH_LONG).show()
        }
    }

    override fun onMapReady(map: MapLibreMap) {
        if (!isAdded) return
        _maplibreMap = map
        mapReady = true
        mapManager.setupBaseMapSettings(map)
        styleLoadGeneration += 1L
        styleDeliveredForGeneration = false
        scheduleStyleLoadWatchdog(map, styleLoadGeneration)
        if (forceOsmOnly || mapManager.sourcesFetched || GeovaultAuthManager.getServerUrl(requireContext()).isEmpty()) {
            mapManager.applySelectedSource(map)
        }
    }

    override fun onStart() {
        super.onStart()
        _mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        _mapView?.let {
            applyOverScrollNever(it)
            it.onResume()
        }
        _maplibreMap?.let { map ->
            if (mapManager.sourcesFetched && !mapManager.isCurrentSourceApplied(map)) {
                mapManager.applySelectedSource(map)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        _mapView?.onPause()
    }

    override fun onStop() {
        super.onStop()
        _mapView?.onStop()
    }

    override fun onDestroyView() {
        clearStyleLoadWatchdog()
        mapManager.onStyleLoaded = null
        _mapView?.removeOnDidFailLoadingMapListener(this)
        _mapView?.onDestroy()
        _mapView = null
        _maplibreMap = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _mapView?.onSaveInstanceState(outState)
    }

    interface Callback {
        fun onMapReady(map: MapLibreMap, style: Style)
    }

    private fun scheduleStyleLoadWatchdog(map: MapLibreMap, generation: Long) {
        clearStyleLoadWatchdog()
        styleLoadWatchdog = Runnable {
            if (!isAdded || view == null) return@Runnable
            if (generation != styleLoadGeneration) return@Runnable
            if (styleDeliveredForGeneration) return@Runnable
            Log.w(TAG, "Map style load timed out; forcing OSM fallback")
            mapManager.loadOsmFallback(map)
        }
        mainHandler.postDelayed(styleLoadWatchdog!!, STYLE_LOAD_TIMEOUT_MS)
    }

    private fun clearStyleLoadWatchdog() {
        styleLoadWatchdog?.let { mainHandler.removeCallbacks(it) }
        styleLoadWatchdog = null
    }

    companion object {
        private const val TAG = "GeoVaultMapFragment"
        private const val DEFAULT_PADDING_DP = 50
        private const val STYLE_LOAD_TIMEOUT_MS = 5000L
        const val ARG_SHOW_TOGGLE = "gv_common_arg_show_toggle"
        const val ARG_FORCE_OSM_ONLY = "gv_common_arg_force_osm_only"
    }
}
