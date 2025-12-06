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
      <!-- Mobile Controls Bar -->
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
            :initial-hidden="hiddenFeatureIds.includes(String(selectedFeature?.properties?.database_id || selectedFeature?.get?.('properties')?.database_id || ''))"
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
        :selected-layer="selectedLayer"
        :share-id="shareId"
        :tile-sources="tileSources"
        :user-location="userLocation"
        :view-context="viewContext"
        :can-manage-hidden="isMainMapRoute && !isPublicShareMode && !!$store.state.userInfo"
        :show-all-labels="showAllLabels"
        :hillshade-available="maptilerConfig && maptilerConfig.isAvailable()"
        :hillshade-enabled="hillshadeEnabled"
        @close="activeMobileSidebar = null"
        @layer-change="updateMapLayer"
        @unhide-feature="handleUnhideFeature"
        @unhide-all="handleUnhideAllHidden"
        @labels-visibility-change="handleLabelsVisibilityChange"
        @hillshade-change="handleHillshadeChange"
    />
  </div>
</template>

<script>
import {markRaw} from 'vue'
import 'maplibre-gl/dist/maplibre-gl.css'
import maplibregl from 'maplibre-gl'
import {GeoJSON} from 'ol/format'
import { LabelMarkerManager } from '@/utils/map/maplibre/labelMarkers.js'
import {getInitialMapConfig, getLocationDisplayName} from '@/utils/map/mapConfigUtils'
import { sortTagsByPriority, sortUserTagsAlphabetically, isSystemTag } from '@/utils/tagUtils.js'
import {getInverseColor} from '@/utils/map/colorUtils'
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
import {
  getBoundingBoxKey,
  getBoundingBoxString,
  getFeatureCoordinates,
  convertMapLibreFeature,
  ensureLayersExist,
  initializeMap,
  setupGeoJsonSource,
  setupMapEventListeners,
  addFeaturesToMap,
  updateMapLayerSource,
  filterPointsOnBorders,
  updateSmallFeatureFlags
} from '@/utils/map/maplibre'
import { getIconSourceUrl, getFeatureIconUrl, loadIconImage, shouldUseIcon } from '@/utils/map/maplibre/featureStyling.js'
import { 
  MapTilerConfig,
  setupTerrain as maptilerSetupTerrain,
  removeTerrain as maptilerRemoveTerrain,
  addHillshade,
  removeHillshade as maptilerRemoveHillshade,
  createTerrainControl
} from '@/utils/map/maplibre/maptilerIntegration.js'

