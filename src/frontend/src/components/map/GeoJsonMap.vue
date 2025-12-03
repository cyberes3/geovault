<template>
  <div class="w-full h-full flex">
    <!-- Left Sidebar - Feature List -->
    <FeatureListSidebar
        :key="sidebarKey"
        :available-tags="availableTags"
        :class="['transition-opacity duration-300', (publicShareError || loadError) ? 'opacity-50 pointer-events-none' : 'opacity-100']"
        :features="featuresInExtent"
        :initial-selected-tags="initialSelectedTags"
        :is-initial-load="isMapInitializing || (isDataLoading && isInitialLoad)"
        :is-mobile-open="activeMobileSidebar === 'features'"
        :can-hide-features="isMainMapRoute && !isPublicShareMode && !!$store.state.userInfo"
        @close="activeMobileSidebar = null"
        @feature-click="zoomToFeature"
        @feature-hide="handleHideFeature"
        @tag-filter-change="handleTagFilterChange"
        @tag-filter-loading-change="isTagFilterLoading = $event"
    />

    <!-- Center - Map -->
    <div class="flex-1 w-full bg-gray-50 relative overflow-hidden">
      <!-- Mobile Controls Bar (placeholder) -->
      <div class="lg:hidden bg-white border-b border-gray-200 px-4 py-3 flex justify-between items-center">
        <button
            class="p-2 text-gray-600 hover:text-gray-900 rounded-md hover:bg-gray-100 focus:outline-none"
            title="Features"
            @click="activeMobileSidebar = 'features'"
        >
          <ListBulletIcon class="w-6 h-6"/>
        </button>
        <div class="text-sm font-medium text-gray-900 max-w-[50%] text-center leading-tight flex flex-col items-center justify-center">
          <template v-if="isPublicShareMode">
            <div v-if="publicShareTag" class="flex items-center justify-center gap-1 w-full">
              <ShareIcon class="w-4 h-4 text-blue-500 flex-shrink-0"/>
              <span class="line-clamp-2">Tag: {{ publicShareTag }}</span>
            </div>
            <div v-else-if="publicShareCollectionName" class="flex items-center justify-center gap-1 w-full">
              <ShareIcon class="w-4 h-4 text-blue-500 flex-shrink-0"/>
              <span class="line-clamp-2">Collection: {{ publicShareCollectionName }}</span>
            </div>
            <div v-else class="flex items-center justify-center gap-1 w-full">
              <ShareIcon class="w-4 h-4 text-blue-500 flex-shrink-0"/>
              <span>Shared Map</span>
            </div>
          </template>
          <template v-else>
            <span class="line-clamp-2">{{ collectionName || 'Map' }}</span>
          </template>
        </div>
        <button
            class="p-2 text-gray-600 hover:text-gray-900 rounded-md hover:bg-gray-100 focus:outline-none"
            title="Map Controls"
            @click="activeMobileSidebar = 'controls'"
        >
          <Cog6ToothIcon class="w-6 h-6"/>
        </button>
      </div>
      <div class="relative w-full h-full">
        <!-- Map -->
        <div
            ref="mapContainer"
            :class="[
            'w-full h-full transition-opacity duration-300',
            (publicShareError || loadError) ? 'opacity-50 pointer-events-none' : 'opacity-100'
          ]"
        ></div>

        <!-- Error Overlay for Invalid Share -->
        <MapErrorOverlay
            :message="publicShareError"
            :visible="!!publicShareError"
            subtext="The share link may have been deleted or expired."
            title="Invalid Share Link"
        />

        <!-- Error Overlay for Loading Failures -->
        <MapErrorOverlay
            :message="loadError"
            :visible="!!loadError"
            subtext="Please try refreshing the page or check your connection."
            title="Error Loading Map"
        />

        <!-- Loading Indicator -->
        <MapLoadingIndicator
            :is-loading="isMapInitializing || isDataLoading || isRestoring || isTagFilterLoading"
            :is-public-share-mode="isPublicShareMode"
        />

        <!-- Feature Info Box or Edit Box -->
        <FeatureInfoBox
            v-if="!isEditingFeature && !isPublicShareMode && !showElevationProfile"
            :feature="selectedFeature"
            @close="selectedFeature = null"
            @download="handleDownloadFeatureKmz"
            @edit="handleEditFeature"
            @zoom="zoomToFeature(selectedFeature)"
            @show-profile="showElevationProfile = true"
        />
        <FeatureInfoBox
            v-if="!isEditingFeature && isPublicShareMode && !showElevationProfile"
            :feature="selectedFeature"
            :share-id="shareId"
            :show-download-button="publicShareInfo && publicShareInfo.allow_downloads"
            :show-edit-button="false"
            @close="selectedFeature = null"
            @download="handleDownloadFeatureKmz"
            @zoom="zoomToFeature(selectedFeature)"
            @show-profile="showElevationProfile = true"
        />
        <FeatureEditBox
            v-if="isEditingFeature && !isPublicShareMode"
            :available-tags="availableTags"
            :feature="selectedFeature"
            :can-hide-feature="isMainMapRoute && !!$store.state.userInfo"
            :initial-hidden="hiddenFeatureIds.includes(String(selectedFeature?.get('properties')?.database_id || ''))"
            @cancel="handleCancelEdit"
            @deleted="handleFeatureDeleted"
            @saved="handleFeatureSaved"
            @visibility-change="handleEditBoxVisibilityChange"
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

      <!-- Center to User Location Button -->
      <button
          v-if="userLocation && !isPublicShareMode"
          class="absolute z-10 bottom-4 left-4 p-2 bg-white border border-gray-200 rounded shadow-md hover:bg-gray-50 text-gray-700 transition-colors"
          title="Center map to your location"
          @click="centerToUserLocation"
      >
        <HomeIcon class="w-5 h-5"/>
      </button>
    </div>

      <!-- Right Sidebar - Map Controls -->
      <MapControlsSidebar
        :allow-downloads="publicShareInfo && publicShareInfo.allow_downloads"
        :allowed-options="publicShareAllowedOptions"
        :class="['transition-opacity duration-300', (publicShareError || loadError) ? 'opacity-50 pointer-events-none' : 'opacity-100']"
        :feature-count="featureCount"
        :hidden-features="hiddenFeatureSummaries"
        :is-mobile-open="activeMobileSidebar === 'controls'"
        :is-public-share-mode="isPublicShareMode"
        :location-display-name="getLocationDisplayName()"
        :max-features="MAX_FEATURES"
        :selected-layer="selectedLayer"
        :share-id="shareId"
        :tile-sources="tileSources"
        :user-location="userLocation"
        :view-context="viewContext"
        :can-manage-hidden="isMainMapRoute && !isPublicShareMode && !!$store.state.userInfo"
        @close="activeMobileSidebar = null"
        @layer-change="updateMapLayer"
        @unhide-feature="handleUnhideFeature"
        @unhide-all="handleUnhideAllHidden"
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
import {getFeatureIconStyle} from '@/utils/map/styleUtils'
import {getFeatureTextStyle} from '@/utils/map/textUtils'
import {getInitialMapConfig, getLocationDisplayName} from '@/utils/map/mapConfigUtils'
import {getBoundingBoxKey, getBoundingBoxString} from '@/utils/map/coordinateUtils'
import { sortTagsByPriority, sortUserTagsAlphabetically, isSystemTag } from '@/utils/tagUtils.js'
import {getInverseColor} from '@/utils/map/colorUtils'
import {useGeoData} from './useGeoData'
import {getCookie} from '@/assets/js/auth.js'
import {getUnitPreference} from '@/utils/units'
import {APIHOST, MAP_CONFIG} from '@/config.js'

