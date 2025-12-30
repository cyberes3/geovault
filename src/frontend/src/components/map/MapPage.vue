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
        :geocoding-available="maptilerConfig && maptilerConfig.isAvailable() && !!$store.state.userInfo"
        @close="activeMobileSidebar = null"
        @feature-click="zoomToFeature"
        @feature-hide="handleHideFeature"
        @tag-filter-change="handleTagFilterChange"
        @tag-filter-loading-change="isTagFilterLoading = $event"
        @tag-filter-start="filterExistingFeaturesByTags"
        @geocoding-result-click="handleGeocodingResult"
        @geocoding-clear="clearGeocodingMarker"
    />

    <!-- Center - Map -->
    <div class="flex-1 w-full bg-gray-50 relative overflow-hidden">
      <MobileControlsBar
        :is-public-share-mode="isPublicShareMode"
        :public-share-tag="publicShareTag"
        :public-share-collection-name="publicShareCollectionName"
        :collection-name="collectionName"
        @toggle-features="activeMobileSidebar = 'features'"
        @toggle-controls="activeMobileSidebar = 'controls'"
      />
      <div class="relative w-full h-full">
        <!-- Map -->
        <div
            ref="mapContainer"
            :class="[
            'w-full h-full transition-opacity duration-300',
            (publicShareError || loadError) ? 'opacity-50 pointer-events-none' : 'opacity-100'
          ]"
        ></div>

        <!-- 3D Terrain Toggle Button -->
        <div
            v-if="maptilerConfig"
            class="maplibregl-ctrl maplibregl-ctrl-group"
            style="position: absolute; top: 100px; left: 10px; z-index: 2;"
        >
          <button
              :class="[
                'maplibregl-ctrl-terrain',
                terrainEnabled ? 'maplibregl-ctrl-terrain-enabled' : ''
              ]"
              type="button"
              title="Toggle 3D Terrain"
              aria-label="Toggle 3D Terrain"
              @click="toggleTerrain"
          ></button>
          <div
              v-if="showTerrainTooltip"
              class="maplibregl-ctrl-terrain-tooltip maplibregl-ctrl-terrain-tooltip-visible"
          >
            {{ isMobile ? 'Use gestures to tilt and rotate.' : 'Use the left mouse button to tilt and rotate.' }}
          </div>
        </div>

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
            @zoom="zoomToFeature(selectedFeature)"
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

        <!-- Quick Point Dialog -->
        <QuickPointDialog
            v-if="!isPublicShareMode"
            :is-open="showQuickPointDialog"
            :available-tags="availableTags"
            @close="showQuickPointDialog = false"
            @created="handleQuickPointCreated"
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
        @layer-change="switchMapLayer"
        @unhide-feature="handleUnhideFeature"
        @unhide-all="handleUnhideAllHidden"
        @labels-visibility-change="handleLabelsVisibilityChange"
        @hillshade-change="handleHillshadeChange"
        @quick-point="showQuickPointDialog = true"
    />

    <!-- Toast Notifications -->
    <Toast ref="toast" />
  </div>
</template>

<script>
import {markRaw, defineAsyncComponent} from 'vue'
import 'maplibre-gl/dist/maplibre-gl.css'
import maplibregl from 'maplibre-gl'
import { LabelMarkerManager } from '@/utils/map/maplibre/labelMarkers.js'
import {getInitialMapConfig, getLocationDisplayName} from '@/utils/map/mapConfigUtils'
import { sortTagsByPriority, sortUserTagsAlphabetically, isSystemTag } from '@/utils/tagUtils.js'
import {getInverseColor} from '@/utils/map/colorUtils'
import {getCookie} from '@/assets/js/auth.js'
import {getUnitPreference} from '@/utils/units'
import {APIHOST, MAP_CONFIG} from '@/config.js'

// Components - always needed
import FeatureListSidebar from './FeatureListSidebar.vue'
import MapControlsSidebar from './MapControlsSidebar.vue'
import FeatureInfoBox from './FeatureInfoBox.vue'
import MapErrorOverlay from './MapErrorOverlay.vue'
import MapLoadingIndicator from './MapLoadingIndicator.vue'
import MobileControlsBar from './MobileControlsBar.vue'
import Toast from '@/components/parts/Toast.vue'

// Lazy-loaded components - only loaded when needed
const FeatureEditBox = defineAsyncComponent(() => import('./FeatureEditBox.vue'))
const FeatureSelectionPopup = defineAsyncComponent(() => import('./FeatureSelectionPopup.vue'))
const ElevationProfileDialog = defineAsyncComponent(() => import('./ElevationProfileDialog.vue'))
const QuickPointDialog = defineAsyncComponent(() => import('./QuickPointDialog.vue'))

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
import {
  restoreGeoJsonFeatures,
  restoreMapView,
  getMapState,
  getGeoJsonData
} from '@/utils/map/maplibre/layerSwitching.js'
import { getIconSourceUrl, getFeatureIconUrl, loadIconImage, shouldUseIcon, getStrokeWidthExpressionWithHighlight, getCircleRadiusExpressionWithHighlight, getIconSizeExpressionWithHighlight } from '@/utils/map/maplibre/featureStyling.js'
import { createZoomBasedRadiusExpression } from '@/utils/map/maplibre/featureStyles.js'
import { 
  MapTilerConfig,
  setupTerrain as maptilerSetupTerrain,
  removeTerrain as maptilerRemoveTerrain,
  addHillshade,
  removeHillshade as maptilerRemoveHillshade,
  createTerrainControl
} from '@/utils/map/maplibre/maptilerIntegration.js'
import { fetchUserLocation } from '@/utils/map/locationUtils.js'
import { getCoordinatesFromGeometry, filterFeaturesByBounds, cleanupDistantFeatures as cleanupDistantFeaturesUtil } from '@/utils/map/featureExtent.js'