export default {
  name: 'MapLibreMap',
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
  computed: {
    isMainMapRoute() {
      const path = this.$route.path
      const hasCollection = !!this.$route.query.collection
      const hasTag = !!this.$route.query.tag
      return path === '/maplibre' && !hasCollection && !hasTag
    },
    hiddenFeatureIds() {
      const features = this.$store.state.hiddenFeatures || []
      if (!Array.isArray(features)) return []
      return features.map(f => String(f.id))
    },
    hiddenFeatureSummaries() {
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
      return Array.isArray(tag) ? tag : [tag]
    },
    viewContext() {
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

      const tag = this.$route.query.tag
      if (tag) {
        return { type: 'tag', name: Array.isArray(tag) ? tag[0] : tag, isPublicShare: false }
      }

      return null
    },
    publicShareAllowedOptions() {
      if (this.isPublicShareMode) {
        return {
          mapLayer: true,
          featureStats: false,
          userLocation: false
        }
      }
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
      // Loading state
      isMapInitializing: false,
      isDataLoading: false,
      isInitialLoad: true,
      loadedBounds: new Set(),
      lastUpdateTime: null,
      featureCount: 0,
      loadTimeout: null,
      userLocation: null,
      currentAbortController: null,
      selectedLayer: 'osm',
      featuresInExtent: [],
      featureListUpdateTimeout: null,
      featureCleanupTimeout: null,
      selectedFeature: null,
      tileSources: [],
      // Configuration
      API_BASE_URL: '/api/geojson/',
      SHARE_API_BASE_URL: '/api/sharing/public/',
      LOCATION_API_URL: '/api/location/user/',
      TILE_SOURCES_API_URL: '/api/tiles/sources/',
      featureTimestamps: {},
      featureIdCounter: 0,
      currentZoom: null,
      featureCountUpdatePending: false,
      isEditingFeature: false,
      showElevationProfile: false,
      hoverMarker: null,
      publicShareError: null,
      loadError: null,
      publicShareTag: null,
      publicShareCollectionName: null,
      publicShareInfo: null,
      overlappingFeatures: [],
      popupPosition: {x: 0, y: 0, containerWidth: 0, containerHeight: 0},
      showFeaturePopup: false,
      isTagFilterActive: false,
      tagFilteredFeatures: [],
      collectionName: null,
      isCollectionMode: false,
      availableTags: [],
      isRestoring: false,
      isTagFilterLoading: false,
      sidebarKey: 0,
      activeMobileSidebar: null,
      mapWasDestroyed: false,
      showAllLabels: true,
      labelMarkerManager: null,
      handleKeyDown: null, // Keyboard event handler for escape key
      maptilerConfig: null, // MapTilerConfig instance
      terrainEnabled: false, // Current state of terrain (on/off)
      tooltipShown: false, // Track if 3D tooltip has been shown
      terrainTooltipElement: null, // Reference to tooltip element
      hillshadeEnabled: false, // Current state of hillshade (on/off)
      // Saved map state for restoration after destruction
      savedMapCenter: null,
      savedMapZoom: null,
      savedMapPitch: null,
      savedMapBearing: null,
    }
  },
  methods: {
    // Helper method to handle missing icons
    handleStyleImageMissing(iconId) {
      // Extract the URL from the icon ID (format: icon-{encoded_url})
      if (iconId && iconId.startsWith('icon-')) {
        // Reconstruct URL from icon ID
        // The URL was encoded by replacing non-alphanumeric chars with underscores
        // We need to find it from the features on the map
        const source = this.map.getSource('geojson-data')
        if (source && source._data && source._data.features) {
          for (const feature of source._data.features) {
            if (feature.properties && feature.properties['_icon-id'] === iconId) {
              // Found the feature with this icon, get its icon URL
              const iconUrl = getFeatureIconUrl(feature.properties)
              if (iconUrl) {
                const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties)
                // Load the icon
                loadIconImage(this.map, iconId, resolvedUrl).catch(err => {
                  console.warn(`Failed to load missing icon ${iconId}:`, err)
                })
                return
              }
            }
          }
        }
        console.warn(`Could not find feature for missing icon: ${iconId}`)
      }
    },
    // Create and configure map instance with controls and sources
    createMapInstance(mapConfig) {
      // Create MapLibre map
      this.map = markRaw(initializeMap(this.$refs.mapContainer, {
        center: mapConfig.center,
        zoom: mapConfig.zoom,
        pitch: mapConfig.pitch || 0,
        bearing: mapConfig.bearing || 0,
        glyphsUrl: '/api/fonts/{fontstack}/{range}.pbf'
      }))

      // Add navigation controls
      this.map.addControl(
        new maplibregl.NavigationControl({
          visualizePitch: true,
          showCompass: true,
          showZoom: true
        }),
        'top-left'
      )

      // Initialize label marker manager
      this.labelMarkerManager = new LabelMarkerManager(this.map)
      this.labelMarkerManager.setVisibility(this.showAllLabels)

      // Setup GeoJSON source
      setupGeoJsonSource(this.map, () => {
        // Map source loaded
      })

      // Setup all map event handlers
      this.setupMapEventHandlers()
    },
    // Bootstrap method to set up all map event listeners
    setupMapEventHandlers() {
      if (!this.map) return
      
      // Setup basic event listeners (moveend, zoomend, click)
      setupMapEventListeners(this.map, {
        onMoveEnd: () => {
          this.debouncedLoadData()
          this.debouncedUpdateFeaturesInExtent()
        },
        onZoomEnd: () => {
          this.debouncedLoadData()
          this.debouncedUpdateFeaturesInExtent()
          this.reprocessFeaturesForZoom()
        },
        onClick: (e) => {
          // Check if layers exist before querying
          const layersToQuery = ['points', 'point-icons', 'replacement-points', 'lines', 'polygons', 'polygon-outlines']
            .filter(layerId => this.map.getLayer(layerId))
          
          if (layersToQuery.length === 0) return
          
          // Query features with a larger radius (15 pixels) to make clicking easier
          const bbox = [
            [e.point.x - 15, e.point.y - 15],
            [e.point.x + 15, e.point.y + 15]
          ]
          const features = this.map.queryRenderedFeatures(bbox, {
            layers: layersToQuery
          })

          // Filter out label points - they shouldn't be clickable
          const clickableFeatures = features.filter(f => !f.properties?._isLabelPoint)

          // Close elevation profile if open when clicking on another feature or empty space
          if (this.showElevationProfile) {
            this.showElevationProfile = false
            this.handleHoverClear()
          }

          // For replacement points, find the original feature
          const processedFeatures = clickableFeatures.map(f => {
            if (f.properties?._isSmallFeatureReplacement) {
              const originalId = f.properties._originalFeatureId
              if (originalId) {
                const source = this.map.getSource('geojson-data')
                if (source && source._data && source._data.features) {
                  const originalFeature = source._data.features.find(
                    feature => feature.properties?.database_id === originalId && !feature.properties?._isSmallFeatureReplacement
                  )
                  if (originalFeature) return originalFeature
                }
              }
            }
            return f
          })

          // Deduplicate features by database_id
          const uniqueFeatures = []
          const seenIds = new Set()
          
          for (const feature of processedFeatures) {
            const featureId = feature.properties?.database_id
            if (featureId && !seenIds.has(featureId)) {
              seenIds.add(featureId)
              uniqueFeatures.push(feature)
            } else if (!featureId) {
              uniqueFeatures.push(feature)
            }
          }

          if (uniqueFeatures.length === 0) {
            this.selectedFeature = null
            this.isEditingFeature = false
            this.showFeaturePopup = false
            this.handleHoverClear()
          } else if (uniqueFeatures.length === 1) {
            const mlFeature = uniqueFeatures[0]
            this.selectedFeature = markRaw(convertMapLibreFeature(mlFeature))
            this.isEditingFeature = false
            this.showFeaturePopup = false
          } else {
            this.overlappingFeatures = uniqueFeatures.map(f => markRaw(convertMapLibreFeature(f)))
            this.popupPosition = {
              x: e.point.x,
              y: e.point.y,
              containerWidth: this.$refs.mapContainer?.clientWidth || 0,
              containerHeight: this.$refs.mapContainer?.clientHeight || 0
            }
            this.showFeaturePopup = true
          }
        }
      })

      // Add immediate zoom event listener for responsive label and icon updates
      this.map.on('zoom', async () => {
        const currentZoom = this.map.getZoom()
        
        // Update label markers only if labels are visible
        if (this.showAllLabels && this.labelMarkerManager) {
          const source = this.map.getSource('geojson-data')
          if (source && source._data && source._data.features) {
            this.labelMarkerManager.updateMarkers(source._data.features)
          }
        }
        
        // Update small feature flags first (synchronous, fast)
        updateSmallFeatureFlags(this.map, currentZoom)
        
        // Then update icon visibility after small feature flags are set
        await this.reprocessFeaturesForZoom()
      })

      // Add styleimagemissing event handler to load icons on-demand
      this.map.on('styleimagemissing', (e) => {
        this.handleStyleImageMissing(e.id)
      })

      // Add hover event listener to change cursor to pointer over features
      this.map.on('mousemove', (e) => {
        const layersToQuery = ['points', 'point-icons', 'replacement-points', 'lines', 'polygons', 'polygon-outlines']
          .filter(layerId => this.map.getLayer(layerId))
        
        if (layersToQuery.length === 0) return
        
        const bbox = [
          [e.point.x - 5, e.point.y - 5],
          [e.point.x + 5, e.point.y + 5]
        ]
        const features = this.map.queryRenderedFeatures(bbox, {
          layers: layersToQuery
        })

        const hoverableFeatures = features.filter(f => !f.properties?._isLabelPoint)
        this.map.getCanvas().style.cursor = hoverableFeatures.length > 0 ? 'pointer' : ''
      })

      // Reset cursor when leaving the map
      this.map.on('mouseout', () => {
        this.map.getCanvas().style.cursor = ''
      })
    },
    getLocationDisplayName() {
      return getLocationDisplayName(this.userLocation)
    },
    getInitialMapConfig() {
      return getInitialMapConfig(this.userLocation)
    },
    getBoundingBoxKey(bounds, zoom) {
      return getBoundingBoxKey(bounds, zoom)
    },
    getBoundingBoxString(bounds) {
      return getBoundingBoxString(bounds)
    },
    async getUserLocation() {
      try {
        const response = await fetch(this.LOCATION_API_URL)
        const data = await response.json()

        if (response.ok && data.location) {
          this.userLocation = data.location
        } else {
          this.userLocation = null
        }
      } catch (error) {
        console.error('Error fetching user location:', error)
        this.userLocation = null
      }
    },
    async initializeMap() {
      // User location is already fetched in parallel during mounted()
      // No need to fetch it again here

      // Ensure map container is truly available and is an HTMLElement
      if (!this.$refs.mapContainer || !(this.$refs.mapContainer instanceof HTMLElement)) {
        throw new Error('Map container is not available or is not an HTMLElement')
      }

      // Determine initial map center and zoom based on user location
      const mapConfig = this.getInitialMapConfig()

      // Create map instance with controls and event handlers
      this.createMapInstance(mapConfig)
      
      // Resize map to ensure proper rendering after initialization
      if (this.map) {
        setTimeout(() => {
          this.map.resize()
        }, 100)
      }
    },
    convertMapLibreFeature(mlFeature) {
      return convertMapLibreFeature(mlFeature)
    },
    debouncedLoadData() {
      // Skip debounced loads during initial map setup to prevent duplicate API calls
      if (this.isMapInitializing) {
        return
      }
      
      if (this.loadTimeout) {
        clearTimeout(this.loadTimeout)
      }
      this.loadTimeout = setTimeout(() => {
        this.loadDataForCurrentView()
      }, 300)
    },
    debouncedUpdateFeaturesInExtent() {
      if (this.featureListUpdateTimeout) {
        clearTimeout(this.featureListUpdateTimeout)
      }
      this.featureListUpdateTimeout = setTimeout(() => {
        this.updateFeaturesInExtent()
      }, 300)
    },
    async loadDataForCurrentView() {
      if (!this.map) return
      if (this.isTagFilterActive) return

      // For MapLibre, check if map is ready by checking if it has bounds
      // The loaded() check can be too strict - instead check if we can get bounds
      let bounds
      try {
        bounds = this.map.getBounds()
        if (!bounds) return
      } catch (e) {
        // Map not ready yet
        return
      }

      // Cancel any existing request
      if (this.currentAbortController) {
        this.currentAbortController.abort()
      }

      const zoom = this.map.getZoom()
      const bbox = [bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()]
      const bboxKey = this.getBoundingBoxKey(bbox, zoom)

      // Check if already loaded
      if (this.loadedBounds.has(bboxKey)) {
        return
      }

      // Create new AbortController
      this.currentAbortController = new AbortController()
      this.isDataLoading = true
      this.loadError = null

      try {
        const bboxString = this.getBoundingBoxString(bbox)
        const roundedZoom = Math.round(zoom)

        let url, response, data

        if (this.isPublicShareMode) {
          if (!this.shareId) return

          if (!this.publicShareInfo || this.publicShareInfo.share_id !== this.shareId) {
            const infoUrl = `/api/sharing/public/info/${this.shareId}/`
            const infoResponse = await fetch(infoUrl, {
              signal: this.currentAbortController.signal
            })

            if (!infoResponse.ok) {
              const errorData = await infoResponse.json()
              this.handlePublicShareError(errorData.error || 'Invalid share link')
              return
            }

            const infoData = await infoResponse.json()
            this.publicShareInfo = {
              share_id: this.shareId,
              share_type: infoData.share_type,
              tag: infoData.tag || null,
              collection_name: infoData.collection_name || null,
              collection_id: infoData.collection_id || null,
              include_tags: infoData.include_tags || false,
              allow_downloads: infoData.allow_downloads || false
            }

            if (infoData.share_type === 'tag') {
              this.publicShareTag = infoData.tag
              this.publicShareCollectionName = null
            } else if (infoData.share_type === 'collection') {
              this.publicShareCollectionName = infoData.collection_name
              this.publicShareTag = null
            }
          }

          if (this.publicShareInfo.share_type === 'tag') {
            url = `${this.SHARE_API_BASE_URL}${this.shareId}/?bbox=${bboxString}&zoom=${roundedZoom}`
          } else if (this.publicShareInfo.share_type === 'collection') {
            url = `/api/sharing/public/collection/${this.shareId}/?bbox=${bboxString}&zoom=${roundedZoom}`
          } else {
            this.publicShareError = 'Unknown share type'
            return
          }

          response = await fetch(url, {
            signal: this.currentAbortController.signal
          })
          data = await response.json()
        } else {
          url = `${this.API_BASE_URL}?bbox=${bboxString}&zoom=${roundedZoom}`
          if (this.isCollectionMode && this.collectionId) {
            url += `&collection=${this.collectionId}`
          }

          response = await fetch(url, {
            signal: this.currentAbortController.signal
          })
          data = await response.json()
        }

        if (!response.ok) {
          if (this.isPublicShareMode) {
            this.handlePublicShareError(data.error || 'Failed to load shared features.')
          } else {
            this.loadError = data.error || 'Failed to load map data.'
          }
          return
        }

        if (data.data && data.data.features) {
          this.loadedBounds.add(bboxKey)
          this.updateFeatureCount()
          // Use markRaw to prevent Vue from making features reactive
          // This is critical for performance with complex geometries
          const rawData = markRaw(data.data)
          await this.addFeaturesToMap(rawData)
          
          // Update features in extent list after data is loaded
          this.debouncedUpdateFeaturesInExtent()
        }
      } catch (error) {
        if (error.name === 'AbortError') return
        console.error('Error loading data:', error)
        this.loadError = error.message || 'Failed to load map data.'
      } finally {
        this.isDataLoading = false
        this.currentAbortController = null
        if (this.isInitialLoad) {
          this.isInitialLoad = false
        }
      }
    },
    async addFeaturesToMap(geojsonData) {
      const zoom = this.map ? this.map.getZoom() : null
      const userSettings = this.$store.state.userSettings || {}
      const replaceIconsLowZoom = userSettings.map?.replace_icons_low_zoom !== undefined 
        ? userSettings.map.replace_icons_low_zoom 
        : true
      await addFeaturesToMap(this.map, geojsonData, this.showAllLabels, zoom, replaceIconsLowZoom)
      
      // Update label markers only if labels are visible
      // Skip expensive label processing when labels are hidden
      if (this.showAllLabels && this.labelMarkerManager && geojsonData && geojsonData.features) {
        // Get all features from the source (including label points if they exist)
        const source = this.map.getSource('geojson-data')
        if (source && source._data && source._data.features) {
          this.labelMarkerManager.updateMarkers(source._data.features)
        }
      }
    },
    async reprocessFeaturesForZoom() {
      // This function updates icon metadata for features when zoom changes
      // It does NOT re-add features - they're already in the source
      // It only updates rendering properties like _icon-id
      
      if (!this.map || !this.map.getSource('geojson-data')) return
      
      const source = this.map.getSource('geojson-data')
      const currentData = source._data || { type: 'FeatureCollection', features: [] }
      const features = currentData.features || []
      
      if (features.length === 0) return
      
      const zoom = this.map.getZoom()
      const userSettings = this.$store.state.userSettings || {}
      const replaceIconsLowZoom = userSettings.map?.replace_icons_low_zoom !== undefined 
        ? userSettings.map.replace_icons_low_zoom 
        : true
      
      // Update icon metadata for point features
      // Don't modify the feature list - just update properties in-place
      let needsUpdate = false
      
      for (const feature of features) {
        // Skip label points and replacement points
        if (feature.properties?._isLabelPoint || feature.properties?._isSmallFeatureReplacement) {
          continue
        }
        
        const geometryType = feature.geometry?.type
        if (geometryType !== 'Point') continue
        
        // Use getFeatureIconUrl helper to check all icon property names
        const iconUrl = getFeatureIconUrl(feature.properties)
        const hasIcon = iconUrl && iconUrl.trim() !== ''
        
        // Determine if we should show icon or circle based on zoom and settings
        const shouldShowIcon = hasIcon && (!replaceIconsLowZoom || zoom > 8)
        
        if (shouldShowIcon) {
          // Update to show icon
          if (!feature.properties['_icon-id']) {
            // Use same icon ID generation as initial processing
            const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties)
            const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`
            feature.properties['_icon-id'] = iconId
            needsUpdate = true
            
            // Ensure icon is loaded using shared function
            if (this.map && !this.map.hasImage(iconId)) {
              loadIconImage(this.map, iconId, resolvedUrl).catch(err => {
                console.warn(`Failed to load icon ${iconId}:`, err)
              })
            }
          }
        } else {
          // Remove icon metadata to show as circle
          if (feature.properties['_icon-id']) {
            delete feature.properties['_icon-id']
            needsUpdate = true
          }
        }
      }
      
      // Only update source if we actually changed something
      if (needsUpdate) {
        source.setData(markRaw({
          type: 'FeatureCollection',
          features: features.map(f => markRaw(f))
        }))
      }
      
      // Update label markers only if labels are visible
      if (this.showAllLabels && this.labelMarkerManager) {
        this.labelMarkerManager.updateMarkers(features)
      }
    },
    updateFeaturesInExtent() {
      if (!this.map || !this.map.getSource('geojson-data')) {
        this.featuresInExtent = []
        return
      }

      const bounds = this.map.getBounds()
      const source = this.map.getSource('geojson-data')
      const data = source._data || { type: 'FeatureCollection', features: [] }
      const features = data.features || []

      // Filter features in current bounds and exclude label points and replacement points
      const featuresInBounds = features.filter(f => {
        // Skip label points - they're internal features for label rendering
        if (f.properties?._isLabelPoint) return false
        
        // Skip small feature replacement points - they're internal features for rendering
        if (f.properties?._isSmallFeatureReplacement) return false
        
        if (!f.geometry) return false
        const coords = this.getFeatureCoordinates(f.geometry)
        return coords.some(coord => {
          const [lon, lat] = coord
          return lon >= bounds.getWest() && lon <= bounds.getEast() &&
                 lat >= bounds.getSouth() && lat <= bounds.getNorth()
        })
      })

      // Convert to format expected by FeatureListSidebar
      // Use markRaw to prevent Vue reactivity on feature objects for performance
      this.featuresInExtent = featuresInBounds.map(f => markRaw(this.convertMapLibreFeature(f)))
      
      // Clean up features far outside viewport (debounced)
      this.debouncedCleanupDistantFeatures()
    },
    debouncedCleanupDistantFeatures() {
      // Debounce cleanup to avoid running it too often
      if (this.featureCleanupTimeout) {
        clearTimeout(this.featureCleanupTimeout)
      }
      
      this.featureCleanupTimeout = setTimeout(() => {
        this.cleanupDistantFeatures()
      }, 2000) // Run 2 seconds after map movement stops
    },
    cleanupDistantFeatures() {
      if (!this.map || !this.map.getSource('geojson-data')) return
      
      const bounds = this.map.getBounds()
      const source = this.map.getSource('geojson-data')
      const data = source._data || { type: 'FeatureCollection', features: [] }
      const features = data.features || []
      
      // 500 miles = 804,672 meters
      // Convert to degrees (approximate at equator: 1 degree ≈ 111,320 meters)
      const bufferDegrees = 804672 / 111320 // ≈ 7.23 degrees
      
      // Create buffered bounds (500 miles in each direction)
      const bufferedBounds = {
        west: bounds.getWest() - bufferDegrees,
        east: bounds.getEast() + bufferDegrees,
        south: bounds.getSouth() - bufferDegrees,
        north: bounds.getNorth() + bufferDegrees
      }
      
      // Filter to keep only features within the buffer
      const featuresWithinBuffer = features.filter(f => {
        if (!f.geometry) return false
        
        const coords = this.getFeatureCoordinates(f.geometry)
        
        // Check if any coordinate is within the buffered bounds
        return coords.some(coord => {
          const [lon, lat] = coord
          return lon >= bufferedBounds.west && lon <= bufferedBounds.east &&
                 lat >= bufferedBounds.south && lat <= bufferedBounds.north
        })
      })
      
      // Only update if we actually removed features
      if (featuresWithinBuffer.length < features.length) {
        const removed = features.length - featuresWithinBuffer.length
        console.log(`Cleaned up ${removed} features more than 500 miles outside viewport`)
        
        // Update the source with filtered features
        source.setData(markRaw({
          type: 'FeatureCollection',
          features: featuresWithinBuffer.map(f => markRaw(f))
        }))
        
        // Update feature count
        this.updateFeatureCount()
        
        // Update label markers
        if (this.showAllLabels && this.labelMarkerManager) {
          this.labelMarkerManager.updateMarkers(featuresWithinBuffer)
        }
      }
    },
    getFeatureCoordinates(geometry) {
      return getFeatureCoordinates(geometry)
    },
    updateFeatureCount() {
      if (this.featureCountUpdatePending) return
      this.featureCountUpdatePending = true

      this.$nextTick(() => {
        if (this.map && this.map.getSource('geojson-data')) {
          const source = this.map.getSource('geojson-data')
          const data = source._data || { type: 'FeatureCollection', features: [] }
          // Count only real features, not label points
          const realFeatures = (data.features || []).filter(f => !f.properties?._isLabelPoint)
          this.featureCount = realFeatures.length
        }
        this.featureCountUpdatePending = false
      })
    },
    async fetchTileSources() {
      try {
        const response = await fetch(this.TILE_SOURCES_API_URL)
        const data = await response.json()

        if (data.sources && Array.isArray(data.sources)) {
          // Filter out hidden sources (utility sources like terrain/hillshade)
          this.tileSources = data.sources.filter(source => !source.hidden)

          const userSettings = this.$store.state.userSettings || {}
          const defaultBasemap = userSettings.map?.default_basemap

          if (defaultBasemap && this.tileSources.find(s => s.id === defaultBasemap)) {
            this.selectedLayer = defaultBasemap
          } else if (!this.selectedLayer || !this.tileSources.find(s => s.id === this.selectedLayer)) {
            if (this.tileSources.length > 0) {
              this.selectedLayer = this.tileSources[0].id
            }
          }
          
          // Return all sources (including hidden ones) for MapTiler config
          return data.sources
        }
        return []
      } catch (error) {
        console.error('Error fetching tile sources:', error)
        // Fallback to OSM if tile sources fail to load
        this.tileSources = [{
          id: 'osm',
          name: 'OpenStreetMap',
          type: 'xyz',
          requires_proxy: false,
          client_config: {
            type: 'xyz',
            url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
            tileSize: 256
          }
        }]
        if (!this.selectedLayer) {
          this.selectedLayer = 'osm'
        }
        return []
      }
    },
    async fetchMaptilerConfig(tileSources) {
      this.maptilerConfig = new MapTilerConfig()
      await this.maptilerConfig.fetchConfig(tileSources)
    },
    add3DTerrainControl() {
      // Only add control if MapTiler is configured
      if (!this.maptilerConfig || !this.maptilerConfig.isAvailable()) {
        return
      }

      // Create custom control with tooltip support
      const self = this
      const terrainControl = {
        onAdd: (map) => {
          const container = document.createElement('div')
          container.className = 'maplibregl-ctrl maplibregl-ctrl-group'
          container.style.position = 'relative'

          const button = document.createElement('button')
          button.className = 'maplibregl-ctrl-terrain'
          button.type = 'button'
          button.title = 'Toggle 3D Terrain'
          button.setAttribute('aria-label', 'Toggle 3D Terrain')

          // Set initial state
          if (self.terrainEnabled) {
            button.classList.add('maplibregl-ctrl-terrain-enabled')
          }

          // Create tooltip element
          const tooltip = document.createElement('div')
          tooltip.className = 'maplibregl-ctrl-terrain-tooltip'
          tooltip.style.display = 'none'
          
          // Detect mobile vs desktop
          const isMobile = window.innerWidth < 768 || 'ontouchstart' in window
          tooltip.textContent = isMobile 
            ? 'Use gestures to tilt and rotate.' 
            : 'Use the left mouse button to tilt and rotate.'
          
          container.appendChild(button)
          container.appendChild(tooltip)
          
          // Store reference to tooltip for initial load
          self.terrainTooltipElement = tooltip

          // Click handler
          button.onclick = () => {
            const isEnabled = button.classList.contains('maplibregl-ctrl-terrain-enabled')
            const newState = !isEnabled

            if (newState) {
              button.classList.add('maplibregl-ctrl-terrain-enabled')
              // Enable 3D: add terrain and tilt the map
              self.setupTerrain()
              self.map.easeTo({ pitch: 50, duration: 800 })
              
              // Show tooltip if not shown before
              if (!self.tooltipShown) {
                self.showTooltip(tooltip)
                self.tooltipShown = true
              }
            } else {
              button.classList.remove('maplibregl-ctrl-terrain-enabled')
              // Disable 3D: remove terrain and reset tilt
              self.removeTerrain()
              self.map.easeTo({ pitch: 0, duration: 800 })
            }
          }

          return container
        },
        onRemove: () => {
          // Cleanup handled by MapLibre
        }
      }
      
      this.map.addControl(terrainControl, 'top-left')
    },
    showTooltip(tooltipElement) {
      // Show tooltip
      tooltipElement.style.display = 'block'
      // Force reflow to trigger transition
      tooltipElement.offsetHeight
      tooltipElement.classList.add('maplibregl-ctrl-terrain-tooltip-visible')
      
      // Hide after 3 seconds
      setTimeout(() => {
        tooltipElement.classList.remove('maplibregl-ctrl-terrain-tooltip-visible')
        setTimeout(() => {
          tooltipElement.style.display = 'none'
        }, 300) // Wait for fade-out transition
      }, 3000)
    },
    setupTerrain() {
      if (!this.maptilerConfig) return
      
      maptilerSetupTerrain(this.map, this.maptilerConfig)
      this.addHillshadeIfNeeded()
    },
    removeTerrain() {
      this.removeHillshade()
      maptilerRemoveTerrain(this.map)
    },
    addHillshadeIfNeeded() {
      if (!this.map || !this.maptilerConfig) return
      
      // Only add hillshade if explicitly enabled via the toggle
      if (!this.hillshadeEnabled) {
        return
      }
      
      addHillshade(this.map, this.maptilerConfig, 'feature-layer')
    },
    removeHillshade() {
      maptilerRemoveHillshade(this.map)
    },
    async fetchAvailableTags() {
      if (!this.$store.state.userInfo) return
      try {
        const response = await fetch(`${APIHOST}/api/features/by-tag/`)
        const data = await response.json()

        if (response.ok) {
          const userTags = data.user_tags ? Object.keys(data.user_tags) : []
          const systemTags = data.system_tags ? Object.keys(data.system_tags) : []

          const sortedUserTags = sortUserTagsAlphabetically(userTags)
          const sortedSystemTags = sortTagsByPriority(systemTags)

          this.availableTags = [...sortedUserTags, ...sortedSystemTags]
        } else {
          this.availableTags = []
        }
      } catch (error) {
        console.error('Error fetching available tags:', error)
        this.availableTags = []
      }
    },
    updateMapLayer(layerValue) {
      if (!this.map) return

      // Save current states to reapply after layer switch
      const terrainEnabled = this.terrainEnabled && this.maptilerConfig?.isAvailable()
      const hillshadeEnabled = this.hillshadeEnabled

      this.selectedLayer = layerValue
      const tileSource = this.tileSources.find(s => s.id === layerValue)
      if (!tileSource) return

      const clientConfig = tileSource.client_config || {}

      // Remove hillshade before removing base layer
      this.removeHillshade()

      // Remove existing raster layers (generic)
      const existingRasterLayers = ['osm-layer', 'tile-layer', 'raster-layer']
      existingRasterLayers.forEach(layerId => {
        if (this.map.getLayer(layerId)) {
          this.map.removeLayer(layerId)
        }
      })
      
      // Remove existing raster sources (generic)
      const existingRasterSources = ['osm', 'tile-source', 'raster-source']
      existingRasterSources.forEach(sourceId => {
        if (this.map.getSource(sourceId)) {
          this.map.removeSource(sourceId)
        }
      })

      // Check if this is a style-based source (MapTiler) or raster-based
      const isStyleBased = clientConfig.style_url || clientConfig.type === 'maptiler'
      
      if (isStyleBased) {
        // Style-based source (e.g., MapTiler) - replaces entire style
        const styleUrl = clientConfig.style_url
        
        // Save current state before switching styles
        const geojsonSource = this.map.getSource('geojson-data')
        let geojsonData = null
        if (geojsonSource && geojsonSource._data) {
          geojsonData = geojsonSource._data
        }
        
        // Load the new style
        this.map.setStyle(styleUrl)
        
        // Restore GeoJSON data and terrain after style loads
        this.map.once('styledata', async () => {
          // Wait a bit to ensure style is fully loaded
          await new Promise(resolve => setTimeout(resolve, 100))
          
          // Restore GeoJSON source and features
          if (geojsonData) {
            // Add GeoJSON source directly
            if (!this.map.getSource('geojson-data')) {
              this.map.addSource('geojson-data', {
                type: 'geojson',
                data: {
                  type: 'FeatureCollection',
                  features: []
                }
              })
            }
            
            // Add layers first (they need to exist before adding features)
            ensureLayersExist(this.map, this.showAllLabels)
            
            // Re-add features to the map with proper styling and icons
            const zoom = this.map ? this.map.getZoom() : null
            await addFeaturesToMap(this.map, geojsonData, this.showAllLabels, zoom)
            
            // Update label markers if labels are visible
            if (this.showAllLabels && this.labelMarkerManager) {
              const source = this.map.getSource('geojson-data')
              if (source && source._data && source._data.features) {
                this.labelMarkerManager.updateMarkers(source._data.features)
              }
            }
          }
          
          // Re-apply terrain if it was enabled
          if (terrainEnabled) {
            this.setupTerrain()
          }
          
          // Re-apply hillshade if it was enabled
          if (hillshadeEnabled) {
            this.addHillshadeIfNeeded()
          }
        })
      } else {
        // Raster-based source - need to reset style if coming from a style-based source
        const currentStyle = this.map.getStyle()
        const needsStyleReset = currentStyle && currentStyle.name // MapTiler styles have a name property
        
        const url = clientConfig.url || `/api/tiles/${layerValue}/{z}/{x}/{y}`
        
        // Handle tile subdomains if provided
        let tiles
        if (clientConfig.tileSubdomains && Array.isArray(clientConfig.tileSubdomains)) {
          tiles = clientConfig.tileSubdomains.map(subdomain =>
            url.replace('{s}', subdomain)
          )
        } else {
          tiles = [url.replace('{s}', clientConfig.tileSubdomains?.[0] || 'a')]
        }
        
        if (needsStyleReset) {
          // Coming from a style-based source - reset to blank style first
          const geojsonSource = this.map.getSource('geojson-data')
          let geojsonData = null
          if (geojsonSource && geojsonSource._data) {
            geojsonData = geojsonSource._data
          }
          
          // Reset to blank style
          this.map.setStyle({
            version: 8,
            glyphs: '/api/fonts/{fontstack}/{range}.pbf',
            sources: {},
            layers: []
          })
          
          // Wait for style to load, then add raster layer and restore GeoJSON
          this.map.once('styledata', async () => {
            await new Promise(resolve => setTimeout(resolve, 100))
            
            // Add raster source and layer
            this.map.addSource('raster-source', {
              type: 'raster',
              tiles: tiles,
              tileSize: clientConfig.tileSize || 256
            })
            this.map.addLayer({
              id: 'raster-layer',
              type: 'raster',
              source: 'raster-source',
              minzoom: clientConfig.minzoom || 0,
              maxzoom: clientConfig.maxzoom || 22
            })
            
            // Restore GeoJSON if we had data
            if (geojsonData) {
              if (!this.map.getSource('geojson-data')) {
                this.map.addSource('geojson-data', {
                  type: 'geojson',
                  data: {
                    type: 'FeatureCollection',
                    features: []
                  }
                })
              }
              
              ensureLayersExist(this.map, this.showAllLabels)
              const zoom = this.map ? this.map.getZoom() : null
              await addFeaturesToMap(this.map, geojsonData, this.showAllLabels, zoom)
              
              if (this.showAllLabels && this.labelMarkerManager) {
                const source = this.map.getSource('geojson-data')
                if (source && source._data && source._data.features) {
                  this.labelMarkerManager.updateMarkers(source._data.features)
                }
              }
            }
            
            // Re-apply terrain if it was enabled
            if (terrainEnabled) {
              this.setupTerrain()
            }
            
            // Re-apply hillshade if it was enabled
            if (hillshadeEnabled) {
              this.addHillshadeIfNeeded()
            }
          })
        } else {
          // Not coming from a style-based source - just add raster layer
          this.map.addSource('raster-source', {
            type: 'raster',
            tiles: tiles,
            tileSize: clientConfig.tileSize || 256
          })
          this.map.addLayer({
            id: 'raster-layer',
            type: 'raster',
            source: 'raster-source',
            minzoom: clientConfig.minzoom || 0,
            maxzoom: clientConfig.maxzoom || 22
          })
          
          // Ensure GeoJSON layers exist and are on top
          ensureLayersExist(this.map, this.showAllLabels)
          
          // Re-apply terrain if it was enabled
          if (terrainEnabled) {
            this.setupTerrain()
          }
          
          // Re-apply hillshade if it was enabled
          if (hillshadeEnabled) {
            this.addHillshadeIfNeeded()
          }
        }
      }
    },
    centerToUserLocation() {
      if (!this.map) return

      // State level zoom (shows entire state)
      const stateLevelZoom = 6

      // If we have user location, center on it; otherwise just zoom to state level
      if (this.userLocation) {
        const latitude = this.userLocation.latitude
        const longitude = this.userLocation.longitude

        if (latitude != null && longitude != null) {
          this.map.flyTo({
            center: [longitude, latitude],
            zoom: stateLevelZoom,
            pitch: 0,  // Reset tilt to flat
            bearing: 0,  // Reset rotation to north
            duration: 500
          })
          return
        }
      }

      // Fallback: just zoom to state level at current center
      this.map.flyTo({
        zoom: stateLevelZoom,
        pitch: 0,  // Reset tilt to flat
        bearing: 0,  // Reset rotation to north
        duration: 500
      })
    },
    zoomToFeature(feature) {
      if (!this.map || !feature) {
        console.warn('zoomToFeature: Missing map or feature', { map: !!this.map, feature: !!feature })
        return
      }

      // Ensure feature is on the map (for search results that might not be loaded)
      // Check if feature already exists by database_id to avoid duplicates
      const properties = feature.get ? feature.get('properties') : feature.properties || {}
      const featureId = properties.database_id
      
      if (featureId) {
        const source = this.map.getSource('geojson-data')
        if (source) {
          const currentData = source._data || { type: 'FeatureCollection', features: [] }
          const existingFeatures = currentData.features || []
          
          // Check if feature already exists
          const exists = existingFeatures.some(f => f.properties?.database_id === featureId)
          
          if (!exists) {
            // Convert OpenLayers feature to GeoJSON format for MapLibre
            let geoJsonFeature = null
            
            if (feature.getGeometry && typeof feature.getGeometry === 'function') {
              const geometry = feature.getGeometry()
              
              // Check if this is an OpenLayers geometry
              if (geometry && typeof geometry.getCoordinates === 'function' && typeof geometry.getType === 'function') {
                // Transform OpenLayers geometry to GeoJSON
                try {
                  const format = new GeoJSON()
                  const geoJsonGeometry = format.writeGeometryObject(geometry, {
                    featureProjection: 'EPSG:3857',
                    dataProjection: 'EPSG:4326'
                  })
                  
                  // Create a GeoJSON feature with the transformed geometry
                  geoJsonFeature = {
                    type: 'Feature',
                    geometry: geoJsonGeometry,
                    properties: properties
                  }
                } catch (error) {
                  console.error('zoomToFeature: Error converting OpenLayers geometry to GeoJSON', error)
                }
              }
            }
            
            // If we couldn't convert it, create a basic GeoJSON feature
            if (!geoJsonFeature && feature.geometry) {
              geoJsonFeature = {
                type: 'Feature',
                geometry: feature.geometry,
                properties: properties
              }
            }
            
            // Add the feature to the map
            if (geoJsonFeature) {
              // Process icon if this is a Point feature
              if (geoJsonFeature.geometry.type === 'Point') {
                const iconUrl = getFeatureIconUrl(geoJsonFeature.properties)
                const zoom = this.map.getZoom()
                const replaceIconsLowZoom = this.$store.state.userSettings?.replace_icons_low_zoom ?? true
                const shouldShowIcon = iconUrl && shouldUseIcon(zoom, iconUrl, replaceIconsLowZoom)
                
                if (shouldShowIcon) {
                  const resolvedUrl = getIconSourceUrl(iconUrl, geoJsonFeature.properties)
                  const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`
                  geoJsonFeature.properties['_icon-id'] = iconId
                  
                  // Load icon if not already loaded
                  if (!this.map.hasImage(iconId)) {
                    loadIconImage(this.map, iconId, resolvedUrl).catch(err => {
                      console.warn(`Failed to load icon ${iconId}:`, err)
                      // Remove icon metadata on failure
                      delete geoJsonFeature.properties['_icon-id']
                    })
                  }
                }
              }
              
              existingFeatures.push(geoJsonFeature)
              source.setData({
                type: 'FeatureCollection',
                features: existingFeatures
              })
              
              // Update label markers
              if (this.labelMarkerManager) {
                this.labelMarkerManager.updateMarkers(existingFeatures)
              }
            }
          }
        }
      }

      // Get feature geometry - handle both converted MapLibre features and raw features
      let geometry = null
      
      // Try to get geometry from converted feature (has getGeometry method)
      if (feature.getGeometry && typeof feature.getGeometry === 'function') {
        const mockGeometry = feature.getGeometry()
        
        // Check if this is an OpenLayers geometry (has getCoordinates and getType methods)
        // OpenLayers geometries are in EPSG:3857 and need transformation
        if (mockGeometry && typeof mockGeometry.getCoordinates === 'function' && typeof mockGeometry.getType === 'function') {
          // This is an OpenLayers geometry - transform it to GeoJSON in EPSG:4326
          try {
            const format = new GeoJSON()
            const geoJsonGeometry = format.writeGeometryObject(mockGeometry, {
              featureProjection: 'EPSG:3857',
              dataProjection: 'EPSG:4326'
            })
            geometry = geoJsonGeometry
          } catch (error) {
            console.error('zoomToFeature: Error converting OpenLayers geometry to GeoJSON', error)
            // Fallback to raw geometry
            geometry = feature.geometry
          }
        } else if (mockGeometry && mockGeometry.getExtent) {
          // This is a mock geometry from convertMapLibreFeature (already in EPSG:4326)
          // Use the extent from the mock geometry
          const extent = mockGeometry.getExtent()
          if (extent && extent.length === 4) {
            let [minLon, minLat, maxLon, maxLat] = extent
            
            // Validate all values are finite
            if (extent.every(v => isFinite(v))) {
              // Validate coordinates are within valid ranges for MapLibre
              // Longitude: -180 to 180, Latitude: -90 to 90
              if (minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90) {
                console.warn('zoomToFeature: Extent out of valid range, clamping', { minLon, minLat, maxLon, maxLat })
                // Clamp to valid ranges
                minLon = Math.max(-180, Math.min(180, minLon))
                minLat = Math.max(-90, Math.min(90, minLat))
                maxLon = Math.max(-180, Math.min(180, maxLon))
                maxLat = Math.max(-90, Math.min(90, maxLat))
              }
              
              // Check if bounds are valid (not all zeros)
              const isNotAllZeros = !(minLon === 0 && minLat === 0 && maxLon === 0 && maxLat === 0)
              
              if (isNotAllZeros) {
                // For points (degenerate bounds), use center + zoom
                if (minLon === maxLon && minLat === maxLat) {
                  this.map.flyTo({
                    center: [minLon, minLat],
                    zoom: 10,
                    duration: 500
                  })
                } else {
                  // For lines and polygons, use bounds
                  // MapLibre LngLatBounds takes southwest and northeast corners
                  try {
                    // Create LngLatBounds: sw corner [minLon, minLat], ne corner [maxLon, maxLat]
                    const bounds = new maplibregl.LngLatBounds(
                      [minLon, minLat], // southwest corner
                      [maxLon, maxLat]  // northeast corner
                    )
                    // Use fitBounds which is more reliable for bounds
                    this.map.fitBounds(bounds, {
                      padding: { top: 50, bottom: 50, left: 50, right: 50 },
                      duration: 500
                    })
                  } catch (error) {
                    console.error('zoomToFeature: Error fitting bounds', error, error.stack)
                    // Fallback: try flyTo
                    try {
                      const bounds = new maplibregl.LngLatBounds([minLon, minLat], [maxLon, maxLat])
                      this.map.flyTo({
                        bounds: bounds,
                        padding: 50,
                        duration: 500
                      })
                    } catch (error2) {
                      console.error('zoomToFeature: Error with flyTo fallback', error2)
                    }
                  }
                }
                return
              } else {
                console.warn('zoomToFeature: Extent is all zeros, trying fallback')
              }
            } else {
              console.warn('zoomToFeature: Extent contains non-finite values', extent)
            }
          } else {
            console.warn('zoomToFeature: Invalid extent format', extent)
          }
        } else {
          console.warn('zoomToFeature: MockGeometry missing getExtent', mockGeometry)
        }
        
        // If we haven't returned yet, fall through to coordinate extraction
        if (!geometry) {
          // Fallback: get raw geometry from converted feature
          geometry = feature.geometry
        }
      } else {
        // Try direct geometry access
        geometry = feature.geometry || feature.get?.('geometry')
      }

      // Check if this is an OpenLayers feature with OpenLayers geometry (not already converted)
      // OpenLayers geometries have methods like getCoordinates() and getType()
      if (geometry && typeof geometry.getCoordinates === 'function' && typeof geometry.getType === 'function') {
        // This is an OpenLayers geometry - transform it to GeoJSON in EPSG:4326
        try {
          const format = new GeoJSON()
          const geoJsonGeometry = format.writeGeometryObject(geometry, {
            featureProjection: 'EPSG:3857',
            dataProjection: 'EPSG:4326'
          })
          geometry = geoJsonGeometry
        } catch (error) {
          console.error('zoomToFeature: Error converting OpenLayers geometry to GeoJSON', error)
          return
        }
      }

      if (!geometry || !geometry.type || !geometry.coordinates) {
        console.warn('zoomToFeature: Invalid geometry', { 
          hasGeometry: !!geometry, 
          geometryType: geometry?.type,
          hasCoordinates: !!geometry?.coordinates,
          feature 
        })
        return
      }

      // Extract coordinates from geometry
      const coords = this.getFeatureCoordinates(geometry)
      if (coords.length === 0) {
        console.warn('zoomToFeature: No coordinates found in geometry', geometry)
        return
      }

      // Calculate bounding box
      let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity
      coords.forEach((coord) => {
        const [lon, lat] = Array.isArray(coord) && coord.length >= 2 ? coord : [null, null]
        if (lon != null && lat != null && isFinite(lon) && isFinite(lat)) {
          // Validate coordinates are within valid ranges for MapLibre
          // Longitude: -180 to 180, Latitude: -90 to 90
          if (lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90) {
            minLon = Math.min(minLon, lon)
            minLat = Math.min(minLat, lat)
            maxLon = Math.max(maxLon, lon)
            maxLat = Math.max(maxLat, lat)
          } else {
            console.warn('zoomToFeature: Coordinate out of valid range', { lon, lat })
          }
        }
      })

      // Ensure we have valid bounds
      if (!isFinite(minLon) || !isFinite(minLat) || !isFinite(maxLon) || !isFinite(maxLat)) {
        console.warn('zoomToFeature: Invalid bounds calculated', { minLon, minLat, maxLon, maxLat, coords })
        return
      }

      // Final validation: ensure bounds are within valid ranges
      if (minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90) {
        console.warn('zoomToFeature: Bounds out of valid range', { minLon, minLat, maxLon, maxLat })
        // Clamp to valid ranges
        minLon = Math.max(-180, Math.min(180, minLon))
        minLat = Math.max(-90, Math.min(90, minLat))
        maxLon = Math.max(-180, Math.min(180, maxLon))
        maxLat = Math.max(-90, Math.min(90, maxLat))
      }

      // Ensure bounds are not degenerate (same point)
      if (minLon === maxLon && minLat === maxLat) {
        // For points, zoom to a reasonable zoom level (limited to 10)
        this.map.flyTo({
          center: [minLon, minLat],
          zoom: 10,
          duration: 500
        })
        return
      }

      // Fly to feature
      try {
        // Create LngLatBounds: sw corner [minLon, minLat], ne corner [maxLon, maxLat]
        const bounds = new maplibregl.LngLatBounds(
          [minLon, minLat], // southwest corner
          [maxLon, maxLat]  // northeast corner
        )
        // Use fitBounds which is more reliable for bounds
        this.map.fitBounds(bounds, {
          padding: { top: 50, bottom: 50, left: 50, right: 50 },
          duration: 500
        })
      } catch (error) {
        console.error('zoomToFeature: Error fitting bounds (fallback)', error, error.stack)
        // Final fallback: try flyTo
        try {
          const bounds = new maplibregl.LngLatBounds([minLon, minLat], [maxLon, maxLat])
          this.map.flyTo({
            bounds: bounds,
            padding: 50,
            duration: 500
          })
        } catch (error2) {
          console.error('zoomToFeature: Error with flyTo fallback (fallback)', error2)
        }
      }
    },
    handlePublicShareError(errorMessage) {
      this.publicShareError = errorMessage || 'Invalid share link'
    },
    handleDownloadFeatureKmz() {
      const feature = this.selectedFeature
      if (!feature) return

      const properties = feature.properties || feature.get?.('properties') || {}
      const featureId = properties.database_id
      if (!featureId) return

      let url = `${APIHOST}/api/export-kmz?feature=${encodeURIComponent(featureId)}`

      if (this.isPublicShareMode && this.shareId) {
        url += `&share=${encodeURIComponent(this.shareId)}`
      }

      window.open(url, '_blank')
    },
    handleEditFeature(feature) {
      this.isEditingFeature = true
    },
    handleCancelEdit() {
      this.isEditingFeature = false
    },
    handleFeatureDeleted(feature) {
      // Remove feature from map
      if (this.map && this.map.getSource('geojson-data')) {
        const source = this.map.getSource('geojson-data')
        const data = source._data || { type: 'FeatureCollection', features: [] }
        const properties = feature.properties || feature.get?.('properties') || {}
        const featureId = properties.database_id

        if (featureId && data.features) {
          data.features = data.features.filter(f => f.properties?.database_id !== featureId)
          source.setData(data)
          this.updateFeatureCount()
        }
      }

      this.selectedFeature = null
      this.isEditingFeature = false
    },
    handleFeatureSaved(feature) {
      // Feature was updated, reload data for current view
      this.loadedBounds.clear()
      this.loadDataForCurrentView()
      this.isEditingFeature = false
    },
    handleFeatureSelect(feature) {
      // Feature is already markRaw from overlappingFeatures
      this.selectedFeature = feature
      this.isEditingFeature = false
      this.showFeaturePopup = false
    },
    handleHillshadeChange(enabled) {
      this.hillshadeEnabled = enabled
      if (enabled) {
        // Add hillshade
        addHillshade(this.map, this.maptilerConfig, 'feature-layer')
      } else {
        // Remove hillshade
        maptilerRemoveHillshade(this.map)
      }
    },
    async handleLabelsVisibilityChange(showLabels) {
      this.showAllLabels = showLabels
      if (this.labelMarkerManager) {
        this.labelMarkerManager.setVisibility(showLabels)
        
        // If turning labels ON, we need to regenerate label points and update markers
        // If turning labels OFF, clear all label points to improve performance
        if (showLabels) {
          // Regenerate label points by reprocessing all features
          // This is necessary because label points were not created when labels were off
          this.loadedBounds.clear()
          await this.loadDataForCurrentView()
        } else {
          // Remove all label points from the map to improve performance
          if (this.map && this.map.getSource('geojson-data')) {
            const source = this.map.getSource('geojson-data')
            const currentData = source._data || { type: 'FeatureCollection', features: [] }
            
            // Filter out label points
            const featuresWithoutLabelPoints = (currentData.features || []).filter(f => 
              !f.properties?._isLabelPoint
            )
            
            // Update source with features (without label points)
            source.setData(markRaw({
              type: 'FeatureCollection',
              features: featuresWithoutLabelPoints.map(f => markRaw(f))
            }))
            
            this.updateFeatureCount()
          }
        }
      }
    },
    async handleUnhideFeature(featureId) {
      if (!this.isMainMapRoute || this.isPublicShareMode || !this.$store.state.userInfo) {
        return
      }

      const hiddenFeaturesManager = (await import('@/utils/hiddenFeaturesManager.js')).default

      try {
        await hiddenFeaturesManager.removeHidden(featureId)
        this.$store.commit('removeHiddenFeature', String(featureId))
        this.loadedBounds.clear()
        this.loadDataForCurrentView()
        this.updateFeaturesInExtent()
      } catch (error) {
        console.error('Error unhiding feature:', error)
      }
    },
    async handleUnhideAllHidden() {
      if (!this.isMainMapRoute || this.isPublicShareMode || !this.$store.state.userInfo) {
        return
      }

      const hiddenFeaturesManager = (await import('@/utils/hiddenFeaturesManager.js')).default

      try {
        await hiddenFeaturesManager.clearAllHidden()
        this.$store.commit('setHiddenFeatures', [])
        this.loadedBounds.clear()
        this.loadDataForCurrentView()
        this.updateFeaturesInExtent()
      } catch (error) {
        console.error('Error clearing hidden features:', error)
      }
    },
    handleTagFilterChange(filteredFeatures) {
      if (!this.map || !this.map.getSource('geojson-data')) {
        return
      }

      if (filteredFeatures === null) {
        // Clear tag filter
        this.isTagFilterActive = false
        this.tagFilteredFeatures = []
        const source = this.map.getSource('geojson-data')
        source.setData({ type: 'FeatureCollection', features: [] })
        this.loadedBounds.clear()
        this.featureTimestamps = {}
        this.loadDataForCurrentView()
        return
      }

      // Apply tag filter
      this.isTagFilterActive = true
      this.tagFilteredFeatures = filteredFeatures

      // Convert OpenLayers features to GeoJSON
      const geojsonFeatures = filteredFeatures.map(f => {
        const props = f.properties || f.get?.('properties') || {}
        const geom = f.geometry || f.getGeometry?.()
        return markRaw({
          type: 'Feature',
          properties: props,
          geometry: geom || null
        })
      })

      // Filter out points on borders
      const filteredGeojsonFeatures = filterPointsOnBorders(geojsonFeatures)

      const source = this.map.getSource('geojson-data')
      // Mark the entire data structure as raw to prevent Vue reactivity
      source.setData(markRaw({
        type: 'FeatureCollection',
        features: filteredGeojsonFeatures.map(f => markRaw(f))
      }))

      this.updateFeatureCount()
      this.updateFeaturesInExtent()
    },
    async handleHideFeature(feature) {
      if (!this.isMainMapRoute || this.isPublicShareMode || !this.$store.state.userInfo) {
        return
      }

      if (!feature) return

      const properties = feature.properties || feature.get?.('properties') || {}
      const featureId = properties.database_id
      const featureName = properties.name
      const geometryType = feature.geometry?.type

      if (!featureId) return

      const hiddenFeaturesManager = (await import('@/utils/hiddenFeaturesManager.js')).default

      const optimisticUpdate = () => {
        this.$store.commit('addHiddenFeature', {
          featureId: String(featureId),
          featureName: featureName || null,
          geometryType: geometryType || null
        })

        // Remove from map
        if (this.map && this.map.getSource('geojson-data')) {
          const source = this.map.getSource('geojson-data')
          const data = source._data || { type: 'FeatureCollection', features: [] }
          if (data.features) {
            data.features = data.features.filter(f => f.properties?.database_id !== featureId)
            source.setData(data)
            this.updateFeatureCount()
          }
        }

        if (this.selectedFeature) {
          const propsSelected = this.selectedFeature.properties || this.selectedFeature.get?.('properties') || {}
          if (propsSelected.database_id === featureId) {
            this.selectedFeature = null
            this.isEditingFeature = false
          }
        }

        this.updateFeaturesInExtent()
      }

      hiddenFeaturesManager.addHidden(featureId, optimisticUpdate)
    },
    async handleEditBoxVisibilityChange(payload) {
      if (!payload || !payload.featureId) return

      if (payload.hidden) {
        await this.handleHideFeature({ properties: { database_id: payload.featureId } })
      } else {
        await this.handleUnhideFeature(payload.featureId)
      }
    },
    handleElevationProfileClose() {
      this.showElevationProfile = false
      this.handleHoverClear() // Clear hover marker when dialog closes
    },
    handleHoverPoint(point) {
      if (!this.map || !point) return
      
      // Parse point coordinates
      let coordinates
      if (Array.isArray(point) && point.length >= 2) {
        coordinates = point // [lon, lat]
      } else if (point.coordinates && Array.isArray(point.coordinates)) {
        coordinates = point.coordinates
      } else if (typeof point === 'object' && point.lon !== undefined && point.lat !== undefined) {
        coordinates = [point.lon, point.lat]
      } else {
        return
      }

      // Remove existing hover marker if any
      if (this.hoverMarker) {
        this.hoverMarker.remove()
        this.hoverMarker = null
      }

      // Get feature stroke color
      let markerColor = '#ff0000' // Default red
      if (this.selectedFeature) {
        const properties = this.selectedFeature.properties || this.selectedFeature.get?.('properties') || {}
        const strokeColor = properties.stroke || '#ff0000'
        markerColor = strokeColor
      }

      // Calculate inverse color for border
      const borderColor = getInverseColor(markerColor)

      // Create custom marker element
      const el = document.createElement('div')
      el.style.width = '11px'
      el.style.height = '11px'
      el.style.borderRadius = '50%'
      el.style.backgroundColor = markerColor
      el.style.border = `1px solid ${borderColor}`
      el.style.boxSizing = 'border-box'

      // Create and add MapLibre marker
      this.hoverMarker = new maplibregl.Marker({
        element: el,
        anchor: 'center'
      })
        .setLngLat([coordinates[0], coordinates[1]])
        .addTo(this.map)
    },
    handleHoverClear() {
      if (this.hoverMarker) {
        this.hoverMarker.remove()
        this.hoverMarker = null
      }
    },
    handleClickPoint(point) {
      if (!this.map || !point) return
      
      // Parse point coordinates
      let coordinates
      if (Array.isArray(point) && point.length >= 2) {
        coordinates = point
      } else if (point.coordinates && Array.isArray(point.coordinates)) {
        coordinates = point.coordinates
      } else if (typeof point === 'object' && point.lon !== undefined && point.lat !== undefined) {
        coordinates = [point.lon, point.lat]
      } else {
        return
      }

      // Get current zoom level to preserve it
      const currentZoom = this.map.getZoom()

      // Center the map on the point without changing zoom
      this.map.flyTo({
        center: [coordinates[0], coordinates[1]],
        zoom: currentZoom,
        duration: 500
      })
    },
    async handleCollectionFilter(collectionId) {
      if (!this.map || !collectionId) {
        return
      }

      try {
        const collectionResponse = await fetch(`${APIHOST}/api/collections/${collectionId}/`)

        if (!collectionResponse.ok) {
          throw new Error('Failed to load collection')
        }

        const collectionData = await collectionResponse.json()

        if (collectionResponse.ok && collectionData.collection) {
          this.collectionName = collectionData.collection.name
          this.isCollectionMode = true

          // Clear current features and loaded bounds
          if (this.map.getSource('geojson-data')) {
            const source = this.map.getSource('geojson-data')
            source.setData({ type: 'FeatureCollection', features: [] })
          }
          this.featureTimestamps = {}
          this.loadedBounds.clear()

          // Trigger bbox loading for current view
          await this.loadDataForCurrentView()
        } else {
          throw new Error('Failed to load collection info')
        }
      } catch (error) {
        console.error('Error loading collection:', error)
        this.collectionName = null
        this.isCollectionMode = false
        if (this.map.getSource('geojson-data')) {
          const source = this.map.getSource('geojson-data')
          source.setData({ type: 'FeatureCollection', features: [] })
        }
        this.loadedBounds.clear()
        this.featureTimestamps = {}
        await this.loadDataForCurrentView()
      }
    },
    async handleUrlFeatureId() {
      const featureId = this.$route.query.featureId
      if (!featureId) {
        return
      }

      try {
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

        const geojsonData = data.feature.geojson
        const properties = geojsonData && geojsonData.properties ? {...geojsonData.properties} : {}
        properties.database_id = featureId

        const feature = {
          type: 'Feature',
          properties: properties,
          geometry: geojsonData.geometry
        }

        // Add feature to map
        if (this.map && this.map.getSource('geojson-data')) {
          const source = this.map.getSource('geojson-data')
          const currentData = source._data || { type: 'FeatureCollection', features: [] }
          const existingFeatures = currentData.features || []
          
          // Check if feature already exists
          const exists = existingFeatures.some(f => f.properties?.database_id === featureId)
          if (!exists) {
            existingFeatures.push(feature)
            source.setData({
              type: 'FeatureCollection',
              features: existingFeatures
            })
          }
        }

        // Zoom to feature
        await this.$nextTick()
        setTimeout(() => {
          this.zoomToFeature(markRaw(this.convertMapLibreFeature(feature)))
          this.removeFeatureIdFromUrl()
        }, 100)
      } catch (error) {
        console.error(`Error fetching feature ${featureId}:`, error)
        this.removeFeatureIdFromUrl()
      }
    },
    removeFeatureIdFromUrl() {
      const query = {...this.$route.query}
      delete query.featureId
      this.$router.replace({
        path: this.$route.path,
        query: query
      })
    },
    // Map Destruction Abstraction Layer
    performMapDestruction() {
      // Save current map position and zoom before destruction (if not already saved)
      if (this.map && !this.savedMapCenter) {
        this.savedMapCenter = this.map.getCenter()
        this.savedMapZoom = this.map.getZoom()
        this.savedMapPitch = this.map.getPitch()
        this.savedMapBearing = this.map.getBearing()
      }
      
      // Clear label marker manager
      if (this.labelMarkerManager) {
        this.labelMarkerManager.clear()
        this.labelMarkerManager = null
      }
      
      // Clear data caches
      this.loadedBounds.clear()
      this.featuresInExtent = []
      
      // Destroy map
      if (this.map) {
        this.map.remove()
        this.map = null
      }
      
      // Mark as destroyed for restoration
      this.mapWasDestroyed = true
    },
    cleanupOnNavigateAway() {
      // Save current map position BEFORE any cleanup
      // This must be done first, before flyTo or any map modifications
      if (this.map) {
        this.savedMapCenter = this.map.getCenter()
        this.savedMapZoom = this.map.getZoom()
        this.savedMapPitch = this.map.getPitch()
        this.savedMapBearing = this.map.getBearing()
      }

      // Clear all features from map source
      if (this.map && this.map.getSource('geojson-data')) {
        const source = this.map.getSource('geojson-data')
        source.setData({ type: 'FeatureCollection', features: [] })
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
      if (this.featureCleanupTimeout) {
        clearTimeout(this.featureCleanupTimeout)
        this.featureCleanupTimeout = null
      }

      // Cancel any pending API request
      if (this.currentAbortController) {
        this.currentAbortController.abort()
        this.currentAbortController = null
      }

      // Clear hover marker
      this.handleHoverClear()

      // Always destroy map when navigating away (state already saved above)
      this.performMapDestruction()

      // After cleanup, reset feature counters
      this.featureCount = 0
      this.featureCountUpdatePending = false
    },
    async restoreMap() {
      if (this.map) return

      this.isMapInitializing = true
      this.isRestoring = true

      // Ensure map container is available (with retry for hot reload scenarios)
      await this.$nextTick()
      
      // Wait for container to be truly ready
      let retries = 0
      const maxRetries = 10
      while ((!this.$refs.mapContainer || !(this.$refs.mapContainer instanceof HTMLElement)) && retries < maxRetries) {
        await new Promise(resolve => setTimeout(resolve, 50))
        retries++
      }
      
      if (!this.$refs.mapContainer || !(this.$refs.mapContainer instanceof HTMLElement)) {
        console.error('Map container not available for restore after retries')
        this.isMapInitializing = false
        this.isRestoring = false
        return
      }

      try {
        // Determine map config - use saved state if available, otherwise use default
        let mapConfig
        if (this.savedMapCenter && this.savedMapZoom !== null) {
          mapConfig = {
            center: [this.savedMapCenter.lng, this.savedMapCenter.lat],
            zoom: this.savedMapZoom,
            pitch: this.savedMapPitch || 0,
            bearing: this.savedMapBearing || 0
          }
        } else {
          mapConfig = this.getInitialMapConfig()
          mapConfig.pitch = 0
          mapConfig.bearing = 0
        }

        // Create map instance with controls and event handlers
        this.createMapInstance(mapConfig)

        // Wait for map to load
        await new Promise((resolve) => {
          if (this.map.loaded()) {
            resolve()
          } else {
            this.map.once('load', resolve)
          }
        })

        // Restore layer selection
        if (this.selectedLayer && this.tileSources.length > 0) {
          this.updateMapLayer(this.selectedLayer)
        }

        // Restore terrain state if it was enabled
        if (this.terrainEnabled && this.maptilerConfig?.isAvailable()) {
          this.setupTerrain()
        }

        // Restore hillshade state if it was enabled
        if (this.hillshadeEnabled && this.maptilerConfig?.isAvailable()) {
          this.addHillshadeIfNeeded()
        }

        // Add 3D terrain control
        this.add3DTerrainControl()

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
            this.map.resize()
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
    },
  },
  async mounted() {
    this.isMapInitializing = true

    // Initialize featureTimestamps as empty object
    this.featureTimestamps = {}

    // Add keyboard event listener for escape key
    this.handleKeyDown = (event) => {
      if (event.key === 'Escape' || event.key === 'Esc') {
        // Only close info box if it's visible and edit box is not open
        if (this.selectedFeature && !this.isEditingFeature) {
          this.selectedFeature = null
        }
      }
    }
    window.addEventListener('keydown', this.handleKeyDown)

    // Ensure map container is available (with retry for hot reload scenarios)
    await this.$nextTick()
    
    // Wait for container to be truly ready (important for hot reload)
    let retries = 0
    const maxRetries = 10
    while ((!this.$refs.mapContainer || !(this.$refs.mapContainer instanceof HTMLElement)) && retries < maxRetries) {
      await new Promise(resolve => setTimeout(resolve, 50))
      retries++
    }
    
    if (!this.$refs.mapContainer || !(this.$refs.mapContainer instanceof HTMLElement)) {
      console.error('Map container not available after retries')
      this.isMapInitializing = false
      this.loadError = 'Map container failed to initialize. Please refresh the page.'
      return
    }

    // Parallelize all independent API calls for faster initialization
    const initPromises = [
      this.fetchTileSources(),
      // getUserLocation only for authenticated, non-public-share users
      (!this.isPublicShareMode ? this.getUserLocation() : Promise.resolve())
    ]
    
    // Fetch available tags for child components (only for authenticated users)
    if (this.$store.state.userInfo) {
      initPromises.push(this.fetchAvailableTags())
    }

    // Wait for all parallel fetches to complete
    const [tileSources] = await Promise.all(initPromises)
    
    // Fetch MapTiler config with the tile sources we just got
    await this.fetchMaptilerConfig(tileSources)

    // Wait for map to be fully initialized before loading data
    try {
      await this.initializeMap()
    } catch (error) {
      console.error('Error initializing map:', error)
      this.loadError = error.message || 'Failed to initialize map. Please refresh the page.'
      this.isMapInitializing = false
      return
    }

    // Wait for map to load before proceeding
    await new Promise((resolve) => {
      if (this.map.loaded()) {
        resolve()
      } else {
        this.map.once('load', resolve)
      }
    })

    // Update map layer to use the selected source (in case it's not the default OSM)
    if (this.selectedLayer && this.tileSources.length > 0) {
      this.updateMapLayer(this.selectedLayer)
    }

    // Setup terrain based on user's default preference (after baselayer is configured)
    const userSettings = this.$store.state.userSettings || {}
    const defaultTerrainOn = userSettings.map?.enable_3d_terrain || false
    const defaultHillshadeOn = userSettings.map?.enable_hillshade || false
    
    if (defaultTerrainOn && this.maptilerConfig?.isAvailable()) {
      this.terrainEnabled = true
      this.setupTerrain()
      // Tilt the map for 3D view
      this.map.setPitch(50)
    } else {
      this.terrainEnabled = false
    }
    
    // Setup hillshade based on user's default preference (after baselayer is configured)
    if (defaultHillshadeOn && this.maptilerConfig?.isAvailable()) {
      this.hillshadeEnabled = true
      this.addHillshadeIfNeeded()
    } else {
      this.hillshadeEnabled = false
    }

    // Add 3D terrain toggle control AFTER setting terrainEnabled (only if MapTiler is configured)
    this.add3DTerrainControl()
    
    // Show tooltip on initial load if terrain is enabled
    if (defaultTerrainOn && this.terrainEnabled && !this.tooltipShown) {
      this.$nextTick(() => {
        if (this.terrainTooltipElement) {
          this.showTooltip(this.terrainTooltipElement)
          this.tooltipShown = true
        }
      })
    }

    // Check for collection query parameter
    if (this.collectionId) {
      await this.handleCollectionFilter(this.collectionId)
    } else {
      // Initial data load
      await this.loadDataForCurrentView()
      
      // Check for featureId in URL
      await this.handleUrlFeatureId()
    }

    // Set isMapInitializing to false after initial data load completes
    // This allows map move/zoom events to trigger data loads from now on
    this.isMapInitializing = false

    // Update map size to ensure it renders properly
    await this.$nextTick()
    if (this.map) {
      setTimeout(() => {
        this.map.resize()
      }, 100)
    }

    // Initial feature list update
    this.updateFeaturesInExtent()
  },
  activated() {
    // Clear current features and feature-related state
    if (this.map && this.map.getSource('geojson-data')) {
      const source = this.map.getSource('geojson-data')
      source.setData({ type: 'FeatureCollection', features: [] })
    }
    this.featuresInExtent = []
    this.featureTimestamps = {}
    this.loadedBounds.clear()
    this.selectedFeature = null
    this.isEditingFeature = false
    this.showElevationProfile = false

    // Clear any active tag filter state
    this.isTagFilterActive = false
    this.tagFilteredFeatures = []
    this.isTagFilterLoading = false

    // Treat this as a fresh initial load
    this.isInitialLoad = true

    // Remount sidebar to clear internal state
    this.sidebarKey += 1

    // If map was destroyed, restore it
    if (this.mapWasDestroyed) {
      this.restoreMap()
      this.mapWasDestroyed = false
      return
    }

    const hasTagQuery = !!this.$route.query.tag
    const hasCollectionQuery = !!this.$route.query.collection

    // Reload data based on route query parameters
    if (this.map) {
      if (hasCollectionQuery) {
        // Collection mode - load collection features
        this.handleCollectionFilter(this.collectionId)
      } else if (!hasTagQuery) {
        // Normal view - reload bbox data
        this.isMapInitializing = true
        this.loadDataForCurrentView().then(() => {
          this.isMapInitializing = false
          this.updateFeaturesInExtent()
          // Resize map after data loads
          if (this.map) {
            setTimeout(() => {
              this.map.resize()
            }, 100)
          }
        })
      } else {
        // Tag filter mode - sidebar will handle loading
        this.updateFeaturesInExtent()
      }
    }
  },
  deactivated() {
    // Run cleanup when navigating away
    this.cleanupOnNavigateAway()
  },
  beforeUnmount() {
    // Remove keyboard event listener
    if (this.handleKeyDown) {
      window.removeEventListener('keydown', this.handleKeyDown)
    }
    
    if (this.labelMarkerManager) {
      this.labelMarkerManager.clear()
      this.labelMarkerManager = null
    }
    if (this.map) {
      this.map.remove()
      this.map = null
    }
  }
}
</script>