// Components
import FeatureListSidebar from './FeatureListSidebar.vue'
import MapControlsSidebar from './MapControlsSidebar.vue'
import FeatureInfoBox from './FeatureInfoBox.vue'
import FeatureEditBox from './FeatureEditBox.vue'
import FeatureSelectionPopup from './FeatureSelectionPopup.vue'
import ElevationProfileDialog from './ElevationProfileDialog.vue'
import MapErrorOverlay from './MapErrorOverlay.vue'
import MapLoadingIndicator from './MapLoadingIndicator.vue'
import {HomeIcon, ExclamationCircleIcon, ShareIcon, FolderIcon, ListBulletIcon, Cog6ToothIcon} from '@heroicons/vue/24/outline'

export default {
  name: 'GeoJsonMap',
  components: {
    FeatureListSidebar,
    MapControlsSidebar,
    FeatureInfoBox,
    FeatureEditBox,
    FeatureSelectionPopup,
    ElevationProfileDialog,
    MapErrorOverlay,
    MapLoadingIndicator,
    HomeIcon,
    ExclamationCircleIcon,
    ShareIcon,
    FolderIcon,
    ListBulletIcon,
    Cog6ToothIcon
  },
  mixins: [],
  computed: {
    isMainMapRoute() {
      // Base map page (/#/map) without collection or tag view context
      const path = this.$route.path
      const hasCollection = !!this.$route.query.collection
      const hasTag = !!this.$route.query.tag
      return path === '/map' && !hasCollection && !hasTag
    },
    hiddenFeatureIds() {
      const features = this.$store.state.hiddenFeatures || []
      if (!Array.isArray(features)) return []
      // Extract IDs from the {id, name} objects
      return features.map(f => String(f.id))
    },
    hiddenFeatureSummaries() {
      // Simply return the hiddenFeatures from the store, which now includes names and geometry types
      const features = this.$store.state.hiddenFeatures || []
      if (!Array.isArray(features)) return []
      return features.map(f => ({
        id: String(f.id),
        name: f.name || null,
        geometry_type: f.geometry_type || null
      }))
    },
    isPublicShareMode() {
      return this.$route.path === '/mapshare' && this.$route.query.id
    },
    shareId() {
      return this.$route.query.id || null
    },
    collectionId() {
      return this.$route.query.collection || null
    },
    initialSelectedTags() {
      const tag = this.$route.query.tag
      if (!tag) {
        return []
      }
      // Vue Router may return a single string or an array for repeated query params
      return Array.isArray(tag) ? tag : [tag]
    },
    // Get the current view context (tag, collection, or null)
    viewContext() {
      // Priority: public share > collection > tag
      if (this.isPublicShareMode) {
        if (this.publicShareTag) {
          return { type: 'tag', name: this.publicShareTag, isPublicShare: true }
        } else if (this.publicShareCollectionName) {
          return { type: 'collection', name: this.publicShareCollectionName, isPublicShare: true }
        }
        return null
      }

      if (this.collectionName) {
        return { type: 'collection', name: this.collectionName, isPublicShare: false }
      }

      // Check for tag in URL query
      const tag = this.$route.query.tag
      if (tag) {
        return { type: 'tag', name: Array.isArray(tag) ? tag[0] : tag, isPublicShare: false }
      }

      return null
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
      // Loading state
      isMapInitializing: false, // Initial mount/restore work before first data load
      isDataLoading: false, // Any network-backed data load for the map
      isInitialLoad: true, // Track if this is the first successful/attempted data load
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
      API_BASE_URL: '/api/geojson/',
      SHARE_API_BASE_URL: '/api/sharing/public/',
      LOCATION_API_URL: '/api/location/user/',
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
      availableTags: [], // Tags fetched once and shared with child components
      isRestoring: false, // Track if map is being restored
      isTagFilterLoading: false, // Track loading for tag-based filtering in sidebar
      sidebarKey: 0, // Force sidebar remount when tag share state changes
      activeMobileSidebar: null, // 'features', 'controls', or null
      mapWasDestroyed: false, // Track if map was fully destroyed for memory reasons
    }
  },
  methods: {
    // Attach geo-data helpers (data loading, feature bookkeeping)
    ...useGeoData(),
    async handleHideFeature(feature) {
      // Account-level hide is only allowed on main /map view for authenticated users (non-public)
      if (!this.isMainMapRoute || this.isPublicShareMode || !this.$store.state.userInfo) {
        return
      }

      if (!feature) {
        return
      }

      const props = feature.get('properties') || {}
      const featureId = props.database_id
      const featureName = props.name
      const geometryType = feature.getGeometry()?.getType()

      if (!featureId) {
        return
      }

      // Import the debounced manager
      const hiddenFeaturesManager = (await import('@/utils/hiddenFeaturesManager.js')).default

      // Optimistic update: immediately update UI
      const optimisticUpdate = () => {
        // Add to store
        this.$store.commit('addHiddenFeature', {
          featureId: String(featureId),
          featureName: featureName || null,
          geometryType: geometryType || null
        })

        // Remove the feature from the map if present
        if (this.vectorSource) {
          const allFeatures = this.vectorSource.getFeatures()
          const toRemove = allFeatures.find(f => {
            const p = f.get('properties') || {}
            return p.database_id === featureId
          })
          if (toRemove) {
            this.vectorSource.removeFeature(toRemove)
          }
        }

        // Clear selection if this was the selected feature
        if (this.selectedFeature) {
          const propsSelected = this.selectedFeature.get('properties') || {}
          if (propsSelected.database_id === featureId) {
            this.selectedFeature = null
            this.isEditingFeature = false
          }
        }

        // Update counts and sidebar list
        this.updateFeatureCount()
        this.debouncedUpdateFeaturesInExtent()
      }

      // Add to debounced bulk update with optimistic callback
      hiddenFeaturesManager.addHidden(featureId, optimisticUpdate)
    },
    async handleEditBoxVisibilityChange(payload) {
      if (!payload || !payload.featureId) {
        return
      }
      if (payload.hidden) {
        // Build a minimal fake feature object with properties so handleHideFeature can reuse logic
        const fakeFeature = {
          get: (key) => {
            if (key === 'properties') {
              return { _id: payload.featureId }
            }
            return null
          }
        }
        await this.handleHideFeature(fakeFeature)
      } else {
        await this.handleUnhideFeature(payload.featureId)
      }
    },
    async handleUnhideFeature(featureId) {
      if (!this.isMainMapRoute || this.isPublicShareMode || !this.$store.state.userInfo) {
        return
      }

      if (!featureId) {
        return
      }

      // Import the debounced manager
      const hiddenFeaturesManager = (await import('@/utils/hiddenFeaturesManager.js')).default

      // Optimistic update: immediately update UI
      const optimisticUpdate = () => {
        // Remove from store
        this.$store.commit('removeHiddenFeature', String(featureId))

        // After un-hiding, reload data for current view so the feature can reappear
        this.loadedBounds.clear()
        this.loadDataForCurrentView()
        this.debouncedUpdateFeaturesInExtent()
      }

      // Add to debounced bulk update with optimistic callback
      hiddenFeaturesManager.removeHidden(featureId, optimisticUpdate)
    },
    async handleUnhideAllHidden() {
      if (!this.isMainMapRoute || this.isPublicShareMode || !this.$store.state.userInfo) {
        return
      }

      try {
        const { clearHiddenFeatures } = await import('@/utils/userSettingsService.js')
        await clearHiddenFeatures()
        // Local cache: clear all hidden features in the store
        this.$store.commit('setHiddenFeatures', [])
      } catch (error) {
        console.error('Error clearing hidden features:', error)
        return
      }

      this.loadedBounds.clear()
      this.loadDataForCurrentView()
      this.debouncedUpdateFeaturesInExtent()
    },
    async withLoading(flagKey, fn) {
      if (!flagKey || typeof fn !== 'function') {
        return
      }
      this[flagKey] = true
      try {
        return await fn()
      } finally {
        this[flagKey] = false
      }
    },
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

          // Get user's default basemap preference from settings
          const userSettings = this.$store.state.userSettings || {}
          const defaultBasemap = userSettings.map?.default_basemap

          // Always check user settings first - if user has a preferred default basemap, use it
          if (defaultBasemap && this.tileSources.find(s => s.id === defaultBasemap)) {
            this.selectedLayer = defaultBasemap
          } else if (!this.selectedLayer || !this.tileSources.find(s => s.id === this.selectedLayer)) {
            // If no user preference or current selection is invalid, fallback to first available tile source
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
        // Set selectedLayer to osm if not already set
        if (!this.selectedLayer) {
          this.selectedLayer = 'osm'
        }
      }
    },
    async fetchAvailableTags() {
      // Only fetch tags for authenticated users
      if (!this.$store.state.userInfo) {
        return
      }
      try {
        const response = await fetch(`${APIHOST}/api/features/by-tag/`)
        const data = await response.json()

        if (response.ok) {
          // Get user tags and system tags separately
          const userTags = data.user_tags ? Object.keys(data.user_tags) : []
          const systemTags = data.system_tags ? Object.keys(data.system_tags) : []

          // Sort user tags alphabetically, system tags by priority
          const sortedUserTags = sortUserTagsAlphabetically(userTags)
          const sortedSystemTags = sortTagsByPriority(systemTags)

          // Combine: user tags first, then system tags
          this.availableTags = [...sortedUserTags, ...sortedSystemTags]
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

        // Handle tile subdomains if provided (e.g., for OpenTopoMap)
        const xyzConfig = {}
        if (clientConfig.tileSubdomains && Array.isArray(clientConfig.tileSubdomains)) {
          // Create array of URLs with each subdomain
          xyzConfig.urls = clientConfig.tileSubdomains.map(subdomain =>
              url.replace('{s}', subdomain)
          )
        } else {
          // Single URL (replace {s} placeholder if present with first subdomain or 'a')
          xyzConfig.url = url.replace('{s}', clientConfig.tileSubdomains?.[0] || 'a')
        }

        const xyzSource = new XYZ(xyzConfig)
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
          const featureId = feature.get('properties')?.database_id
          if (featureId) {
                existingFeature = allFeatures.find(f => f.get('properties')?.database_id === featureId)
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
              const featureId = feature.get('properties')?.database_id
              if (featureId) {
                existingFeature = this.vectorSource.getFeatures().find(f => f.get('properties')?.database_id === featureId)
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

      // Adjust padding for mobile/tablet to position feature in upper half (avoiding info box)
      // On mobile/tablet, the info box is at the bottom and can take up to 60vh
      const isMobile = window.innerWidth < 1024 // Match Tailwind's 'lg' breakpoint for tablets
      let padding
      if (isMobile) {
        // Position feature in upper half: small top padding, large bottom padding
        const viewportHeight = window.innerHeight
        const bottomPadding = Math.floor(viewportHeight * 0.5) // 50% of viewport height
        padding = [50, 50, bottomPadding, 50] // [top, right, bottom, left]
      } else {
        // Desktop: equal padding on all sides
        padding = [50, 50, 50, 50]
      }

      // Fit the view to the feature's extent with padding
      view.fit(extent, {
        padding: padding,
        duration: 500, // Animation duration in milliseconds
        maxZoom: maxZoom // Limit maximum zoom level
      })

      // Show info box for the selected feature
      this.selectedFeature = feature
      this.isEditingFeature = false // Reset edit mode when selecting a new feature
      this.handleHoverClear() // Clear hover marker when feature changes
    },

    // Center map to user's IP location
    centerToUserLocation() {
      if (!this.map || !this.userLocation) return

      const latitude = this.userLocation.latitude
      const longitude = this.userLocation.longitude

      if (latitude == null || longitude == null) {
        console.warn('User location coordinates are not available')
        return
      }

      const view = this.map.getView()
      const center = fromLonLat([longitude, latitude])
      const currentZoom = view.getZoom()

      // If zoomed in too far, zoom out to a reasonable level
      // Otherwise, just pan to the location without changing zoom
      const maxReasonableZoom = 12
      const reasonableZoom = 10

      if (currentZoom > maxReasonableZoom) {
        // Zoom out and pan
        view.animate({
          center: center,
          zoom: reasonableZoom,
          duration: 500
        })
      } else {
        // Just pan, preserve current zoom
        view.animate({
          center: center,
          duration: 500
        })
      }
    },

    // Handle feature selection from popup
    handleFeatureSelect(feature) {
      this.selectedFeature = feature
      this.isEditingFeature = false
      this.showFeaturePopup = false
    },

    // Download selected feature as KMZ (authenticated mode only)
    handleDownloadFeatureKmz() {
      const feature = this.selectedFeature
      if (!feature) {
        return
      }
      const properties = feature.get('properties') || {}
      const featureId = properties.database_id
      if (!featureId) {
        return
      }

      let url = `${APIHOST}/api/export-kmz?feature=${encodeURIComponent(featureId)}`

      // If in public share mode, include share_id parameter
      if (this.isPublicShareMode && this.shareId) {
        url += `&share=${encodeURIComponent(this.shareId)}`
      }

      window.open(url, '_blank')
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
        // Update feature count
        this.updateFeatureCount()

        // Update features in extent list
        this.updateFeaturesInExtent()
      } else {
        // No features match the filter
        this.updateFeatureCount()
        this.updateFeaturesInExtent()
      }
    },

    // Handle collection filter
    async handleCollectionFilter(collectionId) {
      if (!this.vectorSource || !collectionId) {
        return
      }

      try {
        // Fetch collection info only (name for display)
        const collectionResponse = await fetch(`${APIHOST}/api/collections/${collectionId}/`)

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
          await this.loadDataForCurrentView()
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
      const borderColor = getInverseColor(markerColor)

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
      const featureId = this.selectedFeature?.get('properties')?.database_id
      if (featureId && this.vectorSource) {
        try {
          // Fetch the updated feature
          const response = await fetch(`${APIHOST}/api/feature/${featureId}/`)
          if (response.ok) {
            const data = await response.json()
            if (response.ok && data.feature) {
              // Find the existing feature in the vector source by ID
              const existingFeatures = this.vectorSource.getFeatures()
              const existingFeature = existingFeatures.find(f => {
                const props = f.get('properties') || {}
                return props.database_id === featureId
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
                properties.database_id = featureId
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
      const featureId = this.selectedFeature?.get('properties')?.database_id

      // Remove the deleted feature from vector source if it exists
      if (featureId && this.vectorSource) {
        const existingFeatures = this.vectorSource.getFeatures()
        const featureToRemove = existingFeatures.find(f => {
          const props = f.get('properties') || {}
          return props.database_id === featureId
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
        style: (feature, resolution) => {
          // Get setting from store, default to true if not set
          const userSettings = this.$store.state.userSettings || {}
          const replaceIconsLowZoom = userSettings.map?.replace_icons_low_zoom !== undefined 
            ? userSettings.map.replace_icons_low_zoom 
            : true
          return getFeatureIconStyle(feature, resolution, replaceIconsLowZoom)
        },
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
        style: (feature, resolution) => getFeatureTextStyle(feature, resolution),
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
        controls: [],
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
              const featureId = properties.database_id

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
            // Zoom level exceeds maximum, clamping to 20
            this.map.getView().setZoom(20)
            newZoom = 20
          }

          if (newZoom !== this.currentZoom) {
            // Clear cache when zoom changes significantly to ensure data reload
            const zoomDiff = Math.abs(newZoom - (this.currentZoom || 0))
            if (zoomDiff >= 3) {
              this.loadedBounds.clear()
            }

            // Clear cache when zooming out to world view (zoom <= 3)
            if (newZoom <= 3) {
              this.loadedBounds.clear()
            }

          }
        }, 100) // 100ms debounce
      })

      // Clear cache if map starts at world view level
      const initialZoom = this.map.getView().getZoom()
      if (initialZoom <= 3) {
        this.loadedBounds.clear()
      }

      // Event listeners removed - cache functionality eliminated

      // Update map size to ensure it renders properly
      setTimeout(() => {
        if (this.map) {
          this.map.updateSize()
        }
      }, 100)

      // Return a promise that resolves when the map is ready
      return new Promise((resolve) => {
        // Wait for the map to be fully rendered
        this.map.once('rendercomplete', () => {
          // Update size again after render
          setTimeout(() => {
            if (this.map) {
              this.map.updateSize()
            }
          }, 100)
          resolve()
        })

        // Fallback timeout in case rendercomplete doesn't fire
        setTimeout(() => {
          // Update size before resolving
          if (this.map) {
            this.map.updateSize()
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

          // Log location information
          const location = data.location
          const locationString = [
            location.city,
            location.state || location.state_code,
            location.country || location.country_code
          ].filter(Boolean).join(', ') || 'Unknown location'

          const coordinates = location.latitude && location.longitude
              ? `${location.latitude.toFixed(4)}, ${location.longitude.toFixed(4)}`
              : 'No coordinates'

          console.log('📍 User Location:', {
            location: locationString,
            coordinates: coordinates,
            usingFallback: location.is_default === true,
            maxmindAvailable: data.maxmind_available !== false
          })
        } else {
          console.warn('Failed to get user location:', data.error || 'Unknown error')
          this.userLocation = null
        }
      } catch (error) {
        console.error('Error fetching user location:', error)
        this.userLocation = null
      }
    },

    getInitialMapConfig() {
      return getInitialMapConfig(this.userLocation)
    },


    getLocationDisplayName() {
      return getLocationDisplayName(this.userLocation)
    },

    getBoundingBoxKey(extent, zoom) {
      return getBoundingBoxKey(extent, zoom)
    },

    getBoundingBoxString(extent) {
      return getBoundingBoxString(extent, toLonLat)
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


    // Handle featureId from URL parameter
    async handleUrlFeatureId() {
      // Check for featureId in query parameters
      const featureId = this.$route.query.featureId
      if (!featureId) {
        return
      }

      try {
        // Fetch the feature from the API
        const response = await fetch(`${APIHOST}/api/feature/${featureId}/`)
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
        properties.database_id = featureId
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
          return props.database_id === featureId
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

    // Lightweight cleanup that should always run when navigating away
    cleanupOnNavigateAway() {
      const previousFeatureCount = this.featureCount

      // Clear all features but keep tile layer and map instance unless heavy cleanup is needed
      if (this.vectorSource) {
        this.vectorSource.clear()
      }

      // Reset map zoom to default view
      if (this.map) {
        const view = this.map.getView()
        if (view && this.userLocation && this.userLocation.latitude && this.userLocation.longitude) {
          // Reset to user's location at default zoom
          view.animate({
            center: fromLonLat([this.userLocation.longitude, this.userLocation.latitude]),
            zoom: 8,
            duration: 0 // Instant reset, no animation
          })
        } else {
          // If no user location, reset to world view
          view.animate({
            center: [0, 0],
            zoom: 2,
            duration: 0
          })
        }
      }

      // Reset feature-related state
      this.featuresInExtent = []
      this.featureTimestamps = {}
      this.loadedBounds.clear()
      this.selectedFeature = null
      this.isEditingFeature = false
      this.showElevationProfile = false

      // Clean up any pending timeouts
      if (this.loadTimeout) {
        clearTimeout(this.loadTimeout)
        this.loadTimeout = null
      }
      if (this.featureListUpdateTimeout) {
        clearTimeout(this.featureListUpdateTimeout)
        this.featureListUpdateTimeout = null
      }

      // Cancel any pending API request
      if (this.currentAbortController) {
        this.currentAbortController.abort()
        this.currentAbortController = null
      }

      // Clear hover marker and related transient state
      this.handleHoverClear()

      // For very large maps, also destroy heavy map resources to free memory
      this.destroyMapResources(previousFeatureCount)

      // After cleanup, reset feature counters
      this.featureCount = 0
      this.featureCountUpdatePending = false
    },

    // Destroy map and resources to free up memory
    destroyMapResources(featureCountOverride = null) {
      const effectiveFeatureCount = featureCountOverride !== null ? featureCountOverride : this.featureCount

      // Check if we need to destroy the map to free up memory
      if (effectiveFeatureCount > MAP_CONFIG.DESTROY_MAP_THRESHOLD) {

        // Clear layer references
        this.vectorLayer = null
        this.textLayer = null
        this.tileLayer = null

        // Clear data caches
        this.loadedBounds.clear()
        this.featuresInExtent = []

        // Destroy map
        if (this.map) {
          this.map.setTarget(null)
          this.map = null
        }

        // Remember that the map was fully destroyed so we can restore it on re-activation
        this.mapWasDestroyed = true
      }
    },

    // Restore map after being destroyed
    async restoreMap() {
      if (this.map) return

      this.isMapInitializing = true
      this.isRestoring = true

      // Ensure map container is available
      await this.$nextTick()
      if (!this.$refs.mapContainer) {
        console.error('Map container not available for restore')
        this.isMapInitializing = false
        this.isRestoring = false
        return
      }

      try {
        // Re-initialize map
        await this.initializeMap()

        // Restore layer selection
        if (this.selectedLayer && this.tileSources.length > 0) {
          this.updateMapLayer(this.selectedLayer)
        }

        // Reload data
        if (this.collectionId) {
          await this.handleCollectionFilter(this.collectionId)
        } else {
          await this.loadDataForCurrentView()
        }

        // Update map size
        await this.$nextTick()
        if (this.map) {
          setTimeout(() => {
            this.map.updateSize()
          }, 100)
        }

        // Initial feature list update
        this.updateFeaturesInExtent()

      } catch (error) {
        console.error('Error restoring map:', error)
        this.loadError = error.message || 'Failed to restore map'
      } finally {
        this.isMapInitializing = false
        this.isRestoring = false
      }
    }
  },

  watch: {
    // Watch for unit preference changes (no controls to update)
    '$store.state.userSettings.account.units': {
      handler(newUnits) {
        // Controls removed - no action needed
      }
    },
    // Watch for replace icons low zoom setting changes
    '$store.state.userSettings.map.replace_icons_low_zoom': {
      handler() {
        // Trigger style refresh when setting changes
        if (this.vectorLayer) {
          // Force style recalculation by triggering a change on all features
          const features = this.vectorSource.getFeatures()
          features.forEach(feature => feature.changed())
        }
      }
    },
    // Watch for default basemap setting changes
    '$store.state.userSettings.map.default_basemap': {
      handler(newBasemap) {
        // If map is already initialized and tile sources are loaded, update the basemap
        if (this.map && this.tileSources.length > 0 && newBasemap) {
          // Check if the new basemap is valid
          const isValidBasemap = this.tileSources.find(s => s.id === newBasemap)
          if (isValidBasemap && this.selectedLayer !== newBasemap) {
            this.updateMapLayer(newBasemap)
          }
        }
      }
    },
    // Watch for user info changes (handle late login/auth check)
    '$store.state.userInfo': {
      handler(newUserInfo) {
        if (newUserInfo) {
          this.fetchAvailableTags()
        }
      }
    },
    '$route'(to, from) {
      // Watch for route changes, especially share ID and collection changes

      // Handle featureId query parameter changes (for "View on Map" links)
      const newFeatureId = to.query.featureId
      const oldFeatureId = from?.query?.featureId

      if (newFeatureId && newFeatureId !== oldFeatureId) {
        // FeatureId parameter added or changed, zoom to the feature
        this.handleUrlFeatureId()
      }

      // Handle collection query parameter changes
      const newCollectionId = to.query.collection
      const oldCollectionId = from?.query?.collection

      if (newCollectionId !== oldCollectionId) {
        if (newCollectionId) {
          // Collection ID changed or added, load the collection
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
    this.isMapInitializing = true

    // Initialize featureTimestamps as empty object
    this.featureTimestamps = {}

    // Ensure map container is available
    await this.$nextTick()
    if (!this.$refs.mapContainer) {
      console.error('Map container not available')
      this.isMapInitializing = false
      return
    }

    // Fetch tile sources and available tags in parallel
    const initPromises = [this.fetchTileSources()]

    // Fetch available tags for child components (only for authenticated users)
    if (this.$store.state.userInfo) {
      initPromises.push(this.fetchAvailableTags())
    }

    await Promise.all(initPromises)

    // Wait for map to be fully initialized before loading data
    try {
      await this.initializeMap()
    } catch (error) {
      console.error('Error initializing map:', error)
      this.loadError = error.message || 'Failed to initialize map. Please refresh the page.'
      this.isMapInitializing = false
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
      await this.loadDataForCurrentView()
    }

    // Update map size to ensure it renders properly (especially important for public shares)
    await this.$nextTick()
    if (this.map) {
      setTimeout(() => {
        this.map.updateSize()
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

    // Mounted flow completed successfully; clear initializing flag
    this.isMapInitializing = false
  },

  activated() {
    // Whenever we re-activate the map view, do a light reset so we don't
    // carry stale features or caches across pages.

    // Clear current features and feature-related state
    if (this.vectorSource) {
      this.vectorSource.clear()
    }
    this.featuresInExtent = []
    this.featureTimestamps = {}
    this.loadedBounds.clear()
    this.selectedFeature = null
    this.isEditingFeature = false
    this.showElevationProfile = false

    // Clear any active tag filter state on the map side. The URL tag query
    // (if present) will re-apply the filter via the sidebar on re-activation.
    this.isTagFilterActive = false
    this.tagFilteredFeatures = []
    this.isTagFilterLoading = false

    // Treat this as a fresh initial load for the current session
    this.isInitialLoad = true

    // If the map was fully destroyed for memory reasons, let restoreMap
    // handle re-initialization and data loading.
    if (this.mapWasDestroyed) {
      this.restoreMap()
      this.mapWasDestroyed = false
      return
    }

    const hasTagQuery = !!this.$route.query.tag
    const hasCollectionQuery = !!this.$route.query.collection

    // Always remount the sidebar so its internal selectedTags state is cleared,
    // and then let the tag query (if present) drive any new selection.
    this.sidebarKey += 1

    // Reload data based on route query parameters
    if (this.map && this.vectorSource) {
      if (hasCollectionQuery) {
        // Collection mode - load collection features
        this.handleCollectionFilter(this.collectionId)
      } else if (!hasTagQuery) {
        // Normal view - reload bbox data
        this.withLoading('isMapInitializing', async () => {
          await this.loadDataForCurrentView()
          // Update feature list after data loads
          this.updateFeaturesInExtent()
        })
      } else {
        // Tag filter mode - sidebar will handle loading via tag filter
        // Just update empty feature list for now
        this.updateFeaturesInExtent()
      }
    }
  },

  deactivated() {
    // Always run lightweight cleanup when navigating away
    this.cleanupOnNavigateAway()
  },

  async beforeUnmount() {
    // Flush any pending hidden feature updates before unmounting
    try {
      const hiddenFeaturesManager = (await import('@/utils/hiddenFeaturesManager.js')).default
      if (hiddenFeaturesManager.hasPending()) {
        await hiddenFeaturesManager.forceFlush()
      }
    } catch (error) {
      console.error('Error flushing pending hidden features:', error)
    }

    // Always run lightweight cleanup before component is destroyed
    this.cleanupOnNavigateAway()
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
