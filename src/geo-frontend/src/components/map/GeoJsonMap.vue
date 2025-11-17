<template>
  <div class="w-full h-full flex">
    <!-- Left Sidebar - Feature List -->
    <FeatureListSidebar
      :features="featuresInExtent"
      @feature-click="zoomToFeature"
    />

    <!-- Center - Map -->
    <div class="flex-1 bg-gray-50 relative">
      <div class="relative w-full h-full">
        <!-- Map -->
        <div ref="mapContainer" class="w-full h-full"></div>

        <!-- Loading Indicator -->
        <div v-show="isLoading" class="absolute top-4 right-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10">
          <div class="flex items-center space-x-2">
            <div class="animate-spin rounded-full h-4 w-4 border-b-2 border-blue-600"></div>
            <span class="text-sm text-gray-700">Loading data...</span>
          </div>
        </div>

        <!-- Feature Info Box or Edit Box -->
        <FeatureInfoBox
          v-if="!isEditingFeature"
          :feature="selectedFeature"
          @close="selectedFeature = null"
          @edit="handleEditFeature"
        />
        <FeatureEditBox
          v-if="isEditingFeature"
          :feature="selectedFeature"
          @cancel="handleCancelEdit"
          @saved="handleFeatureSaved"
          @deleted="handleFeatureDeleted"
        />
      </div>
    </div>

    <!-- Right Sidebar - Map Controls -->
    <MapControlsSidebar
      :selected-layer="selectedLayer"
      :tile-sources="tileSources"
      :feature-count="featureCount"
      :max-features="MAX_FEATURES"
      :user-location="userLocation"
      :location-display-name="getLocationDisplayName()"
      @layer-change="updateMapLayer"
    />
  </div>
</template>

<script>
import {markRaw} from 'vue'
import {Map, View} from 'ol'
import {OSM, XYZ} from 'ol/source'
import {Tile as TileLayer, Vector as VectorLayer} from 'ol/layer'
import {Vector as VectorSource} from 'ol/source'
import {GeoJSON} from 'ol/format'
import {fromLonLat, toLonLat} from 'ol/proj'
import {authMixin} from '@/assets/js/authMixin.js'
import {MapUtils} from '@/utils/map/MapUtils'
import {APIHOST} from '@/config.js'
import FeatureListSidebar from './FeatureListSidebar.vue'
import MapControlsSidebar from './MapControlsSidebar.vue'
import FeatureInfoBox from './FeatureInfoBox.vue'
import FeatureEditBox from './FeatureEditBox.vue'