<style>
@import 'maplibre-gl/dist/maplibre-gl.css';

/* 3D Terrain toggle button styling */
.maplibregl-ctrl-terrain {
  background-color: #fff;
  background-repeat: no-repeat;
  background-position: center;
  width: 29px;
  height: 29px;
  /* Default state (OFF) - dark gray */
  background-image: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='22' height='22' fill='%23333' viewBox='0 0 22 22'%3E%3Cpath d='m1.754 13.406 4.453-4.851 3.09 3.09 3.281 3.277.969-.969-3.309-3.312 3.844-4.121 6.148 6.886h1.082v-.855l-7.207-8.07-4.84 5.187L6.169 6.57l-5.48 5.965v.871ZM.688 16.844h20.625v1.375H.688Zm0 0'/%3E%3C/svg%3E");
}

.maplibregl-ctrl-terrain:hover {
  /* Hover state when OFF - slightly lighter gray */
  background-image: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='22' height='22' fill='%23555' viewBox='0 0 22 22'%3E%3Cpath d='m1.754 13.406 4.453-4.851 3.09 3.09 3.281 3.277.969-.969-3.309-3.312 3.844-4.121 6.148 6.886h1.082v-.855l-7.207-8.07-4.84 5.187L6.169 6.57l-5.48 5.965v.871ZM.688 16.844h20.625v1.375H.688Zm0 0'/%3E%3C/svg%3E");
}

