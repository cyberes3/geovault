<template>
  <div class="w-full h-full flex">
    <!-- Left Sidebar - Feature List -->
    <FeatureListSidebar
        :class="['transition-opacity duration-300', (publicShareError || loadError) ? 'opacity-50 pointer-events-none' : 'opacity-100']"
        :features="featuresInExtent"
        :available-tags="availableTags"
        @feature-click="zoomToFeature"
        @tag-filter-change="handleTagFilterChange"
    />

    <!-- Center - Map -->
    <div class="flex-1 bg-gray-50 relative">
      <div class="relative w-full h-full">
        <!-- Map -->
        <div ref="mapContainer" :class="['w-full h-full transition-opacity duration-300', (publicShareError || loadError) ? 'opacity-50 pointer-events-none' : 'opacity-100']"></div>

        <!-- Error Overlay for Invalid Share -->
        <transition name="fade">
          <div v-if="publicShareError" class="absolute inset-0 z-50 flex items-center justify-center bg-gray-900 bg-opacity-50">
            <div class="bg-white rounded-lg shadow-xl p-6 max-w-md mx-4 select-none">
              <div class="flex items-center space-x-3 mb-4">
                <svg class="w-8 h-8 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
                <h3 class="text-lg font-semibold text-gray-900">Invalid Share Link</h3>
              </div>
              <p class="text-gray-700 mb-4">{{ publicShareError }}</p>
              <p class="text-sm text-gray-500">The share link may have been deleted or expired.</p>
            </div>
          </div>
        </transition>

        <!-- Error Overlay for Loading Failures -->
        <transition name="fade">
          <div v-if="loadError" class="absolute inset-0 z-50 flex items-center justify-center bg-gray-900 bg-opacity-50">
            <div class="bg-white rounded-lg shadow-xl p-6 max-w-md mx-4 select-none">
              <div class="flex items-center space-x-3 mb-4">
                <svg class="w-8 h-8 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
                <h3 class="text-lg font-semibold text-gray-900">Error Loading Map</h3>
              </div>
              <p class="text-gray-700 mb-4">{{ loadError }}</p>
              <p class="text-sm text-gray-500">Please try refreshing the page or check your connection.</p>
            </div>
          </div>
        </transition>

        <!-- Public Share Title (shown when viewing a public share) -->
        <div v-if="isPublicShareMode" class="absolute top-4 right-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10">
          <div class="flex items-center space-x-2">
            <svg class="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path d="M8.684 13.342C8.885 12.938 9 12.482 9 12c0-.482-.115-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z" stroke-linecap="round" stroke-linejoin="round"
                    stroke-width="2"></path>
            </svg>
            <span v-if="(publicShareTag || publicShareCollectionName) && !publicShareError" class="text-sm font-medium text-gray-900">
              <template v-if="publicShareTag">Shared Tag: {{ publicShareTag }}</template>
              <template v-else-if="publicShareCollectionName">Shared Collection: {{ publicShareCollectionName }}</template>
            </span>
          </div>
        </div>

        <!-- Collection Title (shown when viewing a collection) -->
        <div v-if="collectionName && !isPublicShareMode" class="absolute top-4 right-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10">
          <div class="flex items-center space-x-2">
            <svg class="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
            </svg>
            <span class="text-sm font-medium text-gray-900">Collection: {{ collectionName }}</span>
          </div>
        </div>

        <!-- Loading Indicator -->
        <div v-show="isLoading" :class="['absolute', 'right-4', 'bg-white', 'bg-opacity-90', 'px-4', 'py-2', 'rounded-lg', 'shadow-md', 'z-10', isPublicShareMode ? 'top-20' : 'top-4']">
          <div class="flex items-center space-x-2">
            <div class="animate-spin rounded-full h-4 w-4 border-2 border-transparent" style="border-bottom-color: #4B6BAB;"></div>
            <span class="text-sm text-gray-700">Loading data...</span>
          </div>
        </div>

        <!-- Feature Info Box or Edit Box -->
        <FeatureInfoBox
            v-if="!isEditingFeature && !isPublicShareMode && !showElevationProfile"
            :feature="selectedFeature"
            @close="selectedFeature = null"
            @edit="handleEditFeature"
            @zoom="zoomToFeature(selectedFeature)"
            @show-profile="showElevationProfile = true"
        />
        <FeatureInfoBox
            v-if="!isEditingFeature && isPublicShareMode && !showElevationProfile"
            :feature="selectedFeature"
            :show-edit-button="false"
            @close="selectedFeature = null"
            @zoom="zoomToFeature(selectedFeature)"
            @show-profile="showElevationProfile = true"
        />
        <FeatureEditBox
            v-if="isEditingFeature && !isPublicShareMode"
            :feature="selectedFeature"
            :available-tags="availableTags"
            @cancel="handleCancelEdit"
            @deleted="handleFeatureDeleted"
            @saved="handleFeatureSaved"
        />

        <!-- Elevation Profile Dialog -->
        <ElevationProfileDialog
            v-if="showElevationProfile"
            :feature="selectedFeature"
            @close="handleElevationProfileClose"
            @hover-point="handleHoverPoint"
            @hover-clear="handleHoverClear"
            @click-point="handleClickPoint"
          />

        <!-- Feature Selection Popup (for overlapping features) -->
        <FeatureSelectionPopup
            :features="overlappingFeatures"
            :position="popupPosition"
            :visible="showFeaturePopup"
            @close="showFeaturePopup = false"
            @select="handleFeatureSelect"
        />
      </div>
    </div>

    <!-- Right Sidebar - Map Controls -->
    <MapControlsSidebar
        :allowed-options="publicShareAllowedOptions"
        :class="['transition-opacity duration-300', (publicShareError || loadError) ? 'opacity-50 pointer-events-none' : 'opacity-100']"
        :feature-count="featureCount"
        :location-display-name="getLocationDisplayName()"
        :max-features="MAX_FEATURES"
        :selected-layer="selectedLayer"
        :tile-sources="tileSources"
        :user-location="userLocation"
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
import {Point} from 'ol/geom'
import {Style, Circle, Fill, Stroke} from 'ol/style'
import {Feature} from 'ol'
import {MapUtils} from '@/utils/map/MapUtils'
import {APIHOST} from '@/config.js'
import FeatureListSidebar from './FeatureListSidebar.vue'
import MapControlsSidebar from './MapControlsSidebar.vue'
import FeatureInfoBox from './FeatureInfoBox.vue'
import FeatureEditBox from './FeatureEditBox.vue'
import FeatureSelectionPopup from './FeatureSelectionPopup.vue'
import ElevationProfileDialog from './ElevationProfileDialog.vue'

