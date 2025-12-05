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
        @close="activeMobileSidebar = null"
        @layer-change="updateMapLayer"
        @unhide-feature="handleUnhideFeature"
        @unhide-all="handleUnhideAllHidden"
        @labels-visibility-change="handleLabelsVisibilityChange"
    />
  </div>
</template>

<script>
import {markRaw} from 'vue'
import 'maplibre-gl/dist/maplibre-gl.css'
import maplibregl from 'maplibre-gl'
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
  filterPointsOnBorders
} from '@/utils/map/maplibre'

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
    }
  },
  methods: {
    // Placeholder methods - will be implemented
    async handleHideFeature(feature) {},
    async handleEditBoxVisibilityChange(payload) {},
    handleTagFilterChange(tags) {},
    zoomToFeature(feature) {},
    handleDownloadFeatureKmz(feature) {},
    handleEditFeature(feature) {},
    handleCancelEdit() {},
    handleFeatureDeleted(feature) {},
    handleFeatureSaved(feature) {},
    handleElevationProfileClose() {},
    handleHoverPoint(point) {},
    handleHoverClear() {},
    handleClickPoint(point) {},
    handleFeatureSelect(feature) {},
    handleUnhideFeature(featureId) {},
    handleUnhideAllHidden() {},
    handleLabelsVisibilityChange(show) {},
    updateMapLayer(layerId) {},
    centerToUserLocation() {},
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
      // Get user location first (skip for public share mode)
      if (!this.isPublicShareMode) {
        await this.getUserLocation()
      }

      // Determine initial map center and zoom based on user location
      const mapConfig = this.getInitialMapConfig()

      // Create MapLibre map with OSM base layer and glyphs for text labels
      this.map = markRaw(initializeMap(this.$refs.mapContainer, {
        center: mapConfig.center, // [lon, lat]
        zoom: mapConfig.zoom,
        glyphsUrl: '/api/fonts/{fontstack}/{range}.pbf'
      }))

      // Initialize label marker manager
      this.labelMarkerManager = new LabelMarkerManager(this.map)
      this.labelMarkerManager.setVisibility(this.showAllLabels)

      // Setup GeoJSON source
      setupGeoJsonSource(this.map, () => {
        this.isMapInitializing = false
        
        // Trigger initial data load after map is fully loaded
        if (this.isInitialLoad && !this.collectionId && !this.isTagFilterActive) {
          setTimeout(() => {
            this.loadDataForCurrentView()
          }, 100)
        }
      })

      // Setup event listeners
      setupMapEventListeners(this.map, {
        onMoveEnd: () => {
          this.debouncedLoadData()
          this.debouncedUpdateFeaturesInExtent()
        },
        onZoomEnd: () => {
          this.debouncedLoadData()
          this.debouncedUpdateFeaturesInExtent()
          // Reprocess existing features for icon visibility at new zoom level
          this.reprocessFeaturesForZoom()
        },
        onClick: (e) => {
          // Check if layers exist before querying
          const layersToQuery = ['points', 'point-icons', 'lines', 'polygons', 'polygon-outlines']
            .filter(layerId => this.map.getLayer(layerId))
          
          if (layersToQuery.length === 0) {
            return
          }
          
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

          // Deduplicate features by database_id (same feature can appear in multiple layers)
          const uniqueFeatures = []
          const seenIds = new Set()
          
          for (const feature of clickableFeatures) {
            const featureId = feature.properties?.database_id
            if (featureId && !seenIds.has(featureId)) {
              seenIds.add(featureId)
              uniqueFeatures.push(feature)
            } else if (!featureId) {
              // If no database_id, include it anyway
              uniqueFeatures.push(feature)
            }
          }

          if (uniqueFeatures.length === 0) {
            this.selectedFeature = null
            this.isEditingFeature = false
            this.showFeaturePopup = false
          } else if (uniqueFeatures.length === 1) {
            const feature = markRaw(convertMapLibreFeature(uniqueFeatures[0]))
            this.selectedFeature = feature
            this.isEditingFeature = false
          } else {
            const convertedFeatures = uniqueFeatures.map(f => markRaw(convertMapLibreFeature(f)))
            this.overlappingFeatures = convertedFeatures
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
      this.map.on('zoom', () => {
        // Update label markers only if labels are visible
        // Skip expensive label processing when labels are hidden
        if (this.showAllLabels && this.labelMarkerManager) {
          const source = this.map.getSource('geojson-data')
          if (source && source._data && source._data.features) {
            this.labelMarkerManager.updateMarkers(source._data.features)
          }
        }
        
        // Also update icon visibility immediately during zoom
        // This prevents the delay when switching between icons and circles
        this.reprocessFeaturesForZoom()
      })

      // Add hover event listener to change cursor to pointer over features
      this.map.on('mousemove', (e) => {
        // Check if layers exist before querying
        const layersToQuery = ['points', 'point-icons', 'lines', 'polygons', 'polygon-outlines']
          .filter(layerId => this.map.getLayer(layerId))
        
        if (layersToQuery.length === 0) {
          return
        }
        
        // Query features with a small radius for hover detection
        const bbox = [
          [e.point.x - 5, e.point.y - 5],
          [e.point.x + 5, e.point.y + 5]
        ]
        const features = this.map.queryRenderedFeatures(bbox, {
          layers: layersToQuery
        })

        // Filter out label points
        const hoverableFeatures = features.filter(f => !f.properties?._isLabelPoint)

        // Change cursor to pointer if hovering over a feature
        this.map.getCanvas().style.cursor = hoverableFeatures.length > 0 ? 'pointer' : ''
      })

      // Reset cursor when leaving the map
      this.map.on('mouseout', () => {
        this.map.getCanvas().style.cursor = ''
      })
    },
    convertMapLibreFeature(mlFeature) {
      return convertMapLibreFeature(mlFeature)
    },
    debouncedLoadData() {
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
          this.addFeaturesToMap(rawData)
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
      
      // Reprocess features with new zoom level
      await addFeaturesToMap(this.map, { type: 'FeatureCollection', features }, this.showAllLabels, zoom, replaceIconsLowZoom)
      
      // Update label markers only if labels are visible
      // Skip expensive label processing when labels are hidden
      if (this.showAllLabels && this.labelMarkerManager) {
        const source = this.map.getSource('geojson-data')
        if (source && source._data && source._data.features) {
          this.labelMarkerManager.updateMarkers(source._data.features)
        }
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

      // Filter features in current bounds and exclude label points
      const featuresInBounds = features.filter(f => {
        // Skip label points - they're internal features for label rendering
        if (f.properties?._isLabelPoint) return false
        
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
          this.tileSources = data.sources

          const userSettings = this.$store.state.userSettings || {}
          const defaultBasemap = userSettings.map?.default_basemap

          if (defaultBasemap && this.tileSources.find(s => s.id === defaultBasemap)) {
            this.selectedLayer = defaultBasemap
          } else if (!this.selectedLayer || !this.tileSources.find(s => s.id === this.selectedLayer)) {
            if (this.tileSources.length > 0) {
              this.selectedLayer = this.tileSources[0].id
            }
          }
        }
      } catch (error) {
        console.error('Error fetching tile sources:', error)
        this.tileSources = [{
          id: 'osm',
          name: 'OpenStreetMap',
          type: 'osm',
          requires_proxy: false,
          client_config: {type: 'osm'}
        }]
        if (!this.selectedLayer) {
          this.selectedLayer = 'osm'
        }
      }
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

      this.selectedLayer = layerValue
      const tileSource = this.tileSources.find(s => s.id === layerValue)
      if (!tileSource) return

      const clientConfig = tileSource.client_config || {}

      // Remove existing raster layer
      if (this.map.getLayer('osm-layer')) {
        this.map.removeLayer('osm-layer')
      }
      if (this.map.getSource('osm')) {
        this.map.removeSource('osm')
      }

      // Add new source and layer
      if (clientConfig.type === 'osm' || tileSource.type === 'osm') {
        this.map.addSource('osm', {
          type: 'raster',
          tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
          tileSize: 256,
          attribution: '© OpenStreetMap contributors'
        })
        this.map.addLayer({
          id: 'osm-layer',
          type: 'raster',
          source: 'osm',
          minzoom: 0,
          maxzoom: 19
        })
      } else if (clientConfig.type === 'xyz' || tileSource.type === 'xyz') {
        const url = clientConfig.url || `/api/tiles/${layerValue}/{z}/{x}/{y}`
        this.map.addSource('tile-source', {
          type: 'raster',
          tiles: [url],
          tileSize: 256
        })
        this.map.addLayer({
          id: 'tile-layer',
          type: 'raster',
          source: 'tile-source'
        })
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
            duration: 500
          })
          return
        }
      }

      // Fallback: just zoom to state level at current center
      this.map.flyTo({
        zoom: stateLevelZoom,
        duration: 500
      })
    },
    zoomToFeature(feature) {
      if (!this.map || !feature) {
        console.warn('zoomToFeature: Missing map or feature', { map: !!this.map, feature: !!feature })
        return
      }

      console.log('zoomToFeature called with feature:', feature)

      // Get feature geometry - handle both converted MapLibre features and raw features
      let geometry = null
      
      // Try to get geometry from converted feature (has getGeometry method)
      if (feature.getGeometry && typeof feature.getGeometry === 'function') {
        const mockGeometry = feature.getGeometry()
        console.log('zoomToFeature: Got mockGeometry:', mockGeometry)
        if (mockGeometry && mockGeometry.getExtent) {
          // Use the extent from the mock geometry
          const extent = mockGeometry.getExtent()
          console.log('zoomToFeature: Got extent from mockGeometry:', extent)
          if (extent && extent.length === 4) {
            const [minLon, minLat, maxLon, maxLat] = extent
            
            // Validate all values are finite
            if (extent.every(v => isFinite(v))) {
              // Check if bounds are valid (not all zeros)
              const isNotAllZeros = !(minLon === 0 && minLat === 0 && maxLon === 0 && maxLat === 0)
              
              console.log('zoomToFeature: Extent validation', { 
                isFinite: extent.every(v => isFinite(v)),
                isNotAllZeros,
                extent: [minLon, minLat, maxLon, maxLat],
                isPoint: minLon === maxLon && minLat === maxLat
              })
              
              if (isNotAllZeros) {
                // For points (degenerate bounds), use center + zoom
                if (minLon === maxLon && minLat === maxLat) {
                  console.log('zoomToFeature: Zooming to point')
                  this.map.flyTo({
                    center: [minLon, minLat],
                    zoom: Math.max(this.map.getZoom(), 15),
                    duration: 500
                  })
                } else {
                  // For lines and polygons, use bounds
                  // MapLibre LngLatBounds takes southwest and northeast corners
                  console.log('zoomToFeature: Zooming to bounds', { minLon, minLat, maxLon, maxLat })
                  try {
                    // Create LngLatBounds: sw corner [minLon, minLat], ne corner [maxLon, maxLat]
                    const bounds = new maplibregl.LngLatBounds(
                      [minLon, minLat], // southwest corner
                      [maxLon, maxLat]  // northeast corner
                    )
                    console.log('zoomToFeature: Created LngLatBounds', bounds)
                    // Use fitBounds which is more reliable for bounds
                    this.map.fitBounds(bounds, {
                      padding: { top: 50, bottom: 50, left: 50, right: 50 },
                      duration: 500
                    })
                    console.log('zoomToFeature: Called fitBounds successfully')
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
        // Fallback: get raw geometry from converted feature
        geometry = feature.geometry
      } else {
        // Try direct geometry access
        geometry = feature.geometry || feature.get?.('geometry')
      }

      console.log('zoomToFeature: Using fallback geometry extraction', { geometry, hasGeometry: !!geometry })

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
      console.log('zoomToFeature: Extracted coordinates', { coordsCount: coords.length, geometryType: geometry.type })
      if (coords.length === 0) {
        console.warn('zoomToFeature: No coordinates found in geometry', geometry)
        return
      }

      // Calculate bounding box
      let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity
      coords.forEach((coord) => {
        const [lon, lat] = Array.isArray(coord) && coord.length >= 2 ? coord : [null, null]
        if (lon != null && lat != null && isFinite(lon) && isFinite(lat)) {
          minLon = Math.min(minLon, lon)
          minLat = Math.min(minLat, lat)
          maxLon = Math.max(maxLon, lon)
          maxLat = Math.max(maxLat, lat)
        }
      })

      console.log('zoomToFeature: Calculated bounds', { minLon, minLat, maxLon, maxLat })

      // Ensure we have valid bounds
      if (!isFinite(minLon) || !isFinite(minLat) || !isFinite(maxLon) || !isFinite(maxLat)) {
        console.warn('zoomToFeature: Invalid bounds calculated', { minLon, minLat, maxLon, maxLat, coords })
        return
      }

      // Ensure bounds are not degenerate (same point)
      if (minLon === maxLon && minLat === maxLat) {
        // For points, zoom to a reasonable zoom level
        console.log('zoomToFeature: Zooming to point (fallback)')
        this.map.flyTo({
          center: [minLon, minLat],
          zoom: Math.max(this.map.getZoom(), 15),
          duration: 500
        })
        return
      }

      // Fly to feature
      console.log('zoomToFeature: Zooming to bounds (fallback)', { minLon, minLat, maxLon, maxLat })
      try {
        // Create LngLatBounds: sw corner [minLon, minLat], ne corner [maxLon, maxLat]
        const bounds = new maplibregl.LngLatBounds(
          [minLon, minLat], // southwest corner
          [maxLon, maxLat]  // northeast corner
        )
        console.log('zoomToFeature: Created LngLatBounds (fallback)', bounds)
        // Use fitBounds which is more reliable for bounds
        this.map.fitBounds(bounds, {
          padding: { top: 50, bottom: 50, left: 50, right: 50 },
          duration: 500
        })
        console.log('zoomToFeature: Called fitBounds successfully (fallback)')
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
    },
    handleHoverPoint(point) {
      // Implementation will be added
    },
    handleHoverClear() {
      // Implementation will be added
    },
    handleClickPoint(point) {
      // Implementation will be added
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

    // Check for collection query parameter
    if (this.collectionId) {
      await this.handleCollectionFilter(this.collectionId)
    } else {
      // Initial data load
      await this.loadDataForCurrentView()
      
      // Check for featureId in URL
      await this.handleUrlFeatureId()
    }

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
  beforeUnmount() {
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
</style>