export default {
  name: 'GeoJsonMap',
  components: {
    FeatureListSidebar,
    MapControlsSidebar,
    FeatureInfoBox,
    FeatureEditBox
  },
  mixins: [authMixin],
  data() {
    return {
      map: null,
      vectorSource: null,
      vectorLayer: null, // Layer for icons/images (no declutter)
      textLayer: null, // Layer for text labels (with declutter)
      tileLayer: null, // Reference to the tile layer for updates
      isLoading: false,
      loadedBounds: new Set(),
      lastUpdateTime: null,
      featureCount: 0,
      loadTimeout: null,
      userLocation: null,
      currentAbortController: null, // AbortController for current request
      selectedLayer: 'osm', // Currently selected map layer
      featuresInExtent: [], // Features currently visible in map extent
      featureListUpdateTimeout: null, // Debounce timeout for feature list updates
      selectedFeature: null, // Currently selected feature from map click
      tileSources: [], // Available tile sources from backend
      // Configuration
      API_BASE_URL: '/api/data/geojson/',
      LOCATION_API_URL: '/api/data/location/user/',
      TILE_SOURCES_API_URL: '/api/tiles/sources/',
      MAX_FEATURES: 5000, // Maximum number of features to keep on the map
      featureTimestamps: {}, // Use plain object instead of Map
      featureIdCounter: 0, // Counter to generate unique IDs for features
      currentZoom: null,
      featureCountUpdatePending: false, // Flag to batch feature count updates
      isEditingFeature: false // Track if we're in edit mode
    }
  },
  methods: {
    // Generate a unique ID for a feature
    getFeatureId(feature) {
      // Try to get existing ID or create a new one
      if (!feature._geoJsonMapId) {
        feature._geoJsonMapId = `feature_${++this.featureIdCounter}_${Date.now()}`
      }
      return feature._geoJsonMapId
    },

    // Get feature name from properties (used for sorting)
    getFeatureName(feature) {
      const properties = feature.get('properties') || {}
      return properties.name || 'Unnamed Feature'
    },

    // Fetch tile sources configuration from backend
    async fetchTileSources() {
      try {
        const response = await fetch(this.TILE_SOURCES_API_URL)
        const data = await response.json()
        
        if (data.sources && Array.isArray(data.sources)) {
          this.tileSources = data.sources
          console.log('Loaded tile sources:', this.tileSources)
          
          // Set default layer if not already set or if current selection is invalid
          if (!this.selectedLayer || !this.tileSources.find(s => s.id === this.selectedLayer)) {
            if (this.tileSources.length > 0) {
              this.selectedLayer = this.tileSources[0].id
            }
          }
        } else {
          console.error('Invalid tile sources response:', data)
        }
      } catch (error) {
        console.error('Error fetching tile sources:', error)
        // Fallback to default OSM if API fails
        this.tileSources = [{
          id: 'osm',
          name: 'OpenStreetMap',
          type: 'osm',
          requires_proxy: false,
          client_config: { type: 'osm' }
        }]
      }
    },

    // Update map layer based on selection
    updateMapLayer(layerValue) {
      if (!this.map || !this.tileLayer) return

      // Update selected layer
      this.selectedLayer = layerValue

      // Find the tile source configuration
      const tileSource = this.tileSources.find(s => s.id === layerValue)
      if (!tileSource) {
        console.error(`Tile source not found: ${layerValue}`)
        return
      }

      // Remove current tile layer
      this.map.removeLayer(this.tileLayer)

      // Create new tile layer based on configuration
      const clientConfig = tileSource.client_config || {}
      
      if (clientConfig.type === 'osm' || tileSource.type === 'osm') {
        // OpenStreetMap source
        this.tileLayer = markRaw(new TileLayer({
          source: new OSM()
        }))
      } else if (clientConfig.type === 'xyz' || tileSource.type === 'xyz') {
        // XYZ tile source (may use proxy URL from client_config)
        const url = clientConfig.url || '/api/tiles/{id}/{z}/{x}/{y}'.replace('{id}', layerValue)
        const xyzSource = new XYZ({
          url: url
        })
        this.tileLayer = markRaw(new TileLayer({
          source: xyzSource
        }))
      } else {
        console.error(`Unsupported tile source type: ${clientConfig.type || tileSource.type}`)
        return
      }

      // Add new tile layer at the beginning (below vector layer)
      this.map.getLayers().insertAt(0, this.tileLayer)
    },

    // Update features in extent list
    // Note: This includes all features in the vector source that intersect the extent,
    // regardless of whether they are currently rendered (e.g., small polygons hidden at low zoom)
    updateFeaturesInExtent() {
      if (!this.map || !this.vectorSource) {
        this.featuresInExtent = []
        return
      }

      const view = this.map.getView()
      const extent = view.calculateExtent()

      // Buffer extent by 50 miles (approximately 80,467 meters)
      // 50 miles * 1609.34 meters/mile = 80,467 meters
      const bufferDistance = 50 * 1609.34
      const bufferedExtent = [
        extent[0] - bufferDistance, // minX
        extent[1] - bufferDistance, // minY
        extent[2] + bufferDistance, // maxX
        extent[3] + bufferDistance  // maxY
      ]

      // Get all features from vector source (includes features hidden from rendering)
      const allFeatures = this.vectorSource.getFeatures()

      // Filter features that intersect with buffered extent (50 miles around current view)
      const featuresInView = allFeatures.filter(feature => {
        const geometry = feature.getGeometry()
        if (!geometry) return false
        return geometry.intersectsExtent(bufferedExtent)
      })

      // Sort features alphabetically by name
      featuresInView.sort((a, b) => {
        const nameA = this.getFeatureName(a).toLowerCase()
        const nameB = this.getFeatureName(b).toLowerCase()
        return nameA.localeCompare(nameB)
      })

      this.featuresInExtent = featuresInView
    },

    // Debounced update of features in extent
    debouncedUpdateFeaturesInExtent() {
      if (this.featureListUpdateTimeout) {
        clearTimeout(this.featureListUpdateTimeout)
      }
      this.featureListUpdateTimeout = setTimeout(() => {
        this.updateFeaturesInExtent()
      }, 200) // 200ms debounce
    },

    // Zoom to a specific feature
    zoomToFeature(feature) {
      if (!this.map || !feature) return

      const geometry = feature.getGeometry()
      if (!geometry) return

      const view = this.map.getView()
      const extent = geometry.getExtent()
      const geometryType = geometry.getType()

      // Determine max zoom based on geometry type
      // Points need more context, so limit zoom more
      let maxZoom = 15
      if (geometryType === 'Point' || geometryType === 'MultiPoint') {
        maxZoom = 14 // Limit zoom for points to show surrounding area
      }

      // Fit the view to the feature's extent with padding
      view.fit(extent, {
        padding: [50, 50, 50, 50], // Add padding around the feature
        duration: 500, // Animation duration in milliseconds
        maxZoom: maxZoom // Limit maximum zoom level
      })

      // Show info box for the selected feature
      this.selectedFeature = feature
      this.isEditingFeature = false // Reset edit mode when selecting a new feature
    },

    // Handle edit button click
    handleEditFeature() {
      this.isEditingFeature = true
    },

    // Handle cancel edit
    handleCancelEdit() {
      this.isEditingFeature = false
    },

    // Handle feature saved
    async handleFeatureSaved() {
      this.isEditingFeature = false
      
      // Get the updated feature from the backend
      const featureId = this.selectedFeature?.get('properties')?._id
      if (featureId && this.vectorSource) {
        try {
          // Fetch the updated feature
          const response = await fetch(`${APIHOST}/api/data/feature/${featureId}/`)
          if (response.ok) {
            const data = await response.json()
            if (data.success && data.feature) {
              // Find the existing feature in the vector source by ID
              const existingFeatures = this.vectorSource.getFeatures()
              const existingFeature = existingFeatures.find(f => {
                const props = f.get('properties') || {}
                return props._id === featureId
              })
              
              if (existingFeature) {
                // Update the existing feature with new data
                const format = new GeoJSON()
                const geojsonData = data.feature.geojson
                
                // Read the feature from GeoJSON
                const updatedFeature = format.readFeature(geojsonData, {
                  featureProjection: 'EPSG:3857',
                  dataProjection: 'EPSG:4326'
                })
                
                // Manually preserve properties from the GeoJSON data (same as loadDataForCurrentView)
                // Create a new properties object to avoid reference issues
                const properties = geojsonData && geojsonData.properties 
                  ? { ...geojsonData.properties }
                  : {}
                
                // Add the _id to properties for future updates
                properties._id = featureId
                updatedFeature.set('properties', properties)
                
                // Preserve geojson_hash if available
                if (data.feature.geojson_hash) {
                  updatedFeature.set('geojson_hash', data.feature.geojson_hash)
                }
                
                // Replace the old feature with the updated one
                this.vectorSource.removeFeature(existingFeature)
                this.vectorSource.addFeature(updatedFeature)
                
                // Update selected feature if it's the one we just updated
                if (this.selectedFeature === existingFeature) {
                  this.selectedFeature = updatedFeature
                }
                
                // Force style update
                this.vectorLayer.changed()
                this.textLayer.changed()
                
                // Update features in extent list
                this.updateFeaturesInExtent()
                return
              }
            }
          }
        } catch (error) {
          console.error('Error fetching updated feature:', error)
        }
      }
      
      // Fallback: Refresh the map data to show updated feature
      // Clear the loaded bounds to force a reload
      this.loadedBounds.clear()
      // Reload data for current view
      await this.loadDataForCurrentView()
      // Update features in extent list
      this.updateFeaturesInExtent()
    },

    // Handle feature deleted
    async handleFeatureDeleted() {
      this.isEditingFeature = false
      
      // Get the deleted feature ID
      const featureId = this.selectedFeature?.get('properties')?._id
      
      // Remove the deleted feature from vector source if it exists
      if (featureId && this.vectorSource) {
        const existingFeatures = this.vectorSource.getFeatures()
        const featureToRemove = existingFeatures.find(f => {
          const props = f.get('properties') || {}
          return props._id === featureId
        })
        
        if (featureToRemove) {
          this.vectorSource.removeFeature(featureToRemove)
          
          // Remove from feature timestamps if it exists
          const featureId_key = this.getFeatureId(featureToRemove)
          if (this.featureTimestamps[featureId_key]) {
            delete this.featureTimestamps[featureId_key]
          }
        }
      }
      
      // Clear selected feature
      this.selectedFeature = null
      
      // Clear loaded bounds cache to force reload
      this.loadedBounds.clear()
      
      // Reload data for current view to refresh the map
      await this.loadDataForCurrentView()
      
      // Update features in extent list
      this.updateFeaturesInExtent()
    },

    async initializeMap() {
      // Get user location first
      await this.getUserLocation()

      // Create vector source and two separate layers
      // Use markRaw to prevent Vue from making OpenLayers objects reactive
      // This is critical for performance when adding thousands of features
      this.vectorSource = markRaw(new VectorSource())

      // Layer for icons/images - no declutter, so icons can overlap
      this.vectorLayer = markRaw(new VectorLayer({
        source: this.vectorSource,
        style: (feature, resolution) => MapUtils.getFeatureIconStyle(feature, resolution),
        // Performance optimizations for complex polygon rendering
        renderBuffer: 100,  // Only render features within 100px of viewport
        updateWhileAnimating: true,  // Continue updating during animations
        updateWhileInteracting: true,  // Continue updating during interactions
        declutter: false,  // Allow icons to overlap
        // Layer visibility optimizations for large datasets
        minResolution: 0,  // Show at all zoom levels
        maxResolution: Infinity  // No upper limit, but can be adjusted for performance
      }))

      // Layer for text labels - with declutter, so overlapping labels are hidden
      this.textLayer = markRaw(new VectorLayer({
        source: this.vectorSource,
        style: (feature) => MapUtils.getFeatureTextStyle(feature),
        // Performance optimizations
        renderBuffer: 100,
        updateWhileAnimating: true,
        updateWhileInteracting: true,
        declutter: true,  // Declutter overlapping text labels
        minResolution: 0,
        maxResolution: Infinity
      }))

      // Determine initial map center and zoom based on user location
      const mapConfig = this.getInitialMapConfig()

      // Create tile layer and store reference
      this.tileLayer = markRaw(new TileLayer({
        source: new OSM()
      }))

      // Create map
      // Use markRaw to prevent Vue from making the map object reactive
      this.map = markRaw(new Map({
        target: this.$refs.mapContainer,
        layers: [
          this.tileLayer,
          this.vectorLayer,  // Icons layer (rendered first, below text)
          this.textLayer    // Text labels layer (rendered on top, with declutter)
        ],
        view: new View({
          center: fromLonLat(mapConfig.center),
          zoom: mapConfig.zoom
        })
      }))

      // Add event listeners for data loading
      this.map.getView().on('change:center', this.debouncedLoadData)
      this.map.getView().on('change:resolution', this.debouncedLoadData)

      // Add event listeners for feature list updates
      this.map.getView().on('change:center', this.debouncedUpdateFeaturesInExtent)
      this.map.getView().on('change:resolution', this.debouncedUpdateFeaturesInExtent)

      // Add click event listener for feature selection
      this.map.on('click', (event) => {
        const clickedFeature = this.map.forEachFeatureAtPixel(
          event.pixel,
          (feature) => feature,
          {
            hitTolerance: 5 // 5 pixel tolerance for easier clicking
          }
        )
        
        if (clickedFeature) {
          this.selectedFeature = clickedFeature
          this.isEditingFeature = false // Reset edit mode when selecting a new feature
        } else {
          // Clear selection if clicking on empty space
          this.selectedFeature = null
          this.isEditingFeature = false
        }
      })

      // Change cursor when hovering over features
      this.map.on('pointermove', (event) => {
        const hasFeature = this.map.forEachFeatureAtPixel(
          event.pixel,
          (feature) => feature,
          {
            hitTolerance: 5
          }
        )
        
        this.map.getViewport().style.cursor = hasFeature ? 'pointer' : ''
      })

      // Add debounced zoom change listener
      let zoomChangeTimeout = null
      this.map.getView().on('change:resolution', () => {
        // Clear any existing timeout
        if (zoomChangeTimeout) {
          clearTimeout(zoomChangeTimeout)
        }
        
        // Debounce the zoom change handling
        zoomChangeTimeout = setTimeout(() => {
          const newZoom = this.map.getView().getZoom()
          if (newZoom !== this.currentZoom) {
            // Clear cache when zoom changes significantly to ensure data reload
            const zoomDiff = Math.abs(newZoom - (this.currentZoom || 0))
            if (zoomDiff >= 3) {
              console.log(`Significant zoom change detected (${zoomDiff} levels), clearing cache`)
              this.loadedBounds.clear()
            }
            
            // Clear cache when zooming out to world view (zoom <= 3)
            if (newZoom <= 3) {
              console.log(`Zooming to world view (zoom: ${newZoom}), clearing cache`)
              this.loadedBounds.clear()
            }
            
          }
        }, 100) // 100ms debounce
      })

      // Clear cache if map starts at world view level
      const initialZoom = this.map.getView().getZoom()
      if (initialZoom <= 3) {
        console.log(`Map initialized at world view (zoom: ${initialZoom}), clearing cache`)
        this.loadedBounds.clear()
      }

      // Event listeners removed - cache functionality eliminated

      // Return a promise that resolves when the map is ready
      return new Promise((resolve) => {
        // Wait for the map to be fully rendered
        this.map.once('rendercomplete', () => {
          resolve()
        })
      })
    },

    async getUserLocation() {
      try {
        const response = await fetch(this.LOCATION_API_URL)
        const data = await response.json()

        if (data.success && data.location) {
          this.userLocation = data.location
          console.log('User location detected:', this.userLocation)
        } else {
          console.warn('Failed to get user location:', data.error || 'Unknown error')
          console.log('Using default location: Denver, Colorado')
          this.userLocation = null
        }
      } catch (error) {
        console.error('Error fetching user location:', error)
        this.userLocation = null
      }
    },

    getInitialMapConfig() {
      return MapUtils.getInitialMapConfig(this.userLocation)
    },


    getLocationDisplayName() {
      return MapUtils.getLocationDisplayName(this.userLocation)
    },

    getBoundingBoxKey(extent, zoom) {
      return MapUtils.getBoundingBoxKey(extent, zoom)
    },

    getBoundingBoxString(extent) {
      return MapUtils.getBoundingBoxString(extent, toLonLat)
    },

    async loadDataForCurrentView() {
      // Cancel any existing request
      if (this.currentAbortController) {
        this.currentAbortController.abort()
        console.log('Cancelled previous API request')
      }

      const view = this.map.getView()
      const extent = view.calculateExtent()
      const zoom = view.getZoom()

      // Check if we already loaded data for this area
      const bboxKey = this.getBoundingBoxKey(extent, zoom)
      
      // Check if this is a world-wide extent by calculating the geographic extent
      const [minX, minY, maxX, maxY] = extent
      const minLonLat = toLonLat([minX, minY])
      const maxLonLat = toLonLat([maxX, maxY])
      const lonSpan = maxLonLat[0] - minLonLat[0]
      const latSpan = maxLonLat[1] - minLonLat[1]
      
      // Consider it world-wide if longitude span > 300 degrees or latitude span > 150 degrees
      const isWorldWide = lonSpan > 300 || latSpan > 150 || zoom <= 2
      
      if (isWorldWide) {
        console.log(`World-wide extent detected (zoom: ${zoom}, lonSpan: ${lonSpan.toFixed(1)}, latSpan: ${latSpan.toFixed(1)}), clearing cache and forcing reload`)
        this.loadedBounds.clear()
        // Don't return here - continue to load data
      } else if (this.loadedBounds.has(bboxKey)) {
        // For normal extents, use normal caching
        return
      }

      // Create new AbortController for this request
      this.currentAbortController = new AbortController()
      this.isLoading = true

      try {
        const bboxString = this.getBoundingBoxString(extent)
        const roundedZoom = Math.round(zoom) // Round to integer for API compatibility
        const url = `${this.API_BASE_URL}?bbox=${bboxString}&zoom=${roundedZoom}`

        const response = await fetch(url, {
          signal: this.currentAbortController.signal
        })
        const data = await response.json()

        if (data.success && data.data.features) {
          // Log error if fallback mechanism was used
          if (data.fallback_used) {
            console.error(
              'ERROR: Spatial query returned suspiciously few results for large extent. ' +
              'Fell back to world-wide query. This may indicate a problem with the spatial query or extent calculation.'
            )
          }

          // Show warning if features were limited by configuration
          if (data.warning) {
            console.warn(data.warning)
          }

          // Use original data without simplification
          const processedData = data.data

          // Add new features to the vector source
          const features = new GeoJSON().readFeatures(processedData, {
            featureProjection: 'EPSG:3857',
            dataProjection: 'EPSG:4326'
          })

          // Manually preserve properties from the original GeoJSON data
          features.forEach((feature, index) => {
            const originalFeature = data.data.features[index]

            if (originalFeature && originalFeature.properties) {
              // Set the properties explicitly
              // Note: Individual properties are accessible via feature.get('properties')
              // Setting them individually is redundant and adds overhead
              feature.set('properties', originalFeature.properties)
            }

            // Set the geojson_hash for efficient duplicate detection
            if (originalFeature && originalFeature.geojson_hash) {
              feature.set('geojson_hash', originalFeature.geojson_hash)
            }

          })

          // Filter out features that already exist in the vector source using hash-based detection
          const existingFeatures = this.vectorSource ? this.vectorSource.getFeatures() : []

          // Create a Set of existing feature hashes for O(1) lookup
          const existingFeatureHashes = new Set()
          existingFeatures.forEach(feature => {
            const hash = feature.get('geojson_hash')
            if (hash) {
              existingFeatureHashes.add(hash)
            }
          })

          // Filter new features using hash-based duplicate detection (O(n) instead of O(n²))
          const newFeatures = features.filter(newFeature => {
            const newHash = newFeature.get('geojson_hash')
            if (!newHash) {
              // If no hash is available, keep the feature (shouldn't happen with backend fix)
              console.warn('Feature missing geojson_hash, keeping feature')
              return true
            }

            // O(1) hash lookup instead of O(n) geometry comparison
            return !existingFeatureHashes.has(newHash)
          })

          if (newFeatures.length > 0) {
            // Add timestamps to new features before adding them to the map
            newFeatures.forEach(feature => {
              this.addFeatureTimestamp(feature)
            })

            if (this.vectorSource) {
              this.vectorSource.addFeatures(newFeatures)
              console.log(`Added ${newFeatures.length} new features (filtered ${features.length - newFeatures.length} duplicates)`)
            }

            // Enforce feature limit after adding new features
            this.enforceFeatureLimit()
          } else {
            console.log(`No new features to add (all ${features.length} were duplicates)`)
          }

          this.loadedBounds.add(bboxKey)

          // Batch feature count update to avoid reactivity overhead
          this.scheduleFeatureCountUpdate()
          this.updateLastUpdateTime()

          // Update current zoom
          this.currentZoom = roundedZoom

          // Update features in extent list after loading new features
          this.debouncedUpdateFeaturesInExtent()

          console.log(`Loaded ${features.length} features for bbox: ${bboxString} (zoom: ${roundedZoom})`)
          if (data.total_features_in_bbox && data.total_features_in_bbox > features.length) {
            console.log(`Note: ${data.total_features_in_bbox - features.length} additional features not shown due to limit (${data.max_features_limit})`)
          }
        } else {
          console.error('Error loading data:', data.error)
        }
      } catch (error) {
        // Don't log errors for aborted requests
        if (error.name === 'AbortError') {
          console.log('Request was cancelled')
        } else {
          console.error('Error fetching data:', error)
        }
      } finally {
        this.isLoading = false
        this.currentAbortController = null
      }
    },

    debouncedLoadData() {
      // Cancel any pending request when starting a new debounced request
      if (this.currentAbortController) {
        this.currentAbortController.abort()
        console.log('Cancelled previous API request due to new view change')
      }

      clearTimeout(this.loadTimeout)
      this.loadTimeout = setTimeout(this.loadDataForCurrentView, 500)
    },

    updateFeatureCount() {
      this.featureCount = this.vectorSource ? this.vectorSource.getFeatures().length : 0
    },

    scheduleFeatureCountUpdate() {
      // Batch feature count updates using nextTick to avoid triggering reactivity on every feature
      if (!this.featureCountUpdatePending) {
        this.featureCountUpdatePending = true
        this.$nextTick(() => {
          this.updateFeatureCount()
          this.featureCountUpdatePending = false
        })
      }
    },

    updateLastUpdateTime() {
      this.lastUpdateTime = new Date().toLocaleTimeString()
    },

    enforceFeatureLimit() {
      if (!this.vectorSource) {
        return
      }

      const features = this.vectorSource.getFeatures()
      if (features.length <= this.MAX_FEATURES) {
        return
      }

      // Sort features by timestamp (oldest first) using plain object
      const featuresWithTimestamps = features.map(feature => {
        const featureId = this.getFeatureId(feature)
        return {
          feature,
          featureId,
          timestamp: this.featureTimestamps[featureId] || 0
        }
      }).sort((a, b) => a.timestamp - b.timestamp)

      // Calculate how many features to remove
      const featuresToRemove = features.length - this.MAX_FEATURES

      // Remove oldest features
      for (let i = 0; i < featuresToRemove; i++) {
        const {feature, featureId} = featuresWithTimestamps[i]
        this.vectorSource.removeFeature(feature)
        delete this.featureTimestamps[featureId]
      }

      console.log(`Removed ${featuresToRemove} oldest features to maintain limit of ${this.MAX_FEATURES}`)
      this.scheduleFeatureCountUpdate()
      // Update feature list after removing features
      this.debouncedUpdateFeaturesInExtent()
    },

    addFeatureTimestamp(feature) {
      const featureId = this.getFeatureId(feature)
      this.featureTimestamps[featureId] = Date.now()
    },

    clearAllFeatures() {
      // Clear all features and their timestamps
      if (this.vectorSource) {
        this.vectorSource.clear()
      }
      this.featureTimestamps = {}
      this.loadedBounds.clear()
      this.scheduleFeatureCountUpdate()
      console.log('Cleared all features from the map')
    },

    // Handle featureId from URL parameter
    async handleUrlFeatureId() {
      // Check for featureId in query parameters
      const featureId = this.$route.query.featureId
      if (!featureId) {
        return
      }

      try {
        // Fetch the feature from the API
        const response = await fetch(`${APIHOST}/api/data/feature/${featureId}/`)
        if (!response.ok) {
          console.error(`Failed to fetch feature ${featureId}: ${response.statusText}`)
          this.removeFeatureIdFromUrl()
          return
        }

        const data = await response.json()
        if (!data.success || !data.feature) {
          console.error(`Feature ${featureId} not found or access denied`)
          this.removeFeatureIdFromUrl()
          return
        }

        // Convert GeoJSON to OpenLayers feature
        const format = new GeoJSON()
        const geojsonData = data.feature.geojson
        
        const feature = format.readFeature(geojsonData, {
          featureProjection: 'EPSG:3857',
          dataProjection: 'EPSG:4326'
        })

        // Preserve properties from the GeoJSON data
        const properties = geojsonData && geojsonData.properties 
          ? { ...geojsonData.properties }
          : {}
        
        // Add the _id to properties
        properties._id = featureId
        feature.set('properties', properties)

        // Preserve geojson_hash if available
        if (data.feature.geojson_hash) {
          feature.set('geojson_hash', data.feature.geojson_hash)
        }

        // Add feature to the map if it's not already there
        // Check if feature already exists in vector source
        const existingFeatures = this.vectorSource.getFeatures()
        let featureToZoom = existingFeatures.find(f => {
          const props = f.get('properties') || {}
          return props._id === featureId
        })

        if (!featureToZoom) {
          // Add the feature to the map
          this.vectorSource.addFeature(feature)
          this.addFeatureTimestamp(feature)
          featureToZoom = feature
        }

        // Wait a bit for the map to render, then zoom to the feature
        await this.$nextTick()
        setTimeout(() => {
          this.zoomToFeature(featureToZoom)
          // Remove the featureId parameter from URL
          this.removeFeatureIdFromUrl()
        }, 100)
      } catch (error) {
        console.error(`Error fetching feature ${featureId}:`, error)
        this.removeFeatureIdFromUrl()
      }
    },

    // Remove featureId parameter from URL
    removeFeatureIdFromUrl() {
      const query = { ...this.$route.query }
      delete query.featureId
      this.$router.replace({
        path: this.$route.path,
        query: query
      })
    },

    // Cache functionality removed
  },

  async mounted() {
    // Initialize featureTimestamps as empty object
    this.featureTimestamps = {}

    // Fetch tile sources configuration first
    await this.fetchTileSources()

    // Wait for map to be fully initialized before loading data
    await this.initializeMap()

    // Update map layer to use the selected source (in case it's not the default OSM)
    if (this.selectedLayer && this.tileSources.length > 0) {
      this.updateMapLayer(this.selectedLayer)
    }

    // Initial data load - now the map is ready
    await this.loadDataForCurrentView()

    // Initial feature list update
    this.updateFeaturesInExtent()

    // Check for featureId in URL parameters and zoom to it
    // Wait a bit for the map to fully render before zooming
    await this.$nextTick()
    setTimeout(() => {
      this.handleUrlFeatureId()
    }, 200)
  },

  beforeUnmount() {
    // Clean up
    if (this.loadTimeout) {
      clearTimeout(this.loadTimeout)
    }

    // Clean up feature list update timeout
    if (this.featureListUpdateTimeout) {
      clearTimeout(this.featureListUpdateTimeout)
    }

    // Cancel any pending API request
    if (this.currentAbortController) {
      this.currentAbortController.abort()
      console.log('Cancelled API request on component unmount')
    }

    // Clear feature timestamps
    this.featureTimestamps = {}
  }
}
</script>

<style scoped>
/* Hide OpenLayers attribution */
:deep(.ol-attribution) {
  display: none;
}
</style>