export default {
  name: 'GeoJsonMap',
  components: {
    FeatureListSidebar,
    MapControlsSidebar,
    FeatureInfoBox,
    FeatureEditBox,
    FeatureSelectionPopup,
    ElevationProfileDialog
  },
  mixins: [],
  computed: {
    isPublicShareMode() {
      return this.$route.path === '/mapshare' && this.$route.query.id
    },
    shareId() {
      return this.$route.query.id || null
    },
    collectionId() {
      return this.$route.query.collection || null
    },
    // Get allowed options based on mode (public share or authenticated)
    publicShareAllowedOptions() {
      if (this.isPublicShareMode) {
        return {
          mapLayer: true, // Allow map layer selection
          featureStats: false, // Hide feature stats for public users
          userLocation: false // Hide user location for public users
        }
      }
      // For authenticated users, allow all options
      return {
        mapLayer: true,
        featureStats: true,
        userLocation: true
      }
    }
  },
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
      SHARE_API_BASE_URL: '/api/data/sharing/public/',
      LOCATION_API_URL: '/api/data/location/user/',
      TILE_SOURCES_API_URL: '/api/tiles/sources/',
      MAX_FEATURES: 5000, // Maximum number of features to keep on the map
      featureTimestamps: {}, // Use plain object instead of Map
      featureIdCounter: 0, // Counter to generate unique IDs for features
      currentZoom: null,
      featureCountUpdatePending: false, // Flag to batch feature count updates
      isEditingFeature: false, // Track if we're in edit mode
      showElevationProfile: false, // Track if elevation profile dialog is shown
      hoverMarker: null, // Temporary marker for chart hover
      publicShareError: null, // Error message for invalid public share
      loadError: null, // Error message for loading failures and exceptions
      publicShareTag: null, // Tag name for public share
      publicShareCollectionName: null, // Collection name for public share
      publicShareInfo: null, // Cached share info (share_type, tag, collection_name, etc.)
      // Allowed options for public share users
      publicShareAllowedOptions: {
        mapLayer: true, // Allow map layer selection
        featureStats: false, // Hide feature stats for public users
        userLocation: false // Hide user location for public users
      },
      // Feature selection popup state
      overlappingFeatures: [], // Array of features at click point
      popupPosition: {x: 0, y: 0, containerWidth: 0, containerHeight: 0}, // Pixel coordinates and container dimensions for popup positioning
      showFeaturePopup: false, // Boolean flag to show/hide popup
      // Tag filter state
      isTagFilterActive: false, // Track if tag filtering is active
      tagFilteredFeatures: [], // Store filtered features from tag filter
      // Collection state
      collectionName: null, // Name of the collection being viewed
      isCollectionMode: false, // Track if collection filtering is active
      // Available tags for autocomplete and filtering
      availableTags: [] // Tags fetched once and shared with child components
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
          client_config: {type: 'osm'}
        }]
      }
    },
    async fetchAvailableTags() {
      // Only fetch tags for authenticated users (not public share mode)
      if (this.isPublicShareMode) {
        return
      }
      try {
        const response = await fetch(`${APIHOST}/api/data/features/by-tag/`)
        const data = await response.json()
        
        if (response.ok) {
          // Get user tags and system tags separately
          const userTags = data.user_tags ? Object.keys(data.user_tags).sort() : []
          const systemTags = data.system_tags ? Object.keys(data.system_tags).sort() : []
          
          // Combine with user tags first, then system tags (like TagPicker expects)
          this.availableTags = [...userTags, ...systemTags]
        } else {
          console.error('Failed to fetch tags:', data.error || 'Unknown error')
          this.availableTags = []
        }
      } catch (error) {
        console.error('Error fetching available tags:', error)
        this.availableTags = []
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
      const featuresInVicinity = allFeatures.filter(feature => {
        const geometry = feature.getGeometry()
        if (!geometry) return false
        return geometry.intersectsExtent(bufferedExtent)
      })

      // Sort features alphabetically by name
      featuresInVicinity.sort((a, b) => {
        const nameA = this.getFeatureName(a).toLowerCase()
        const nameB = this.getFeatureName(b).toLowerCase()
        return nameA.localeCompare(nameB)
      })

      this.featuresInExtent = featuresInVicinity
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

      // Ensure feature is on the map (for search results that might not be loaded)
      // Find existing feature by reference or ID to avoid duplicates
      if (this.vectorSource) {
        const allFeatures = this.vectorSource.getFeatures()
        
        // Check if feature already exists (by reference or ID)
        let existingFeature = allFeatures.includes(feature) ? feature : null
        if (!existingFeature) {
          const featureId = feature.get('properties')?._id
          if (featureId) {
            existingFeature = allFeatures.find(f => f.get('properties')?._id === featureId)
          }
        }
        
        // If not found, try to add it (with error handling for race conditions)
        if (!existingFeature) {
          try {
            this.vectorSource.addFeature(feature)
            this.addFeatureTimestamp(feature)
            existingFeature = feature
          } catch (error) {
            // Feature was likely added between checks, find it again
            existingFeature = allFeatures.includes(feature) ? feature : null
            if (!existingFeature) {
              const featureId = feature.get('properties')?._id
              if (featureId) {
                existingFeature = this.vectorSource.getFeatures().find(f => f.get('properties')?._id === featureId)
              }
            }
            // Fallback to original feature if still not found
            if (!existingFeature) {
              existingFeature = feature
            }
          }
        }
        
        feature = existingFeature
      }

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
      this.handleHoverClear() // Clear hover marker when feature changes
    },

    // Handle feature selection from popup
    handleFeatureSelect(feature) {
      this.selectedFeature = feature
      this.isEditingFeature = false
      this.showFeaturePopup = false
    },

    // Handle tag filter change from sidebar
    handleTagFilterChange(filteredFeatures) {
      if (!this.vectorSource) {
        return
      }

      if (filteredFeatures === null) {
        // Clear tag filter - restore normal behavior
        this.isTagFilterActive = false
        this.tagFilteredFeatures = []

        // Clear the map and reload data for current view
        this.vectorSource.clear()
        this.loadedBounds.clear()
        this.featureTimestamps = {}
        this.loadDataForCurrentView()
        return
      }

      // Apply tag filter
      this.isTagFilterActive = true
      this.tagFilteredFeatures = filteredFeatures

      // Clear current features
      this.vectorSource.clear()
      this.featureTimestamps = {}

      // Add filtered features to map
      if (filteredFeatures.length > 0) {
        // Add timestamps to features
        filteredFeatures.forEach(feature => {
          this.addFeatureTimestamp(feature)
        })

        this.vectorSource.addFeatures(filteredFeatures)
        console.log(`Applied tag filter: ${filteredFeatures.length} features`)

        // Update feature count
        this.updateFeatureCount()

        // Update features in extent list
        this.updateFeaturesInExtent()
      } else {
        // No features match the filter
        this.updateFeatureCount()
        this.updateFeaturesInExtent()
        console.log('Tag filter: No features match the selected tags')
      }
    },

    // Handle collection filter
    async handleCollectionFilter(collectionId) {
      if (!this.vectorSource || !collectionId) {
        return
      }

      try {
        // Fetch collection info only (name for display)
        const collectionResponse = await fetch(`${APIHOST}/api/data/collections/${collectionId}/`)

        if (!collectionResponse.ok) {
          throw new Error('Failed to load collection')
        }

        const collectionData = await collectionResponse.json()

        if (collectionResponse.ok && collectionData.collection) {
          this.collectionName = collectionData.collection.name
          this.isCollectionMode = true

          // Clear current features and loaded bounds to start fresh with bbox loading
          this.vectorSource.clear()
          this.featureTimestamps = {}
          this.loadedBounds.clear()

          // Trigger bbox loading for current view
          // This will use the collection parameter automatically via loadDataForCurrentView
          await this.loadDataForCurrentView()

          console.log(`Collection filter enabled: ${this.collectionName} (ID: ${collectionId})`)
        } else {
          throw new Error('Failed to load collection info')
        }
      } catch (error) {
        console.error('Error loading collection:', error)
        this.collectionName = null
        this.isCollectionMode = false
        // Clear collection filter and restore normal behavior
        if (this.vectorSource) {
          this.vectorSource.clear()
          this.loadedBounds.clear()
          this.featureTimestamps = {}
          this.loadDataForCurrentView()
        }
      }
    },

    // Handle edit button click
    handleEditFeature() {
      // Disable editing in public share mode
      if (this.isPublicShareMode) {
        return
      }
      this.isEditingFeature = true
    },

    // Handle cancel edit
    handleCancelEdit() {
      this.isEditingFeature = false
    },

    /**
     * Convert hex color to RGB array [r, g, b]
     */
    hexToRgb(hex) {
      const shorthandRegex = /^#?([a-f\d])([a-f\d])([a-f\d])$/i
      hex = hex.replace(shorthandRegex, (m, r, g, b) => r + r + g + g + b + b)
      const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex)
      return result
        ? [parseInt(result[1], 16), parseInt(result[2], 16), parseInt(result[3], 16)]
        : null
    },

    /**
     * Get the inverse/opposite color of a hex color
     */
    getInverseColor(hex) {
      const rgb = this.hexToRgb(hex)
      if (!rgb) {
        return '#000000' // Default to black if conversion fails
      }
      // Invert each RGB component
      const inverted = rgb.map(c => 255 - c)
      // Convert back to hex
      return '#' + inverted.map(c => {
        const hex = c.toString(16)
        return hex.length === 1 ? '0' + hex : hex
      }).join('')
    },

    // Handle hover point from elevation profile chart
    handleHoverPoint(coordinate) {
      if (!this.vectorSource || !coordinate || !Array.isArray(coordinate) || coordinate.length < 2) {
        return
      }

      // Remove existing hover marker if any
      if (this.hoverMarker) {
        this.vectorSource.removeFeature(this.hoverMarker)
        this.hoverMarker = null
      }

      // Get feature stroke color
      let markerColor = '#ff0000' // Default red
      if (this.selectedFeature) {
        const properties = this.selectedFeature.get('properties') || {}
        const strokeColor = properties.stroke || '#ff0000'
        markerColor = strokeColor
      }

      // Calculate inverse color for border
      const borderColor = this.getInverseColor(markerColor)

      // Create new Point feature at the coordinate
      const point = new Point(fromLonLat([coordinate[0], coordinate[1]]))
      const hoverFeature = markRaw(new Feature({
        geometry: point
      }))

      // Set style for hover marker (using feature's stroke color, with inverse color border)
      hoverFeature.setStyle(new Style({
        image: new Circle({
          radius: 5.5,
          fill: new Fill({
            color: markerColor
          }),
          stroke: new Stroke({
            color: borderColor,
            width: 1
          })
        })
      }))

      // Add to vector source
      this.vectorSource.addFeature(hoverFeature)
      this.hoverMarker = hoverFeature
    },

    // Handle hover clear from elevation profile chart
    handleHoverClear() {
      if (this.hoverMarker && this.vectorSource) {
        this.vectorSource.removeFeature(this.hoverMarker)
        this.hoverMarker = null
      }
    },

    // Handle click point from elevation profile chart - center map on that point
    handleClickPoint(coordinate) {
      if (!this.map || !coordinate || !Array.isArray(coordinate) || coordinate.length < 2) {
        return
      }

      const view = this.map.getView()
      const center = fromLonLat([coordinate[0], coordinate[1]])
      
      // Get current zoom level to preserve it
      const currentZoom = view.getZoom()
      
      // Center the map without changing zoom
      view.setCenter(center)
    },

    // Handle elevation profile dialog close
    handleElevationProfileClose() {
      this.showElevationProfile = false
      this.handleHoverClear() // Clear hover marker when dialog closes
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
            if (response.ok && data.feature) {
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
                    ? {...geojsonData.properties}
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
                
                // Refresh available tags so suggestions are up to date
                await this.fetchAvailableTags()
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
      
      // Refresh available tags so suggestions are up to date
      await this.fetchAvailableTags()
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
      // Get user location first (skip for public share mode)
      if (!this.isPublicShareMode) {
        await this.getUserLocation()
      }

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
          zoom: mapConfig.zoom,
          maxZoom: 20
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
        // Collect all features at the click point
        const featuresAtPixel = []
        const seenFeatures = new WeakSet() // Track unique features by object reference
        const seenIds = new Set() // Track features by ID for deduplication

        this.map.forEachFeatureAtPixel(
            event.pixel,
            (feature) => {
              // Deduplicate features: check by object reference first, then by ID
              if (seenFeatures.has(feature)) {
                return false // Skip duplicate feature object
              }

              const properties = feature.get('properties') || {}
              const featureId = properties._id

              // If feature has an ID, check if we've seen this ID before
              if (featureId) {
                if (seenIds.has(featureId)) {
                  return false // Skip duplicate feature with same ID
                }
                seenIds.add(featureId)
              }

              // Mark this feature as seen and add to list
              seenFeatures.add(feature)
              featuresAtPixel.push(feature)
              return false // Continue collecting all features
            },
            {
              hitTolerance: 12 // Increased tolerance for easier clicking
            }
        )

        // Close popup if clicking elsewhere
        if (this.showFeaturePopup) {
          this.showFeaturePopup = false
        }

        // Close elevation profile dialog if open when clicking
        if (this.showElevationProfile) {
          this.showElevationProfile = false
          this.handleHoverClear() // Clear hover marker when dialog closes
        }

        if (featuresAtPixel.length === 0) {
          // No features: Clear selection
          this.selectedFeature = null
          this.isEditingFeature = false
          this.handleHoverClear() // Clear hover marker when selection is cleared
        } else if (featuresAtPixel.length === 1) {
          // Single feature: Select directly (existing behavior)
          this.selectedFeature = featuresAtPixel[0]
          this.isEditingFeature = false
        } else {
          // Multiple features: Show popup
          // Close info box if it's open
          this.selectedFeature = null
          this.isEditingFeature = false
          this.overlappingFeatures = featuresAtPixel
          // Get pixel coordinates relative to map container
          const mapContainer = this.$refs.mapContainer
          const containerRect = mapContainer ? mapContainer.getBoundingClientRect() : {width: window.innerWidth, height: window.innerHeight}
          this.popupPosition = {
            x: event.pixel[0],
            y: event.pixel[1],
            containerWidth: containerRect.width,
            containerHeight: containerRect.height
          }
          this.showFeaturePopup = true
        }
      })

      // Change cursor when hovering over features
      this.map.on('pointermove', (event) => {
        const hasFeature = this.map.forEachFeatureAtPixel(
            event.pixel,
            (feature) => feature,
            {
              hitTolerance: 12 // Match click tolerance
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
          let newZoom = this.map.getView().getZoom()
          
          // Defensive check: clamp zoom to 20 if it somehow exceeds the limit
          if (newZoom > 20) {
            console.warn(`Zoom level ${newZoom} exceeds maximum of 20, clamping to 20`)
            this.map.getView().setZoom(20)
            newZoom = 20
          }
          
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

      // Update map size to ensure it renders properly
      setTimeout(() => {
        if (this.map) {
          this.map.updateSize()
          console.log('Map size updated after initialization')
        }
      }, 100)

      // Return a promise that resolves when the map is ready
      return new Promise((resolve) => {
        // Wait for the map to be fully rendered
        this.map.once('rendercomplete', () => {
          console.log('Map rendercomplete event fired')
          // Update size again after render
          setTimeout(() => {
            if (this.map) {
              this.map.updateSize()
              console.log('Map size updated after rendercomplete')
            }
          }, 100)
          resolve()
        })

        // Fallback timeout in case rendercomplete doesn't fire
        setTimeout(() => {
          console.log('Map initialization timeout, proceeding anyway')
          // Update size before resolving
          if (this.map) {
            this.map.updateSize()
            console.log('Map size updated after timeout')
          }
          resolve()
        }, 1000)
      })
    },

    async getUserLocation() {
      try {
        const response = await fetch(this.LOCATION_API_URL)
        const data = await response.json()

        if (response.ok && data.location) {
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

    // Handle public share errors by setting error message and disabling map interactions
    handlePublicShareError(errorMessage) {
      this.publicShareError = errorMessage || 'Invalid share link'
      // Disable map interactions
      if (this.map) {
        this.map.getInteractions().forEach(interaction => {
          interaction.setActive(false)
        })
      }
    },

    async loadDataForCurrentView() {
      // Skip loading if tag filter is active (tag filter manages its own features)
      if (this.isTagFilterActive) {
        return
      }

      // Note: Collection mode now uses bbox loading, so we don't skip it here

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
      this.loadError = null // Clear any previous load errors

      try {
        const bboxString = this.getBoundingBoxString(extent)
        const roundedZoom = Math.round(zoom) // Round to integer for API compatibility

        let url, response, data

        if (this.isPublicShareMode) {
          // Get share info (cached after first call)
          if (!this.publicShareInfo || this.publicShareInfo.share_id !== this.shareId) {
            const infoUrl = `/api/data/sharing/public/info/${this.shareId}/`
            const infoResponse = await fetch(infoUrl, {
              signal: this.currentAbortController.signal
            })

            if (!infoResponse.ok) {
              const errorData = await infoResponse.json()
              this.handlePublicShareError(errorData.error || 'Invalid share link')
              return
            }

            const infoData = await infoResponse.json()
            // Response is successful if we got here (infoResponse.ok is true)

            // Cache the share info
            this.publicShareInfo = {
              share_id: this.shareId,
              share_type: infoData.share_type,
              tag: infoData.tag || null,
              collection_name: infoData.collection_name || null,
              collection_id: infoData.collection_id || null,
              include_tags: infoData.include_tags || false
            }

            // Store tag/collection name for display
            if (infoData.share_type === 'tag') {
              this.publicShareTag = infoData.tag
              this.publicShareCollectionName = null
            } else if (infoData.share_type === 'collection') {
              this.publicShareCollectionName = infoData.collection_name
              this.publicShareTag = null
            }
          }

          // Use appropriate endpoint based on share_type
          if (this.publicShareInfo.share_type === 'tag') {
            url = `${this.SHARE_API_BASE_URL}${this.shareId}/?bbox=${bboxString}&zoom=${roundedZoom}`
          } else if (this.publicShareInfo.share_type === 'collection') {
            url = `/api/data/sharing/public/collection/${this.shareId}/?bbox=${bboxString}&zoom=${roundedZoom}`
          } else {
            this.publicShareError = 'Unknown share type'
            return
          }

          response = await fetch(url, {
            signal: this.currentAbortController.signal
          })

          data = await response.json()
        } else {
          // Use regular endpoint
          url = this.API_BASE_URL
          // Build URL with optional collection parameter
          url = `${url}?bbox=${bboxString}&zoom=${roundedZoom}`
          if (this.isCollectionMode && this.collectionId) {
            // collectionId is a computed property from route query
            url += `&collection=${this.collectionId}`
          }

          response = await fetch(url, {
            signal: this.currentAbortController.signal
          })
          data = await response.json()
        }

        // Store the tag or collection name for display (from public share response)
        if (this.isPublicShareMode) {
          if (data.tag) {
            this.publicShareTag = data.tag
            this.publicShareCollectionName = null
          } else if (data.collection_name) {
            this.publicShareCollectionName = data.collection_name
            this.publicShareTag = null
          }
        }

        // Check if the response indicates an error
        if (!response.ok) {
          if (this.isPublicShareMode) {
            this.handlePublicShareError(data.error || 'Failed to load shared features.')
          } else {
            this.loadError = data.error || 'Failed to load map data.'
          }
          console.error('Error loading data:', data.error)
          return
        }

        if (response.ok && data.data && data.data.features) {
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
          this.loadError = error.message || 'Failed to load map data. Please try again.'
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
        if (!response.ok || !data.feature) {
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
            ? {...geojsonData.properties}
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
      const query = {...this.$route.query}
      delete query.featureId
      this.$router.replace({
        path: this.$route.path,
        query: query
      })
    },

    // Cache functionality removed
  },

  watch: {
    '$route'(to, from) {
      // Watch for route changes, especially share ID and collection changes

      // Handle collection query parameter changes
      const newCollectionId = to.query.collection
      const oldCollectionId = from?.query?.collection

      if (newCollectionId !== oldCollectionId) {
        if (newCollectionId) {
          // Collection ID changed or added, load the collection
          console.log('Collection ID changed, loading collection:', newCollectionId)
          // Wait for map to be ready if it's not yet
          if (this.map && this.vectorSource) {
            this.handleCollectionFilter(newCollectionId)
          } else {
            // If map isn't ready yet, wait for it
            this.$nextTick(() => {
              if (this.map && this.vectorSource) {
                this.handleCollectionFilter(newCollectionId)
              }
            })
          }
        } else if (oldCollectionId) {
          // Collection parameter was removed, clear collection mode
          console.log('Collection parameter removed, clearing collection mode')
          this.collectionName = null
          this.isCollectionMode = false
          // Clear the map and reload data for current view
          if (this.vectorSource) {
            this.vectorSource.clear()
            this.loadedBounds.clear()
            this.featureTimestamps = {}
            this.loadDataForCurrentView()
          }
        }
      }

      // Handle public share mode
      if (this.isPublicShareMode) {
        const newShareId = to.query.id
        const oldShareId = from?.query?.id

        // If share ID changed, reload the share data
        if (newShareId !== oldShareId) {
          console.log('Share ID changed, reloading share data:', newShareId)
          // Reset state
          this.publicShareError = null
          this.publicShareTag = null
          this.publicShareCollectionName = null
          this.publicShareInfo = null // Clear cached share info

          // Re-enable map interactions in case they were disabled
          if (this.map) {
            this.map.getInteractions().forEach(interaction => {
              interaction.setActive(true)
            })
          }

          // Clear existing features
          if (this.vectorSource) {
            this.vectorSource.clear()
          }
          this.featureTimestamps = {}
          this.loadedBounds.clear()
          this.selectedFeature = null
          this.isEditingFeature = false

          // Reload the new share data using bbox loading
          // The map view change will trigger loadDataForCurrentView automatically
          if (this.map && this.vectorSource) {
            this.debouncedLoadData()
          } else {
            // If map isn't ready yet, wait for it
            this.$nextTick(() => {
              if (this.map && this.vectorSource) {
                this.debouncedLoadData()
              }
            })
          }
        }
      }
    }
  },
  async created() {
    // App.vue handles all authentication checks before components are created
    // For public shares, App.vue allows unauthenticated access
    // For authenticated routes, App.vue ensures user is logged in before components are created
  },
  async mounted() {
    // Initialize featureTimestamps as empty object
    this.featureTimestamps = {}

    // Ensure map container is available
    await this.$nextTick()
    if (!this.$refs.mapContainer) {
      console.error('Map container not available')
      return
    }

    // Fetch tile sources configuration first
    await this.fetchTileSources()

    // Fetch available tags for child components (only for authenticated users)
    if (!this.isPublicShareMode) {
      await this.fetchAvailableTags()
    }

    // Wait for map to be fully initialized before loading data
    try {
      await this.initializeMap()
      console.log('Map initialized successfully')
    } catch (error) {
      console.error('Error initializing map:', error)
      this.loadError = error.message || 'Failed to initialize map. Please refresh the page.'
      return
    }

    // Update map layer to use the selected source (in case it's not the default OSM)
    if (this.selectedLayer && this.tileSources.length > 0) {
      this.updateMapLayer(this.selectedLayer)
    }

    // Check for collection query parameter
    if (this.collectionId) {
      await this.handleCollectionFilter(this.collectionId)
      // Don't load normal data if collection is loaded
    } else {
      // Check for featureId in URL (existing functionality)
      await this.handleUrlFeatureId()

      // Initial data load - now the map is ready (only if not in collection mode)
      console.log('Loading data for current view, isPublicShareMode:', this.isPublicShareMode, 'shareId:', this.shareId)
      await this.loadDataForCurrentView()
    }

    // Update map size to ensure it renders properly (especially important for public shares)
    await this.$nextTick()
    if (this.map) {
      setTimeout(() => {
        this.map.updateSize()
        console.log('Map size updated after data load')
      }, 100)
    }

    // Initial feature list update
    this.updateFeaturesInExtent()

    // Check for featureId in URL parameters and zoom to it (only in non-public mode)
    if (!this.isPublicShareMode) {
      await this.$nextTick()
      setTimeout(() => {
        this.handleUrlFeatureId()
      }, 200)
    }
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

    // Clear hover marker
    this.handleHoverClear()

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

/* Fade transition for error overlay */
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.5s ease;
}

.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

.fade-enter-to, .fade-leave-from {
  opacity: 1;
}

/* Disable text selection on error overlay */
.select-none {
  -webkit-user-select: none;
  -moz-user-select: none;
  -ms-user-select: none;
  user-select: none;
}
</style>