.maplibregl-ctrl-terrain.maplibregl-ctrl-terrain-enabled {
  /* Enabled state (ON) - light blue */
  background-image: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='22' height='22' fill='%2333b5e5' viewBox='0 0 22 22'%3E%3Cpath d='m1.754 13.406 4.453-4.851 3.09 3.09 3.281 3.277.969-.969-3.309-3.312 3.844-4.121 6.148 6.886h1.082v-.855l-7.207-8.07-4.84 5.187L6.169 6.57l-5.48 5.965v.871ZM.688 16.844h20.625v1.375H.688Zm0 0'/%3E%3C/svg%3E");
}

.maplibregl-ctrl-terrain.maplibregl-ctrl-terrain-enabled:hover {
  /* Hover state when ON - brighter light blue */
  background-image: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='22' height='22' fill='%2350c8ff' viewBox='0 0 22 22'%3E%3Cpath d='m1.754 13.406 4.453-4.851 3.09 3.09 3.281 3.277.969-.969-3.309-3.312 3.844-4.121 6.148 6.886h1.082v-.855l-7.207-8.07-4.84 5.187L6.169 6.57l-5.48 5.965v.871ZM.688 16.844h20.625v1.375H.688Zm0 0'/%3E%3C/svg%3E");
}

.maplibregl-ctrl-terrain:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 3D Terrain tooltip styling */
.maplibregl-ctrl-terrain-tooltip {
  position: absolute;
  left: 38px;
  top: 0;
  background-color: #fff;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 4px;
  padding: 6px 10px;
  font-size: 12px;
  color: #333;
  white-space: nowrap;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
  pointer-events: none;
  z-index: 1;
  opacity: 0;
  transition: opacity 0.3s ease-in-out;
}

.maplibregl-ctrl-terrain-tooltip-visible {
  opacity: 1;
}
</style>