export default {
  name: 'MapPage',
  components: {
    FeatureListSidebar,
    MapControlsSidebar,
    FeatureInfoBox,
    FeatureEditBox,
    FeatureSelectionPopup,
    ElevationProfileDialog,
    MapErrorOverlay,
    MapLoadingIndicator,
    QuickPointDialog,
    MobileControlsBar,
    Toast,
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
      return path === '/map' && !hasCollection && !hasTag
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
  watch: {
    selectedFeature(newFeature, oldFeature) {
      // Update highlighting when feature is selected/deselected (dialog opens/closes)
      this.$nextTick(() => {
        this.updateFeatureHighlighting()
      })
    },
    isEditingFeature(newVal) {
      // Update highlighting when edit dialog opens/closes
      this.$nextTick(() => {
        this.updateFeatureHighlighting()
      })
    },
    '$route.query.featureId': {
      handler(newFeatureId, oldFeatureId) {
        // Handle featureId query parameter changes for subsequent navigations
        // This catches cases where the component is already mounted and user
        // clicks another "View on Map" button from tags page
        if (newFeatureId && newFeatureId !== oldFeatureId) {
          this.handleUrlFeatureId()
        }
      },
      immediate: false  // Don't run on mount since mounted() already handles it
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
      zoomUpdateFrame: null,
      userLocation: null,
      currentAbortController: null,
      selectedLayer: 'osm',
      featuresInExtent: [],
      // Persistent cache of GeoJSON features that survives setStyle() calls
      cachedGeoJsonData: null,
      // Cache for serialized source data to avoid expensive serialize() calls
      cachedSerializedData: null,
      lastSerializedZoom: null,
      lastProcessedZoom: null,
      lastLabelUpdateZoom: null,
      lastIconVisibilityZoom: null,
      featureListUpdateTimeout: null,
      featureCleanupTimeout: null,
      selectedFeature: null,
      tileSources: [],
      showQuickPointDialog: false,
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
      showTerrainTooltip: false, // Track if 3D tooltip should be shown
      hillshadeEnabled: false, // Current state of hillshade (on/off)
      // Saved map state for restoration after destruction
      savedMapCenter: null,
      savedMapZoom: null,
      savedMapPitch: null,
      savedMapBearing: null,
      geocodingMarker: null, // Marker for geocoding search results
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
        if (source) {
          // Use serialize() method for MapLibre v5 compatibility
          const serialized = source.serialize()
          const data = serialized.data
          
          if (data && data.features) {
            for (const feature of data.features) {
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
        }
        console.warn(`Could not find feature for missing icon: ${iconId}`)
      }
    },
    // Create and configure map instance with controls and sources
    createMapInstance(mapConfig) {
      // Get anti-aliasing setting from user preferences
      const userSettings = this.$store.state.userSettings || {}
      const enableAntialias = userSettings.map?.enable_antialias || false

      // Create MapLibre map
      this.map = markRaw(initializeMap(this.$refs.mapContainer, {
        center: mapConfig.center,
        zoom: mapConfig.zoom,
        pitch: mapConfig.pitch || 0,
        bearing: mapConfig.bearing || 0,
        glyphsUrl: '/api/fonts/{fontstack}/{range}.pbf',
        antialias: enableAntialias
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
      
      // Cancel pending bbox queries when user starts panning or zooming
      this.map.on('move', () => {
        this.cancelPendingBboxQuery()
      })
      this.map.on('zoom', () => {
        this.cancelPendingBboxQuery()
      })

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
                const serialized = source.serialize()
                const data = serialized.data
                if (data && data.features) {
                  const originalFeature = data.features.find(
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
            // Get the map container's bounding rect to properly position the popup
            // e.point is relative to the map canvas, so we use it directly
            const mapRect = this.$refs.mapContainer?.getBoundingClientRect() || { left: 0, top: 0 }
            this.popupPosition = {
              x: e.point.x,
              y: e.point.y,
              containerWidth: this.$refs.mapContainer?.clientWidth || window.innerWidth,
              containerHeight: this.$refs.mapContainer?.clientHeight || window.innerHeight
            }
            this.showFeaturePopup = true
          }
        }
      })

      // Add throttled zoom event listener for responsive label and icon updates
      // Use requestAnimationFrame to batch updates and prevent choppiness
      this.map.on('zoom', () => {
        // Cancel any pending zoom updates
        if (this.zoomUpdateFrame) {
          cancelAnimationFrame(this.zoomUpdateFrame)
        }
        
        // Schedule update for next animation frame
        this.zoomUpdateFrame = requestAnimationFrame(() => {
          this.handleZoomUpdate()
          this.zoomUpdateFrame = null
        })
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
        
        // Track hovered feature for highlighting
        if (hoverableFeatures.length > 0) {
          const hoveredFeature = hoverableFeatures[0]
          const hoveredId = hoveredFeature.properties?.database_id
          if (this.hoveredFeatureId !== hoveredId) {
            this.hoveredFeatureId = hoveredId
            this.updateFeatureHighlighting()
          }
        } else {
          if (this.hoveredFeatureId !== null) {
            this.hoveredFeatureId = null
            this.updateFeatureHighlighting()
          }
        }
      })

      // Reset cursor when leaving the map
      this.map.on('mouseout', () => {
        this.map.getCanvas().style.cursor = ''
        if (this.hoveredFeatureId !== null) {
          this.hoveredFeatureId = null
          this.updateFeatureHighlighting()
        }
      })

      // Add contextmenu (right-click) handler to copy coordinates
      this.map.on('contextmenu', (e) => {
        // Prevent default browser context menu
        e.preventDefault()
        
        // Get coordinates and zoom level
        const { lng, lat } = e.lngLat
        const zoom = Math.round(this.map.getZoom())
        
        // Format coordinates with 6 decimal places
        const coordinateString = `${lat.toFixed(6)}, ${lng.toFixed(6)}`
        
        // Generate CalTopo URL
        const caltopoUrl = `https://caltopo.com/map.html#ll=${lat.toFixed(5)},${lng.toFixed(5)}&z=${zoom}`
        
        // Copy to clipboard
        navigator.clipboard.writeText(coordinateString).then(() => {
          // Show success message with CalTopo link
          if (this.$refs.toast) {
            const html = `Coordinates copied! <a href="${caltopoUrl}" target="_blank" rel="noopener noreferrer">Open in CalTopo</a>`
            this.$refs.toast.success('', { html, timeout: 5000 })
          }
        }).catch((err) => {
          console.error('Failed to copy coordinates:', err)
          // Show error message
          if (this.$refs.toast) {
            this.$refs.toast.error('Failed to copy coordinates')
          }
        })
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
      this.userLocation = await fetchUserLocation(this.LOCATION_API_URL)
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
      
      // Resize map after load event
      this.ensureMapResize()
    },
    convertMapLibreFeature(mlFeature) {
      return convertMapLibreFeature(mlFeature)
    },
    cancelPendingBboxQuery() {
      // Cancel any pending bbox query when user starts panning or zooming
      if (this.loadTimeout) {
        clearTimeout(this.loadTimeout)
        this.loadTimeout = null
      }
    },
    getCachedSourceData() {
      // Cache serialized source data to avoid expensive serialize() calls
      // Returns cached data if zoom hasn't changed significantly (within 0.1)
      if (!this.map || !this.map.getSource('geojson-data')) {
        return null
      }

      const source = this.map.getSource('geojson-data')
      const currentZoom = this.map.getZoom()
      
      // Check if we can use cached data (zoom hasn't changed significantly)
      if (this.cachedSerializedData && 
          this.lastSerializedZoom !== null && 
          Math.abs(currentZoom - this.lastSerializedZoom) < 0.1) {
        return this.cachedSerializedData
      }
      
      // Serialize and cache
      const serialized = source.serialize()
      this.cachedSerializedData = serialized
      this.lastSerializedZoom = currentZoom
      
      return serialized
    },
    invalidateSourceCache() {
      // Invalidate cached serialized data when source data changes
      this.cachedSerializedData = null
      this.lastSerializedZoom = null
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
      // Use requestIdleCallback for non-critical updates when available
      if (window.requestIdleCallback) {
        this.featureListUpdateTimeout = setTimeout(() => {
          requestIdleCallback(() => {
            this.updateFeaturesInExtent()
          }, { timeout: 1000 })
        }, 500)
      } else {
        this.featureListUpdateTimeout = setTimeout(() => {
          this.updateFeaturesInExtent()
        }, 500)
      }
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
      
      // Invalidate cache since new features were added
      this.invalidateSourceCache()
      
      // Initialize feature highlighting after layers are set up
      this.updateFeatureHighlighting()
      
      // Update persistent cache with current map state (survives setStyle() calls)
      if (this.map && this.map.getSource('geojson-data')) {
        try {
          const source = this.map.getSource('geojson-data')
          const serialized = source.serialize()
          const currentData = serialized.data
          if (currentData && currentData.features) {
            // Deep clone to avoid reactivity issues
            this.cachedGeoJsonData = markRaw({
              type: 'FeatureCollection',
              features: currentData.features.map(f => markRaw(f))
            })
          }
        } catch (error) {
          console.warn('Failed to update cached GeoJSON data:', error)
        }
      }
      
      // Update label markers only if labels are visible
      // Skip expensive label processing when labels are hidden
      if (this.showAllLabels && this.labelMarkerManager && geojsonData && geojsonData.features) {
        // Get all features from the source (including label points if they exist)
        const source = this.map.getSource('geojson-data')
        if (source) {
          const serialized = source.serialize()
          const data = serialized.data
          if (data && data.features) {
            this.labelMarkerManager.updateMarkers(data.features)
          }
        }
      }
    },
    async handleZoomUpdate() {
      // Throttled zoom update handler - runs via requestAnimationFrame
      // This prevents choppiness by batching updates
      if (!this.map) return
      
      const currentZoom = this.map.getZoom()
      
      // Only update label markers when zoom crosses significant threshold (0.5+)
      // This reduces expensive marker DOM updates
      if (this.showAllLabels && this.labelMarkerManager) {
        const lastLabelZoom = this.lastLabelUpdateZoom || 0
        if (Math.abs(currentZoom - lastLabelZoom) >= 0.5) {
          const serialized = this.getCachedSourceData()
          if (serialized && serialized.data && serialized.data.features) {
            // Pass true for immediate update during zoom
            this.labelMarkerManager.updateMarkers(serialized.data.features, true)
            this.lastLabelUpdateZoom = currentZoom
          }
        }
      }
      
      // Update small feature flags (needed for visual feedback during zoom)
      // This is relatively lightweight and provides immediate visual feedback
      updateSmallFeatureFlags(this.map, currentZoom)
      // Invalidate cache since updateSmallFeatureFlags may have modified data
      this.invalidateSourceCache()
      
      // Lightweight icon visibility update during zoom to prevent icons showing when zooming out
      // Only updates when crossing the threshold (zoom 8) to keep it performant
      this.updateIconVisibilityDuringZoom(currentZoom)
    },
    updateIconVisibilityDuringZoom(currentZoom) {
      // Lightweight method to hide icons immediately when zooming out past threshold
      // Uses layer visibility for immediate effect, then updates source data
      const ICON_THRESHOLD = 8
      const userSettings = this.$store.state.userSettings || {}
      const replaceIconsLowZoom = userSettings.map?.replace_icons_low_zoom !== undefined 
        ? userSettings.map.replace_icons_low_zoom 
        : true
      
      // Only process if replaceIconsLowZoom is enabled
      if (!replaceIconsLowZoom) {
        this.lastIconVisibilityZoom = currentZoom
        return
      }
      
      // Check if we're at or below threshold and need to hide icons
      const shouldHideIcons = currentZoom <= ICON_THRESHOLD
      const wasAboveThreshold = this.lastIconVisibilityZoom === null || this.lastIconVisibilityZoom > ICON_THRESHOLD
      
      // Immediately hide/show the point-icons layer based on zoom
      // This provides instant visual feedback without waiting for source data update
      if (this.map && this.map.getLayer('point-icons')) {
        const currentVisibility = this.map.getLayoutProperty('point-icons', 'visibility')
        const targetVisibility = shouldHideIcons ? 'none' : 'visible'
        
        if (currentVisibility !== targetVisibility) {
          this.map.setLayoutProperty('point-icons', 'visibility', targetVisibility)
        }
      }
      
      // Update source data when crossing threshold (going from above to below)
      // This ensures data is correct for when zooming back in
      if (shouldHideIcons && wasAboveThreshold) {
        // Zoom crossed threshold going down - update source data to remove _icon-id
        const serialized = this.getCachedSourceData()
        if (serialized && serialized.data && serialized.data.features) {
          const features = serialized.data.features
          let needsUpdate = false
          
          // Quick pass: only remove _icon-id from Point features that have icons
          for (const feature of features) {
            if (feature.properties?._isLabelPoint || feature.properties?._isSmallFeatureReplacement) {
              continue
            }
            
            if (feature.geometry?.type === 'Point' && feature.properties?.['_icon-id']) {
              delete feature.properties['_icon-id']
              needsUpdate = true
            }
          }
          
          if (needsUpdate) {
            const source = this.map.getSource('geojson-data')
            source.setData(markRaw({
              type: 'FeatureCollection',
              features: features.map(f => markRaw(f))
            }))
            this.invalidateSourceCache()
          }
        }
      } else if (!shouldHideIcons && this.lastIconVisibilityZoom !== null && this.lastIconVisibilityZoom <= ICON_THRESHOLD) {
        // Zoom crossed threshold going up - show layer (source data will be updated by reprocessFeaturesForZoom)
        // Layer visibility is already set above, just need to ensure it's visible
      }
      
      this.lastIconVisibilityZoom = currentZoom
    },
    async reprocessFeaturesForZoom() {
      // This function updates icon metadata for features when zoom changes
      // It does NOT re-add features - they're already in the source
      // It only updates rendering properties like _icon-id
      
      if (!this.map || !this.map.getSource('geojson-data')) return
      
      const zoom = this.map.getZoom()
      
      // Only process when zoom crosses integer boundaries (e.g., 7→8, 8→9)
      // This reduces expensive processing by ~80%
      if (this.lastProcessedZoom !== null && Math.abs(zoom - this.lastProcessedZoom) < 0.5) {
        return
      }
      
      const serialized = this.getCachedSourceData()
      if (!serialized) return
      
      const currentData = serialized.data || { type: 'FeatureCollection', features: [] }
      const features = currentData.features || []
      
      if (features.length === 0) return
      
      this.lastProcessedZoom = zoom
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
        const source = this.map.getSource('geojson-data')
        source.setData(markRaw({
          type: 'FeatureCollection',
          features: features.map(f => markRaw(f))
        }))
        // Invalidate cache since data changed
        this.invalidateSourceCache()
      }
      
      // Ensure point-icons layer visibility matches zoom level
      if (this.map && this.map.getLayer('point-icons')) {
        const userSettings = this.$store.state.userSettings || {}
        const replaceIconsLowZoom = userSettings.map?.replace_icons_low_zoom !== undefined 
          ? userSettings.map.replace_icons_low_zoom 
          : true
        
        const shouldShowIcons = !replaceIconsLowZoom || zoom > 8
        const currentVisibility = this.map.getLayoutProperty('point-icons', 'visibility')
        const targetVisibility = shouldShowIcons ? 'visible' : 'none'
        
        if (currentVisibility !== targetVisibility) {
          this.map.setLayoutProperty('point-icons', 'visibility', targetVisibility)
        }
      }
    },
    updateFeaturesInExtent() {
      if (!this.map || !this.map.getSource('geojson-data')) {
        this.featuresInExtent = []
        return
      }

      const bounds = this.map.getBounds()
      
      // Use cached serialized data to avoid expensive serialize() calls
      const serialized = this.getCachedSourceData()
      if (!serialized) {
        this.featuresInExtent = []
        return
      }
      
      const data = serialized.data || { type: 'FeatureCollection', features: [] }
      const features = data.features || []

      // Update persistent cache with current source state
      if (features.length > 0) {
        try {
          this.cachedGeoJsonData = markRaw({
            type: 'FeatureCollection',
            features: features.map(f => markRaw(f))
          })
        } catch (error) {
          console.warn('Failed to update cached GeoJSON data:', error)
        }
      }

      // Filter features in current bounds using utility function
      const featuresInBounds = filterFeaturesByBounds(features, bounds, true, true)

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
      
      // Use cached serialized data to avoid expensive serialize() calls
      const serialized = this.getCachedSourceData()
      if (!serialized) return
      
      const data = serialized.data || { type: 'FeatureCollection', features: [] }
      const features = data.features || []
      
      // Use utility function to clean up distant features (500 miles buffer)
      const { filteredFeatures: featuresWithinBuffer, removedCount } = cleanupDistantFeaturesUtil(
        features, 
        bounds, 
        this.getFeatureCoordinates, 
        500
      )
      
      // Only update if we actually removed features
      if (removedCount > 0) {
        console.log(`Cleaned up ${removedCount} features more than 500 miles outside viewport`)
        
        const source = this.map.getSource('geojson-data')
        // Update the source with filtered features
        source.setData(markRaw({
          type: 'FeatureCollection',
          features: featuresWithinBuffer.map(f => markRaw(f))
        }))
        
        // Invalidate cache since data changed
        this.invalidateSourceCache()
        
        // Update feature count
        this.updateFeatureCount()
        
        // Update label markers
        if (this.showAllLabels && this.labelMarkerManager) {
          this.labelMarkerManager.updateMarkers(featuresWithinBuffer)
        }
      }
    },
    getFeatureCoordinates(geometry) {
      return getCoordinatesFromGeometry(geometry)
    },
    updateFeatureCount() {
      if (this.featureCountUpdatePending) return
      this.featureCountUpdatePending = true

      this.$nextTick(() => {
        if (this.map && this.map.getSource('geojson-data')) {
          const source = this.map.getSource('geojson-data')
          const serialized = source.serialize()
          const data = serialized.data || { type: 'FeatureCollection', features: [] }
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
    async toggleTerrain() {
      if (!this.map) return

      const newState = !this.terrainEnabled

      if (newState) {
        // Enable 3D: add terrain and tilt the map
        this.terrainEnabled = true
        
        // Setup terrain (parallelizes source setup and sky/atmosphere)
        // We need to await this to ensure the terrain source is added before tilting
        // But we don't wait for tiles to load - they'll load in background
        await this.setupTerrain()
        
        // Tilt immediately after source is added - terrain tiles will load in background
        // The terrain source exists, so DEM errors won't occur
        // Tiles will load progressively as the user views different areas
        this.map.easeTo({ pitch: 50, duration: 800 })
        
        // Show tooltip if not shown before
        if (!this.showTerrainTooltip) {
          this.showTerrainTooltip = true
          // Hide after 3 seconds
          setTimeout(() => {
            this.showTerrainTooltip = false
          }, 3000)
        }
      } else {
        // Disable 3D: remove terrain and reset tilt
        this.terrainEnabled = false
        this.removeTerrain()
        this.map.easeTo({ pitch: 0, duration: 800 })
      }
    },
    async setupTerrain() {
      if (!this.maptilerConfig) return
      
      // Check if atmosphere should be applied based on current layer
      const tileSource = this.tileSources.find(s => s.id === this.selectedLayer)
      const layerName = tileSource?.name || ''
      const applyAtmosphere = this.shouldApplyAtmosphere(layerName)
      
      // Parallelize terrain setup and hillshade setup
      await Promise.all([
        maptilerSetupTerrain(this.map, this.maptilerConfig, applyAtmosphere),
        // Hillshade can be set up in parallel if enabled
        this.hillshadeEnabled ? Promise.resolve(this.addHillshadeIfNeeded()) : Promise.resolve()
      ])
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

    /**
     * Wait for a Vue ref element to be available in the DOM
     * @param {string} refName - Name of the ref to wait for
     * @param {number} timeout - Optional timeout in milliseconds (default: 2000)
     * @returns {Promise<HTMLElement>} Resolves with the element when available
     * @throws {Error} If element is not found within timeout
     */
    waitForElement(refName, timeout = 2000) {
      // Return immediately if element exists
      if (this.$refs[refName] instanceof HTMLElement) {
        return Promise.resolve(this.$refs[refName])
      }

      return new Promise((resolve, reject) => {
        const observer = new MutationObserver(() => {
          if (this.$refs[refName] instanceof HTMLElement) {
            observer.disconnect()
            clearTimeout(timeoutId)
            resolve(this.$refs[refName])
          }
        })

        // Watch for DOM changes
        observer.observe(this.$el, { childList: true, subtree: true })

        // Safety timeout
        const timeoutId = setTimeout(() => {
          observer.disconnect()
          reject(new Error(`Element ${refName} not found within ${timeout}ms`))
        }, timeout)
      })
    },

    /**
     * Ensure map is resized after operations
     * Resizes immediately if map is loaded, otherwise waits for 'load' event
     */
    ensureMapResize() {
      if (!this.map) return

      // If map is already loaded, resize immediately
      if (this.map.loaded()) {
        this.map.resize()
      } else {
        // Otherwise wait for load event
        this.map.once('load', () => {
          if (this.map) {
            this.map.resize()
          }
        })
      }
    },

    /**
     * Log current map state to console
     */
    logMapState() {
      if (!this.map) {
        console.log('Map State: Map not initialized')
        return
      }

      const userSettings = this.$store.state.userSettings || {}
      const mapSettings = userSettings.map || {}

      const state = {
        map: {
          center: this.map.getCenter(),
          zoom: this.map.getZoom(),
          pitch: this.map.getPitch(),
          bearing: this.map.getBearing(),
          loaded: this.map.loaded()
        },
        layer: {
          selected: this.selectedLayer,
          available: this.tileSources.length
        },
        features: {
          inExtent: this.featuresInExtent.length,
          loaded: this.featureCount
        },
        settings: {
          antialias: mapSettings.enable_antialias || false,
          terrain: {
            enabled: this.terrainEnabled,
            default: mapSettings.enable_3d_terrain || false,
            available: this.maptilerConfig?.isAvailable() || false
          },
          hillshade: {
            enabled: this.hillshadeEnabled,
            default: mapSettings.enable_hillshade || false,
            available: this.maptilerConfig?.isAvailable() || false
          },
          defaultBasemap: mapSettings.default_basemap || 'osm',
          replaceIconsLowZoom: mapSettings.replace_icons_low_zoom !== undefined 
            ? mapSettings.replace_icons_low_zoom 
            : true
        },
        mode: {
          isPublicShare: this.isPublicShareMode,
          isCollectionMode: this.isCollectionMode,
          collectionId: this.collectionId,
          isTagFilterActive: this.isTagFilterActive
        }
      }

      console.log('🗺️ Map State on Load:', state)
    },

    /**
     * Wait for a specific map event to fire
     * @param {string} eventName - Name of the event to wait for
     * @param {number} timeout - Optional timeout in milliseconds (default: 30000)
     * @returns {Promise} Resolves when event fires or timeout occurs
     */
    waitForMapEvent(eventName, timeout = 30000) {
      if (!this.map) {
        return Promise.resolve()
      }

      return new Promise((resolve) => {
        // If map is already loaded and we're waiting for 'load', resolve immediately
        if (eventName === 'load' && this.map.loaded()) {
          resolve()
          return
        }

        const timeoutId = setTimeout(() => {
          console.warn(`Timeout waiting for ${eventName} event`)
          resolve()
        }, timeout)

        this.map.once(eventName, () => {
          clearTimeout(timeoutId)
          resolve()
        })
      })
    },

    /**
     * Apply tile source to the map (style-based or raster)
     * @param {string} layerValue - Layer ID to apply
     * @returns {Promise} Resolves when tile source is applied
     */
    async applyTileSource(layerValue) {
      if (!this.map) return

      const tileSource = this.tileSources.find(s => s.id === layerValue)
      if (!tileSource) {
        console.error(`Tile source not found: ${layerValue}`)
        return
      }

      const clientConfig = tileSource.client_config || {}
      const isStyleBased = clientConfig.style_url || clientConfig.type === 'maptiler'

      if (isStyleBased) {
        // Style-based source (e.g., MapTiler)
        const styleUrl = clientConfig.style_url
        this.map.setStyle(styleUrl)
        // Wait for styledata event to ensure style is loaded
        await this.waitForMapEvent('styledata')
      } else {
        // Raster-based source
        const url = clientConfig.url || `/api/tiles/${layerValue}/{z}/{x}/{y}`

        let tiles
        if (clientConfig.tileSubdomains && Array.isArray(clientConfig.tileSubdomains)) {
          tiles = clientConfig.tileSubdomains.map(subdomain =>
            url.replace('{s}', subdomain)
          )
        } else {
          tiles = [url.replace('{s}', clientConfig.tileSubdomains?.[0] || 'a')]
        }

        // Set blank style first
        this.map.setStyle({
          version: 8,
          glyphs: '/api/fonts/{fontstack}/{range}.pbf',
          sources: {},
          layers: []
        })

        // Wait for styledata event
        await this.waitForMapEvent('styledata')

        // Add raster source and layer
        this.map.addSource('raster-source', {
          type: 'raster',
          tiles: tiles,
          tileSize: clientConfig.tileSize || 256,
          attribution: clientConfig.attribution || ''
        })
        this.map.addLayer({
          id: 'raster-layer',
          type: 'raster',
          source: 'raster-source',
          minzoom: clientConfig.minzoom || 0,
          maxzoom: clientConfig.maxzoom || 22
        })
      }
    },

    /**
     * Check if atmosphere should be applied for a given layer
     * @param {string} layerName - Name of the layer
     * @returns {boolean} True if atmosphere should be applied
     */
    shouldApplyAtmosphere(layerName) {
      if (!this.terrainEnabled) return false
      const name = (layerName || '').toLowerCase()
      return name.includes('imagery') || name.includes('satellite')
    },

    /**
     * Apply terrain and hillshade with conditional atmosphere
     * Parallelizes terrain and hillshade setup for faster initialization
     * @param {string} layerValue - Layer ID to check for atmosphere eligibility
     */
    async applyTerrainAndHillshade(layerValue) {
      if (!this.map || !this.maptilerConfig?.isAvailable()) return

      const tileSource = this.tileSources.find(s => s.id === layerValue)
      const layerName = tileSource?.name || ''

      // Parallelize terrain and hillshade setup
      const promises = []

      // Apply terrain with conditional atmosphere
      if (this.terrainEnabled) {
        const applyAtmosphere = this.shouldApplyAtmosphere(layerName)
        promises.push(maptilerSetupTerrain(this.map, this.maptilerConfig, applyAtmosphere))
      }

      // Apply hillshade if enabled (can happen in parallel with terrain)
      if (this.hillshadeEnabled) {
        promises.push(Promise.resolve(this.addHillshadeIfNeeded()))
      }

      // Wait for all setup operations to complete
      await Promise.all(promises)
    },

    /**
     * Destroy the map instance completely
     */
    destroyMap() {
      // Clean up label markers
      if (this.labelMarkerManager) {
        this.labelMarkerManager.clearAllMarkers()
        this.labelMarkerManager = null
      }

      // Completely destroy the map
      if (this.map) {
        this.map.remove()
        this.map = null
      }
    },

    /**
     * Create a new map instance with the given configuration
     * @param {Object} mapConfig - Map configuration (center, zoom, pitch, bearing)
     */
    createMapInstance(mapConfig) {
      // Ensure map container is available
      if (!this.$refs.mapContainer || !(this.$refs.mapContainer instanceof HTMLElement)) {
        throw new Error('Map container is not available')
      }

      // Get anti-aliasing setting from user preferences
      const userSettings = this.$store.state.userSettings || {}
      const enableAntialias = userSettings.map?.enable_antialias || false

      // Create MapLibre map
      this.map = markRaw(initializeMap(this.$refs.mapContainer, {
        center: mapConfig.center,
        zoom: mapConfig.zoom,
        pitch: mapConfig.pitch || 0,
        bearing: mapConfig.bearing || 0,
        glyphsUrl: '/api/fonts/{fontstack}/{range}.pbf',
        antialias: enableAntialias
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

    /**
     * Switch map layer - completely resets map and re-renders with new tilesource
     * Uses event-driven architecture instead of polling/waiting
     * @param {string} layerValue - Layer ID to switch to
     * @param {boolean} isInitialSetup - If true, skip map recreation (for initial load)
     */
    async switchMapLayer(layerValue, isInitialSetup = false) {
      if (!this.map) return

      // Cancel any pending API requests to prevent race conditions
      if (this.currentAbortController) {
        this.currentAbortController.abort()
        this.currentAbortController = null
      }

      // Save current map state and features
      const mapState = getMapState(this.map)
      if (!mapState) return

      // Save GeoJSON data BEFORE any style changes (setStyle() destroys all sources)
      // Use persistent cache first, then try to get from source, then fall back to cache
      let geojsonData = null
      
      // First, try to get from persistent cache (most reliable)
      if (this.cachedGeoJsonData && this.cachedGeoJsonData.features && this.cachedGeoJsonData.features.length > 0) {
        geojsonData = this.cachedGeoJsonData
      }
      
      // If cache is empty or doesn't exist, try to get from source
      if (!geojsonData || !geojsonData.features || geojsonData.features.length === 0) {
        let attempts = 0
        while (!geojsonData && attempts < 3) {
          const sourceData = getGeoJsonData(this.map)
          if (sourceData && sourceData.features && sourceData.features.length > 0) {
            geojsonData = sourceData
            // Update cache with fresh data from source
            this.cachedGeoJsonData = markRaw({
              type: 'FeatureCollection',
              features: sourceData.features.map(f => markRaw(f))
            })
            break
          }
          if (attempts < 2) {
            // Wait a brief moment and try again (source might be in transitional state)
            await new Promise(resolve => setTimeout(resolve, 10))
          }
          attempts++
        }
      }
      
      // Ensure we have a valid FeatureCollection structure even if source doesn't exist
      if (!geojsonData) {
        geojsonData = { type: 'FeatureCollection', features: [] }
      }
      
      // Store whether we actually had features to restore
      const hadFeaturesToRestore = geojsonData.features && geojsonData.features.length > 0
      
      const terrainEnabled = this.terrainEnabled && this.maptilerConfig?.isAvailable()
      const hillshadeEnabled = this.hillshadeEnabled

      // Update selected layer
      this.selectedLayer = layerValue
      const tileSource = this.tileSources.find(s => s.id === layerValue)
      if (!tileSource) {
        console.error(`Tile source not found: ${layerValue}`)
        return
      }

      // Check if this is a style-based source (MapTiler) - these can preserve the map
      const clientConfig = tileSource.client_config || {}
      const isStyleBased = clientConfig.style_url || clientConfig.type === 'maptiler'

      // For initial setup or style-based sources, preserve the map and just change the style
      if (isInitialSetup || isStyleBased) {
        // For style-based sources, preserve features by restoring immediately after style change
        await this.applyTileSource(layerValue)
        
        // Restore map view immediately after style loads (before other operations)
        // This prevents the visible reset that happens when setStyle() resets pitch/bearing
        restoreMapView(this.map, mapState.center, mapState.zoom, mapState.pitch, mapState.bearing)
        
        // Wait for style to be ready
        await this.waitForMapEvent('idle')
        
        // Restore features immediately (setStyle() destroys sources, but we restore them right away)
        await restoreGeoJsonFeatures(this.map, geojsonData, this.showAllLabels, this.labelMarkerManager)
        
        // Re-initialize feature highlighting after features are restored
        this.updateFeatureHighlighting()
        
        // Wait a moment for the source to stabilize before checking
        await new Promise(resolve => setTimeout(resolve, 100))
        
        // Verify features were actually restored and update cache
        const sourceAfterRestore = this.map.getSource('geojson-data')
        let featuresRestored = false
        if (sourceAfterRestore) {
          const serialized = sourceAfterRestore.serialize()
          const restoredData = serialized.data
          featuresRestored = restoredData && restoredData.features && restoredData.features.length > 0
          
          // Update persistent cache with restored data
          if (featuresRestored) {
            this.cachedGeoJsonData = markRaw({
              type: 'FeatureCollection',
              features: restoredData.features.map(f => markRaw(f))
            })
          }
        }
        
        // If we had features to restore but they weren't restored, try restoring again with cached data
        if (hadFeaturesToRestore && !featuresRestored) {
          console.log('Features were not restored, attempting to restore from cached data')
          await restoreGeoJsonFeatures(this.map, geojsonData, this.showAllLabels, this.labelMarkerManager)
          
          // Re-initialize feature highlighting after retry restore
          this.updateFeatureHighlighting()
          
          // Verify again after second attempt and update cache
          await new Promise(resolve => setTimeout(resolve, 100))
          const sourceAfterRetry = this.map.getSource('geojson-data')
          if (sourceAfterRetry) {
            const serialized = sourceAfterRetry.serialize()
            const retryData = serialized.data
            featuresRestored = retryData && retryData.features && retryData.features.length > 0
            
            // Update cache with retry data
            if (featuresRestored) {
              this.cachedGeoJsonData = markRaw({
                type: 'FeatureCollection',
                features: retryData.features.map(f => markRaw(f))
              })
            }
          }
          
          // Only reload from API if restoration still failed and we have no cached bounds
          if (!featuresRestored && this.loadedBounds.size === 0) {
            console.log('Restoration failed, loading from API (no cached bounds)')
            await this.loadDataForCurrentView()
          }
        }
        
        // Apply terrain and hillshade
        await this.applyTerrainAndHillshade(layerValue)
        return
      }

      // For raster-based layer switching, completely reset the map
      // 1. Destroy the map
      this.destroyMap()

      // 2. Ensure container is available
      await this.$nextTick()
      if (!this.$refs.mapContainer || !(this.$refs.mapContainer instanceof HTMLElement)) {
        console.error('Map container not available')
        this.loadError = 'Map container not available'
        return
      }

      // 3. Create new map instance with saved state
      try {
        this.createMapInstance({
          center: [mapState.center.lng, mapState.center.lat],
          zoom: mapState.zoom,
          pitch: mapState.pitch,
          bearing: mapState.bearing
        })

        // 4. Wait for map to load (event-driven)
        await this.waitForMapEvent('load')

        // 5. Apply new tile source (event-driven)
        await this.applyTileSource(layerValue)

        // 6. Restore map view immediately after style loads (before other operations)
        // This prevents the visible reset that happens when setStyle() resets pitch/bearing
        restoreMapView(this.map, mapState.center, mapState.zoom, mapState.pitch, mapState.bearing)

        // 7. Wait for style to be ready (event-driven)
        await this.waitForMapEvent('idle')

        // 8. Restore GeoJSON features
        await restoreGeoJsonFeatures(this.map, geojsonData, this.showAllLabels, this.labelMarkerManager)

        // Re-initialize feature highlighting after features are restored
        this.updateFeatureHighlighting()

        // 8a. Verify features were actually restored and update cache
        // Wait a moment for the source to stabilize before checking
        await new Promise(resolve => setTimeout(resolve, 100))
        
        const sourceAfterRestore = this.map.getSource('geojson-data')
        let featuresRestored = false
        if (sourceAfterRestore) {
          const serialized = sourceAfterRestore.serialize()
          const restoredData = serialized.data
          featuresRestored = restoredData && restoredData.features && restoredData.features.length > 0
          
          // Update persistent cache with restored data
          if (featuresRestored) {
            this.cachedGeoJsonData = markRaw({
              type: 'FeatureCollection',
              features: restoredData.features.map(f => markRaw(f))
            })
          }
        }

        // If we had features to restore but they weren't restored, try restoring again with cached data
        if (hadFeaturesToRestore && !featuresRestored) {
          // Features existed but weren't restored - try restoring again with the cached geojsonData
          console.log('Features were not restored, attempting to restore from cached data')
          await restoreGeoJsonFeatures(this.map, geojsonData, this.showAllLabels, this.labelMarkerManager)
          
          // Re-initialize feature highlighting after retry restore
          this.updateFeatureHighlighting()
          
          // Verify again after second attempt and update cache
          await new Promise(resolve => setTimeout(resolve, 100))
          const sourceAfterRetry = this.map.getSource('geojson-data')
          if (sourceAfterRetry) {
            const serialized = sourceAfterRetry.serialize()
            const retryData = serialized.data
            featuresRestored = retryData && retryData.features && retryData.features.length > 0
            
            // Update cache with retry data
            if (featuresRestored) {
              this.cachedGeoJsonData = markRaw({
                type: 'FeatureCollection',
                features: retryData.features.map(f => markRaw(f))
              })
            }
          }
          
          // Only reload from API if restoration still failed and we have no cached bounds
          if (!featuresRestored && this.loadedBounds.size === 0) {
            console.log('Restoration failed, loading from API (no cached bounds)')
            await this.loadDataForCurrentView()
          }
        } else if (!hadFeaturesToRestore && !featuresRestored && this.loadedBounds.size === 0) {
          // No features existed before, but ensure we have data loaded for current viewport
          // This handles the case where the map was just created or features haven't been loaded yet
          await this.loadDataForCurrentView()
        }

        // 9. Apply terrain and hillshade with conditional atmosphere
        await this.applyTerrainAndHillshade(layerValue)

        // 10. Resize map to ensure proper rendering
        this.ensureMapResize()
      } catch (error) {
        console.error('Error switching map layer:', error)
        this.loadError = error.message || 'Failed to switch map layer'
      }
    },
    /**
     * Helper method to perform programmatic navigation with bbox refresh
     * This ensures that data loads after navigation completes, even if the area was previously cached
     * @param {Function} navigationFn - Function that performs the navigation (should return void or Promise)
     * @param {boolean} clearAllBounds - If true, clears all loaded bounds; if false, only clears relevant bounds
     */
    async navigateAndRefresh(navigationFn, clearAllBounds = true) {
      if (!this.map) return

      // Clear loaded bounds to force refresh after navigation
      if (clearAllBounds) {
        this.loadedBounds.clear()
      }

      // Execute the navigation
      await navigationFn()

      // Wait for the map movement to complete
      // Use a one-time listener for moveend to ensure data loads after animation
      return new Promise((resolve) => {
        const onMoveEnd = () => {
          // Trigger immediate (non-debounced) data load
          this.loadDataForCurrentView()
          resolve()
        }

        // Listen for moveend event (fires when flyTo/fitBounds animation completes)
        this.map.once('moveend', onMoveEnd)
      })
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
          this.navigateAndRefresh(() => {
            this.map.flyTo({
              center: [longitude, latitude],
              zoom: stateLevelZoom,
              pitch: 0,  // Reset tilt to flat
              bearing: 0,  // Reset rotation to north
              duration: 500
            })
          })
          return
        }
      }

      // Fallback: just zoom to state level at current center
      this.navigateAndRefresh(() => {
        this.map.flyTo({
          zoom: stateLevelZoom,
          pitch: 0,  // Reset tilt to flat
          bearing: 0,  // Reset rotation to north
          duration: 500
        })
      })
    },
    zoomToFeature(feature) {
      if (!this.map || !feature) {
        console.warn('zoomToFeature: Missing map or feature', { map: !!this.map, feature: !!feature })
        return
      }

      // Ensure feature is on the map (for search results that might not be loaded)
      // Check if feature already exists by database_id to avoid duplicates
      const properties = feature.properties || {}
      const featureId = properties.database_id
      
      if (featureId) {
        const source = this.map.getSource('geojson-data')
        const serialized = source.serialize()
        const currentData = serialized.data || { type: 'FeatureCollection', features: [] }
          const existingFeatures = currentData.features || []
          
          // Check if feature already exists
          const exists = existingFeatures.some(f => f.properties?.database_id === featureId)
          
          if (!exists) {
            // Feature is already GeoJSON, just ensure it has the right structure
            const geoJsonFeature = {
              type: 'Feature',
              geometry: feature.geometry,
              properties: properties
            }
            
            // Add the feature to the map
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

      // Get feature geometry (pure GeoJSON)
      const geometry = feature.geometry
      
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
        // Check if we need to adjust padding for mobile with feature info box
        const isMobile = window.innerWidth < 768
        const hasFeatureInfoBox = this.selectedFeature && !this.isEditingFeature
        
        let padding = 50
        if (isMobile && hasFeatureInfoBox) {
          // On mobile with feature info box, center on top 2/3 of screen
          // Feature info box takes up to 60vh at bottom
          const viewportHeight = window.innerHeight
          const infoBoxMaxHeight = viewportHeight * 0.6
          // Add extra bottom padding to push feature into top 2/3
          padding = { top: 50, bottom: infoBoxMaxHeight + 20, left: 50, right: 50 }
        }
        
        return this.navigateAndRefresh(() => {
          this.map.flyTo({
            center: [minLon, minLat],
            zoom: 10,
            duration: 500,
            padding: padding
          })
        })
      }

      // Calculate padding based on screen size and feature info box visibility
      const isMobile = window.innerWidth < 768
      const hasFeatureInfoBox = this.selectedFeature && !this.isEditingFeature
      
      let padding
      if (isMobile && hasFeatureInfoBox) {
        // On mobile with feature info box, center on top 2/3 of screen
        // Feature info box takes up to 60vh at bottom
        const viewportHeight = window.innerHeight
        const infoBoxMaxHeight = viewportHeight * 0.6
        // Add extra bottom padding to push feature into top 2/3
        padding = { top: 50, bottom: infoBoxMaxHeight + 20, left: 50, right: 50 }
      } else {
        // Default padding for desktop or mobile without feature info box
        padding = { top: 50, bottom: 50, left: 50, right: 50 }
      }

      // Fly to feature
      return this.navigateAndRefresh(() => {
        try {
          // Create LngLatBounds: sw corner [minLon, minLat], ne corner [maxLon, maxLat]
          const bounds = new maplibregl.LngLatBounds(
            [minLon, minLat], // southwest corner
            [maxLon, maxLat]  // northeast corner
          )
          // Use fitBounds which is more reliable for bounds
          this.map.fitBounds(bounds, {
            padding: padding,
            duration: 500
          })
        } catch (error) {
          console.error('zoomToFeature: Error fitting bounds (fallback)', error, error.stack)
          // Final fallback: try flyTo
          try {
            const bounds = new maplibregl.LngLatBounds([minLon, minLat], [maxLon, maxLat])
            this.map.flyTo({
              bounds: bounds,
              padding: typeof padding === 'object' ? padding.top : padding,
              duration: 500
            })
          } catch (error2) {
            console.error('zoomToFeature: Error with flyTo fallback (fallback)', error2)
          }
        }
      })
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
        const serialized = source.serialize()
        const data = serialized.data || { type: 'FeatureCollection', features: [] }
        const properties = feature.properties || feature.get?.('properties') || {}
        const featureId = properties.database_id

        if (featureId && data.features) {
          data.features = data.features.filter(f => f.properties?.database_id !== featureId)
          source.setData(data)
          this.updateFeatureCount()
          
          // Remove label marker for deleted feature if labels are visible
          if (this.showAllLabels && this.labelMarkerManager) {
            this.labelMarkerManager.removeMarker(String(featureId))
          }
        }
      }

      this.selectedFeature = null
      this.isEditingFeature = false
    },
    async handleQuickPointCreated(createdFeature) {
      // Add the newly created feature directly to the map
      if (createdFeature && this.map && this.map.getSource('geojson-data')) {
        // Process icon if this is a Point feature
        if (createdFeature.geometry?.type !== 'Point') {
          throw new Error("Quick point was not a point")
        }
        const iconUrl = getFeatureIconUrl(createdFeature.properties)
        const zoom = this.map.getZoom()
        const userSettings = this.$store.state.userSettings || {}
        const replaceIconsLowZoom = userSettings.map?.replace_icons_low_zoom !== undefined
          ? userSettings.map.replace_icons_low_zoom
          : true
        const shouldShowIcon = iconUrl && shouldUseIcon(zoom, iconUrl, replaceIconsLowZoom)

        if (shouldShowIcon) {
          const resolvedUrl = getIconSourceUrl(iconUrl, createdFeature.properties)
          const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`
          createdFeature.properties['_icon-id'] = iconId

          // Load icon if not already loaded
          if (!this.map.hasImage(iconId)) {
            try {
              await loadIconImage(this.map, iconId, resolvedUrl)
            } catch (err) {
              console.warn(`Failed to load icon ${iconId}:`, err)
              // Remove icon metadata on failure
              delete createdFeature.properties['_icon-id']
            }
          }
        }
        
        const source = this.map.getSource('geojson-data')
        const serialized = source.serialize()
        const currentData = serialized.data || { type: 'FeatureCollection', features: [] }
        const existingFeatures = currentData.features || []
        
        // Add the new feature to the map
        existingFeatures.push(createdFeature)
        source.setData({
          type: 'FeatureCollection',
          features: existingFeatures
        })
        
        // Update feature count
        this.updateFeatureCount()
        
        // Update features in extent
        this.updateFeaturesInExtent()
        
        // Update label markers if enabled
        if (this.showAllLabels && this.labelMarkerManager) {
          this.labelMarkerManager.updateMarkers(existingFeatures)
        }
      }
      
      this.showQuickPointDialog = false
    },
    handleFeatureSaved(updatedFeature) {
      // If updated feature data is provided, update in-memory without network call
      if (updatedFeature && updatedFeature.properties?.database_id) {
        const featureId = updatedFeature.properties.database_id
        
        if (this.map && this.map.getSource('geojson-data')) {
          const source = this.map.getSource('geojson-data')
          const serialized = source.serialize()
          const currentData = serialized.data || { type: 'FeatureCollection', features: [] }
          const features = currentData.features || []
          
          // Find the feature on the map by database_id
          const featureIndex = features.findIndex(f => 
            f.properties?.database_id === featureId && 
            !f.properties?._isLabelPoint && 
            !f.properties?._isSmallFeatureReplacement
          )
          
          if (featureIndex !== -1) {
            // Feature exists on map - update it in-place
            const existingFeature = features[featureIndex]
            
            // Create a deep copy of the updated feature to avoid reference issues
            const updatedFeatureCopy = JSON.parse(JSON.stringify(updatedFeature))
            
            // Ensure database_id is preserved
            updatedFeatureCopy.properties.database_id = featureId
            
            // Process icon if this is a Point feature
            if (updatedFeatureCopy.geometry?.type === 'Point') {
              const iconUrl = getFeatureIconUrl(updatedFeatureCopy.properties)
              const zoom = this.map.getZoom()
              const userSettings = this.$store.state.userSettings || {}
              const replaceIconsLowZoom = userSettings.map?.replace_icons_low_zoom !== undefined 
                ? userSettings.map.replace_icons_low_zoom 
                : true
              const shouldShowIcon = iconUrl && shouldUseIcon(zoom, iconUrl, replaceIconsLowZoom)
              
              if (shouldShowIcon) {
                const resolvedUrl = getIconSourceUrl(iconUrl, updatedFeatureCopy.properties)
                const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`
                updatedFeatureCopy.properties['_icon-id'] = iconId
                
                // Load icon if not already loaded
                if (!this.map.hasImage(iconId)) {
                  loadIconImage(this.map, iconId, resolvedUrl).catch(err => {
                    console.warn(`Failed to load icon ${iconId}:`, err)
                    // Remove icon metadata on failure
                    delete updatedFeatureCopy.properties['_icon-id']
                  })
                }
              } else {
                // Remove icon metadata to show as circle
                delete updatedFeatureCopy.properties['_icon-id']
              }
            } else {
              // Remove icon metadata for non-point features
              delete updatedFeatureCopy.properties['_icon-id']
            }
            
            // Replace the feature in the array
            features[featureIndex] = markRaw(updatedFeatureCopy)
            
            // Update the source with the modified features
            source.setData(markRaw({
              type: 'FeatureCollection',
              features: features.map(f => markRaw(f))
            }))
            
            // Update label markers if labels are visible
            if (this.showAllLabels && this.labelMarkerManager) {
              this.labelMarkerManager.updateMarkers(features)
            }
            
            // Update features in extent and feature count
            this.updateFeaturesInExtent()
            this.updateFeatureCount()
            
            // Update selected feature if it's the one that was saved
            if (this.selectedFeature && 
                (this.selectedFeature.properties?.database_id === featureId || 
                 this.selectedFeature.get?.('properties')?.database_id === featureId)) {
              // Update the selected feature reference
              this.selectedFeature = markRaw(convertMapLibreFeature(updatedFeatureCopy))
            }
          } else {
            // Feature not on map - reload to get it (or do nothing)
            // For now, we'll do nothing since the feature isn't visible
            console.log(`Feature ${featureId} not found on map, skipping update`)
          }
        }
      } else {
        // No updated feature data provided - fall back to reload
        this.loadedBounds.clear()
        this.loadDataForCurrentView()
      }
      
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
            const serialized = source.serialize()
            const currentData = serialized.data || { type: 'FeatureCollection', features: [] }
            
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
    filterExistingFeaturesByTags(selectedTags) {
      if (!this.map || !this.map.getSource('geojson-data') || !selectedTags || selectedTags.length === 0) {
        return
      }

      // Mark tag filter as active for immediate filtering
      this.isTagFilterActive = true

      const source = this.map.getSource('geojson-data')
      const serialized = source.serialize()
      const data = serialized.data || { type: 'FeatureCollection', features: [] }
      const allFeatures = data.features || []

      // Filter features that match all selected tags
      const filteredFeatures = allFeatures.filter(f => {
        // Skip label points and replacement points
        if (f.properties?._isLabelPoint || f.properties?._isSmallFeatureReplacement) {
          return false
        }

        const props = f.properties || {}
        
        // Get tags - handle both array and JSON string formats
        let tags = props.tags || []
        if (typeof tags === 'string') {
          try {
            tags = JSON.parse(tags)
          } catch (e) {
            tags = []
          }
        }
        if (!Array.isArray(tags)) {
          tags = []
        }

        // Get system_tags - handle both array and JSON string formats
        let systemTags = props.system_tags || []
        if (typeof systemTags === 'string') {
          try {
            systemTags = JSON.parse(systemTags)
          } catch (e) {
            systemTags = []
          }
        }
        if (!Array.isArray(systemTags)) {
          systemTags = []
        }

        // Combine all tags
        const allFeatureTags = [...tags, ...systemTags]

        // Check if feature has all selected tags
        return selectedTags.every(tag => allFeatureTags.includes(tag))
      })

      // Update the map with filtered features immediately
      if (filteredFeatures.length > 0) {
        // Filter out points on borders
        const filteredGeojsonFeatures = filterPointsOnBorders(filteredFeatures)

        source.setData(markRaw({
          type: 'FeatureCollection',
          features: filteredGeojsonFeatures.map(f => markRaw(f))
        }))

        this.updateFeatureCount()
        this.updateFeaturesInExtent()
      } else {
        // No matching features in memory
        source.setData(markRaw({
          type: 'FeatureCollection',
          features: []
        }))
        this.updateFeatureCount()
        this.updateFeaturesInExtent()
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

      // Convert features to GeoJSON if needed (features are now native GeoJSON)
      const geojsonFeatures = filteredFeatures.map(f => {
        return markRaw({
          type: 'Feature',
          properties: f.properties || {},
          geometry: f.geometry
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

      const properties = feature.properties || {}
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
          const serialized = source.serialize()
          const data = serialized.data || { type: 'FeatureCollection', features: [] }
          if (data.features) {
            data.features = data.features.filter(f => f.properties?.database_id !== featureId)
            source.setData(data)
            this.updateFeatureCount()
          }
        }

        if (this.selectedFeature) {
          const propsSelected = this.selectedFeature.properties || {}
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
        const properties = this.selectedFeature.properties || {}
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
    /**
     * Update paint properties for lines, polygon-outlines, and points
     * to highlight hovered and selected features
     */
    updateFeatureHighlighting() {
      if (!this.map) return
      
      // Get selected feature ID if dialog is open (either info or edit dialog)
      let selectedFeatureId = null
      if (this.selectedFeature) {
        selectedFeatureId = this.selectedFeature.properties?.database_id || this.selectedFeature.get?.('properties')?.database_id
      }
      
      // Generate stroke width expression with highlight for lines and polygon outlines
      const lineWidthExpression = getStrokeWidthExpressionWithHighlight(
        2, // default width
        this.hoveredFeatureId,
        selectedFeatureId,
        1.5 // multiplier for highlighted features (50% thicker)
      )
      
      // Update lines layer
      if (this.map.getLayer('lines')) {
        this.map.setPaintProperty('lines', 'line-width', lineWidthExpression)
      }
      
      // Update polygon-outlines layer
      if (this.map.getLayer('polygon-outlines')) {
        this.map.setPaintProperty('polygon-outlines', 'line-width', lineWidthExpression)
      }
      
      // Generate circle radius expressions with highlight for points
      // Regular points: 4px base, 2px min
      const baseRadiusExpression = createZoomBasedRadiusExpression(4, 2)
      const radiusExpression = getCircleRadiusExpressionWithHighlight(
        baseRadiusExpression,
        this.hoveredFeatureId,
        selectedFeatureId,
        1.5 // multiplier for highlighted features (50% larger)
      )
      
      // Update points layer (circles without icons)
      if (this.map.getLayer('points')) {
        this.map.setPaintProperty('points', 'circle-radius', radiusExpression)
      }
      
      // Replacement points: 3px base, 1.5px min
      const replacementRadiusExpression = createZoomBasedRadiusExpression(3, 1.5)
      const replacementRadiusHighlight = getCircleRadiusExpressionWithHighlight(
        replacementRadiusExpression,
        this.hoveredFeatureId,
        selectedFeatureId,
        1.5 // multiplier for highlighted features (50% larger)
      )
      
      // Update replacement-points layer
      if (this.map.getLayer('replacement-points')) {
        this.map.setPaintProperty('replacement-points', 'circle-radius', replacementRadiusHighlight)
      }
      
      // Generate icon size expression with highlight for point icons
      // Use smaller multiplier for icons (1.05x) compared to circles (1.5x)
      const iconSizeExpression = getIconSizeExpressionWithHighlight(
        1.0, // base icon size
        this.hoveredFeatureId,
        selectedFeatureId,
        1.05 // multiplier for highlighted features (5% larger, less than circles)
      )
      
      // Update point-icons layer (icons)
      if (this.map.getLayer('point-icons')) {
        this.map.setLayoutProperty('point-icons', 'icon-size', iconSizeExpression)
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
      this.navigateAndRefresh(() => {
        this.map.flyTo({
          center: [coordinates[0], coordinates[1]],
          zoom: currentZoom,
          duration: 500
        })
      })
    },
    async handleGeocodingResult(result) {
      if (!this.map || !result) return

      // Extract coordinates from geocoding result for marker placement
      // Geocoding results have coordinates [lng, lat] directly
      let coordinates = null
      if (result.coordinates && Array.isArray(result.coordinates)) {
        coordinates = result.coordinates
      } else if (result.center && Array.isArray(result.center)) {
        coordinates = result.center
      } else {
        console.error('Geocoding result missing coordinates:', result)
        return
      }

      // Extract bbox - there will always be a bbox
      const bbox = result.bbox
      if (!bbox || !Array.isArray(bbox) || bbox.length !== 4) {
        console.error('Geocoding result missing bbox:', result)
        return
      }

      // Remove previous geocoding marker if exists
      if (this.geocodingMarker) {
        this.geocodingMarker.remove()
        this.geocodingMarker = null
      }

      // Create marker with search.png icon first
      // Import the icon asset (Vite will handle the path resolution)
      let iconUrl
      try {
        // Try to import the asset - Vite will resolve this at build time
        const iconModule = await import('@/assets/img/search.png')
        iconUrl = iconModule.default || iconModule
      } catch (error) {
        console.warn('Could not import search icon, using fallback marker:', error)
        iconUrl = null
      }

      // Create marker element
      const el = document.createElement('div')
      el.style.cursor = 'pointer'
      
      if (iconUrl) {
        // Create img element for the icon
        const img = document.createElement('img')
        img.src = iconUrl
        img.style.width = '32px'
        img.style.height = '32px'
        img.style.display = 'block'
        el.appendChild(img)
      } else {
        // Fallback: create a simple colored marker
        el.style.width = '20px'
        el.style.height = '20px'
        el.style.borderRadius = '50%'
        el.style.backgroundColor = '#3b82f6' // Blue color
        el.style.border = '2px solid white'
        el.style.boxShadow = '0 2px 4px rgba(0,0,0,0.3)'
      }

      // Make marker clickable to remove it
      el.addEventListener('click', () => {
        this.clearGeocodingMarker()
      })

      // Create and add marker immediately (before flying)
      this.geocodingMarker = new maplibregl.Marker({
        element: el,
        anchor: 'bottom' // Anchor at bottom center for point markers
      })
        .setLngLat([coordinates[0], coordinates[1]])
        .addTo(this.map)

      // Always use bbox to fly to the location
      // There will always be a bbox, so no fallback to point coordinates
      const [minLon, minLat, maxLon, maxLat] = bbox
      
      // Check if bbox is degenerate (a point) - MapTiler API sometimes returns these
      const isDegenerate = (minLon === maxLon && minLat === maxLat)
      
      if (isDegenerate) {
        // For degenerate bboxes (points), just fly to the point at zoom 15
        await this.navigateAndRefresh(() => {
          this.map.flyTo({
            center: [coordinates[0], coordinates[1]],
            zoom: 15,
            duration: 500
          })
        })
      } else {
        // For proper bboxes, fit the bounds
        await this.navigateAndRefresh(() => {
          const bounds = new maplibregl.LngLatBounds(
            [minLon, minLat], // southwest corner
            [maxLon, maxLat]  // northeast corner
          )
          this.map.fitBounds(bounds, {
            padding: { top: 50, bottom: 50, left: 50, right: 50 },
            duration: 500
          })
        })
      }
    },
    clearGeocodingMarker() {
      // Remove geocoding marker if it exists
      if (this.geocodingMarker) {
        this.geocodingMarker.remove()
        this.geocodingMarker = null
      }
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
        // Set loading for the feature fetch
        this.isDataLoading = true
        
        const response = await fetch(`${APIHOST}/api/feature/${featureId}/`)
        if (!response.ok) {
          console.error(`Failed to fetch feature ${featureId}: ${response.statusText}`)
          this.removeFeatureIdFromUrl()
          this.isDataLoading = false
          return
        }

        const data = await response.json()
        if (!response.ok || !data.feature) {
          console.error(`Feature ${featureId} not found or access denied`)
          this.removeFeatureIdFromUrl()
          this.isDataLoading = false
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

        // Feature fetch complete - loading flag will be managed by loadDataForCurrentView during zoom
        this.isDataLoading = false

        // Wait for map to be ready (in case it's being restored from keep-alive)
        await this.waitForMap()

        // Add feature to map
        if (this.map && this.map.getSource('geojson-data')) {
          const source = this.map.getSource('geojson-data')
          const serialized = source.serialize()
          const currentData = serialized.data || { type: 'FeatureCollection', features: [] }
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

          // Wait for map to process the new data, then zoom
          await this.$nextTick()
          
          // Zoom to feature - navigateAndRefresh will trigger loadDataForCurrentView
          // which manages isDataLoading for the bbox call
          await this.zoomToFeature(markRaw(this.convertMapLibreFeature(feature)))
          this.removeFeatureIdFromUrl()
        }
      } catch (error) {
        console.error(`Error fetching feature ${featureId}:`, error)
        this.removeFeatureIdFromUrl()
        this.isDataLoading = false
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
    async waitForMap() {
      // Wait for map to be initialized (handles keep-alive restore scenarios)
      const maxWait = 5000 // 5 seconds max wait
      const checkInterval = 50 // Check every 50ms
      const startTime = Date.now()
      
      while (!this.map || !this.map.getSource('geojson-data')) {
        if (Date.now() - startTime > maxWait) {
          console.error('Timeout waiting for map to be ready')
          return
        }
        await new Promise(resolve => setTimeout(resolve, checkInterval))
      }
    },
    // Map Destruction Abstraction Layer
    performMapDestruction() {
      // Cancel any pending API requests to prevent race conditions
      if (this.currentAbortController) {
        this.currentAbortController.abort()
        this.currentAbortController = null
      }

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
      
      // Wait for container to be truly ready (event-driven)
      try {
        await this.waitForElement('mapContainer')
      } catch (error) {
        console.error('Map container not available for restore:', error.message)
        this.isMapInitializing = false
        this.isRestoring = false
        return
      }

      try {
        // Fetch available tags (refresh tag list on map restore)
        if (this.$store.state.userInfo) {
          await this.fetchAvailableTags()
        }

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
          this.switchMapLayer(this.selectedLayer)
        }

        // Restore terrain state if it was enabled
        if (this.terrainEnabled && this.maptilerConfig?.isAvailable()) {
          await this.setupTerrain()
        }

        // Restore hillshade state if it was enabled
        if (this.hillshadeEnabled && this.maptilerConfig?.isAvailable()) {
          this.addHillshadeIfNeeded()
        }

        // Reload data
        if (this.collectionId) {
          await this.handleCollectionFilter(this.collectionId)
        } else {
          await this.loadDataForCurrentView()
        }

        // Update map size after load
        this.ensureMapResize()

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
    
    // Wait for container to be truly ready (important for hot reload, event-driven)
    try {
      await this.waitForElement('mapContainer')
    } catch (error) {
      console.error('Map container not available:', error.message)
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

    // Setup terrain and hillshade preferences BEFORE updating map layer
    // so they can be applied during the initial setup
    const userSettings = this.$store.state.userSettings || {}
    const defaultTerrainOn = userSettings.map?.enable_3d_terrain || false
    const defaultHillshadeOn = userSettings.map?.enable_hillshade || false
    
    this.terrainEnabled = defaultTerrainOn && this.maptilerConfig?.isAvailable()
    this.hillshadeEnabled = defaultHillshadeOn && this.maptilerConfig?.isAvailable()

    // Update map layer to use the selected source (in case it's not the default OSM)
    // Pass true for isInitialSetup to avoid destroying and recreating the map
    if (this.selectedLayer && this.tileSources.length > 0) {
      await this.switchMapLayer(this.selectedLayer, true)
    }

    // Tilt the map if terrain is enabled
    if (this.terrainEnabled && this.map) {
      this.map.setPitch(50)
    }

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
    } else if (this.$route.query.featureId) {
      // Feature-first strategy: zoom to feature immediately for snappy UX
      // Note: zoomToFeature calls navigateAndRefresh which will load bbox data
      // after the zoom animation completes, so we don't need to call loadDataForCurrentView here
      await this.handleUrlFeatureId()
    } else {
      // Normal flow for non-featureId navigation
      await this.loadDataForCurrentView()
    }

    // Set isMapInitializing to false after initial data load completes
    // This allows map move/zoom events to trigger data loads from now on
    this.isMapInitializing = false

    // Update map size after load
    if (this.map) {
      this.map.once('load', () => {
        if (this.map) {
          this.map.resize()
        }
      })
    }

    // Initial feature list update
    this.updateFeaturesInExtent()

    // Log final map state after all initialization is complete
    this.logMapState()
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
    const hasFeatureId = !!this.$route.query.featureId

    // Reload data based on route query parameters
    if (this.map) {
      if (hasCollectionQuery) {
        // Collection mode - load collection features
        this.handleCollectionFilter(this.collectionId)
      } else if (hasFeatureId) {
        // Feature-first strategy: zoom to feature immediately
        this.handleUrlFeatureId()
      } else if (!hasTagQuery) {
        // Normal view - reload bbox data
        this.isMapInitializing = true
        this.loadDataForCurrentView().then(() => {
          this.isMapInitializing = false
          this.updateFeaturesInExtent()
          // Resize map after data loads (wait for idle since data is loading)
          if (this.map) {
            if (this.map.loaded()) {
              // Map is loaded, wait for idle to ensure data is rendered
              this.map.once('idle', () => {
                if (this.map) {
                  this.map.resize()
                }
              })
            } else {
              // Map not loaded yet, wait for load then idle
              this.map.once('load', () => {
                if (this.map) {
                  this.map.once('idle', () => {
                    if (this.map) {
                      this.map.resize()
                    }
                  })
                }
              })
            }
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
    
    // Cancel any pending zoom updates
    if (this.zoomUpdateFrame) {
      cancelAnimationFrame(this.zoomUpdateFrame)
      this.zoomUpdateFrame = null
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

