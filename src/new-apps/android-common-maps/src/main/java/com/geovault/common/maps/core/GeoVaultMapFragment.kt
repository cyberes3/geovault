package com.geovault.common.maps.core

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

class GeoVaultMapFragment : Fragment() {
    interface Callback {
        fun onMapReady(map: MapLibreMap, style: Style)
    }

    var callback: Callback? = null
    var mapView: MapView? = null
        private set
    var maplibreMap: MapLibreMap? = null
        private set
    private var manager: MapLibreManager? = null

    val mapManager: MapLibreManager
        get() = manager ?: error("Map manager not initialized")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibreInitializer.init(requireContext())
        manager = MapLibreManager(requireContext())
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val mv = MapView(requireContext())
        mv.onCreate(savedInstanceState)
        mapView = mv
        mv.getMapAsync { map ->
            maplibreMap = map
            map.setStyle("https://demotiles.maplibre.org/style.json") { style ->
                callback?.onMapReady(map, style)
            }
        }
        return mv
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroyView() {
        mapView?.onDestroy()
        mapView = null
        maplibreMap = null
        super.onDestroyView()
    }
}
