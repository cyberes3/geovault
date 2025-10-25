<template>
  <div class="w-full h-full">
    <!-- Map Container -->
    <div class="w-full h-full">
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

        <!-- Feature Stats -->
        <div class="absolute bottom-4 left-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10 text-xs">
          <div class="space-y-1">
            <div>Features: <span class="font-medium">{{ featureCount }}</span> / <span class="font-medium">{{ MAX_FEATURES }}</span></div>
            <div v-if="simplificationEnabled" class="text-blue-600">
              Simplification: Tier {{ getCurrentSimplificationTier() }} ({{ getSimplificationMultiplierForTier(getCurrentSimplificationTier()) }}x)
            </div>
            <div v-if="userLocation" class="text-gray-600">
              📍 {{ getLocationDisplayName() }}
            </div>
            <div class="flex items-center space-x-2">
              <label class="flex items-center space-x-1 cursor-pointer">
                <input
                    v-model="simplificationEnabled"
                    class="rounded"
                    type="checkbox"
                    @change="onSimplificationToggle"
                >
                <span class="text-xs">Simplify geometry</span>
              </label>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {Map, View} from 'ol'
import {OSM} from 'ol/source'
import {Tile as TileLayer, Vector as VectorLayer} from 'ol/layer'
import {Vector as VectorSource} from 'ol/source'
import {Style, Fill, Stroke, Circle} from 'ol/style'
import {GeoJSON} from 'ol/format'
import {fromLonLat, toLonLat} from 'ol/proj'
import {authMixin} from '@/assets/js/authMixin.js'
import {SimplificationTierManager} from '@/utils/map/SimplificationTierManager'
import {MapUtils} from '@/utils/map/MapUtils'
import {GeometrySimplification} from "@/utils/map/GeometrySimplification";

export default {
  name: 'GeoJsonMap',
  mixins: [authMixin],
  data() {
    return {
      map: null,
      vectorSource: null,
      vectorLayer: null,
      isLoading: false,
      loadedBounds: new Set(),
      lastUpdateTime: null,
      featureCount: 0,
      loadTimeout: null,
      userLocation: null,
      currentAbortController: null, // AbortController for current request
      // Configuration
      API_BASE_URL: '/api/data/geojson/',
      LOCATION_API_URL: '/api/data/location/user/',
      MAX_FEATURES: 5000, // Maximum number of features to keep on the map
      featureTimestamps: {}, // Use plain object instead of Map
      featureIdCounter: 0, // Counter to generate unique IDs for features
      // Geometry simplification settings
      simplificationEnabled: true,
      simplificationOptions: {
        baseTolerance: 0.001, // Base tolerance for zoom level 10
        minZoom: 1,
        maxZoom: 20,
        enableSimplification: true
      },
      // Performance monitoring
      lastSimplificationStats: null,
      // Performance thresholds
      performanceThresholds: {
        maxProcessingTime: 100, // ms
        maxFeaturesForFullDetail: 1000,
        minZoomForAggressiveSimplification: 8
      },
      // Feature count scaling thresholds (5 tiers based on MAX_FEATURES)
      featureCountThresholds: {
        tier1: 0,      // 0-20% of max features - minimal simplification
        tier2: 0,      // 20-40% of max features - light simplification
        tier3: 0,      // 40-60% of max features - moderate simplification
        tier4: 0,      // 60-80% of max features - aggressive simplification
        tier5: 0       // 80-100% of max features - maximum simplification
      },
      // Dynamic simplification
      originalFeatureData: new Map(), // Store original GeoJSON data for re-simplification
      currentZoom: null,
      immediateSimplificationTimeout: null, // Timeout for immediate simplification
      immediateSimplificationPerformed: false, // Track if immediate simplification was done
      // Performance optimization settings
      simplificationSettings: {
        minImmediateZoomChangeThreshold: 0.5 // Minimum zoom change for immediate simplification
      },
      // Utility instances
      tierManager: null
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

    initializeFeatureCountThresholds() {
      // Initialize the 5-tier thresholds based on MAX_FEATURES
      const maxFeatures = this.MAX_FEATURES
      this.featureCountThresholds = {
        tier1: Math.floor(maxFeatures * 0.2),  // 0-20% of max features
        tier2: Math.floor(maxFeatures * 0.4),  // 20-40% of max features
        tier3: Math.floor(maxFeatures * 0.6),  // 40-60% of max features
        tier4: Math.floor(maxFeatures * 0.8),  // 60-80% of max features
        tier5: maxFeatures                     // 80-100% of max features
      }
      console.log('Feature count thresholds initialized:', this.featureCountThresholds)
    },

    getCurrentSimplificationTier() {
      if (!this.vectorSource || !this.tierManager) {
        return 1 // Default tier during initialization
      }

      // Count only polygon and line features (not points)
      const allFeatures = this.vectorSource.getFeatures()
      const featureCount = MapUtils.countPolyLineFeatures(allFeatures)

      return this.tierManager.getCurrentTier(featureCount)
    },

    getSimplificationMultiplierForTier(tier) {
      if (!this.tierManager) {
        return 1.0 // Default multiplier during initialization
      }

      return this.tierManager.getSimplificationMultiplier(tier)
    },

    async initializeMap() {
      // Get user location first
      await this.getUserLocation()

      // Create vector source and layer
      this.vectorSource = new VectorSource()

      this.vectorLayer = new VectorLayer({
        source: this.vectorSource,
        style: (feature) => this.getFeatureStyle(feature),
        // Performance optimizations for complex polygon rendering
        renderBuffer: 100,  // Only render features within 100px of viewport
        updateWhileAnimating: true,  // Continue updating during animations
        updateWhileInteracting: true,  // Continue updating during interactions
        declutter: true  // Disable label decluttering (enable if you have overlapping labels)
      })

      // Determine initial map center and zoom based on user location
      const mapConfig = this.getInitialMapConfig()

      // Create map
      this.map = new Map({
        target: this.$refs.mapContainer,
        layers: [
          new TileLayer({
            source: new OSM()
          }),
          this.vectorLayer
        ],
        view: new View({
          center: fromLonLat(mapConfig.center),
          zoom: mapConfig.zoom
        })
      })

      // Add event listeners
      this.map.getView().on('change:center', this.debouncedLoadData)
      this.map.getView().on('change:resolution', this.debouncedLoadData)

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
            
            // Use debounced immediate simplification to wait for zoom completion
            this.debouncedImmediateReSimplify(newZoom)
            // Don't call secondary re-simplification here - it will be called when new data loads
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

    getFeatureStyle(feature) {
      const properties = feature.get('properties') || {}
      const geometryType = feature.getGeometry().getType()
      const name = properties.name || 'Unknown'

      // Helper function to convert hex color to CSS color string
      const hexToColor = (hexColor, defaultColor = '#ff0000') => {
        if (!hexColor || typeof hexColor !== 'string') return defaultColor
        return hexColor
      }

      if (geometryType === 'Point') {
        // Points use marker-color or default red
        const fillColor = hexToColor(properties['marker-color'], '#ff0000')

        return new Style({
          image: new Circle({
            radius: 6,
            fill: new Fill({
              color: fillColor
            }),
            stroke: new Stroke({
              color: fillColor, // Use same color for stroke
              width: 2
            })
          })
        })
      } else if (geometryType === 'LineString') {
        // Lines use stroke and stroke-width
        const strokeColor = hexToColor(properties.stroke, '#ff0000')
        return new Style({
          stroke: new Stroke({
            color: strokeColor,
            width: properties['stroke-width'] || 2
          })
        })
      } else if (geometryType === 'Polygon') {
        // Polygons use stroke, stroke-width, fill, and fill-opacity
        const strokeColor = hexToColor(properties.stroke, '#ff0000')
        let fillColor = hexToColor(properties.fill, '#ff0000')

        // Apply fill-opacity if specified
        if (properties['fill-opacity'] !== undefined) {
          // Convert hex to RGB and apply opacity
          const hex = fillColor.replace('#', '')
          const r = parseInt(hex.substr(0, 2), 16)
          const g = parseInt(hex.substr(2, 2), 16)
          const b = parseInt(hex.substr(4, 2), 16)
          fillColor = `rgba(${r}, ${g}, ${b}, ${properties['fill-opacity']})`
        }

        return new Style({
          stroke: new Stroke({
            color: strokeColor,
            width: properties['stroke-width'] || 2
          }),
          fill: new Fill({
            color: fillColor
          })
        })
      }

      // Default style for unknown geometry types
      return new Style({
        stroke: new Stroke({
          color: '#ff0000',
          width: 2
        }),
        fill: new Fill({
          color: 'rgba(255, 0, 0, 0.3)'
        })
      })
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
          // Show warning if features were limited by configuration
          if (data.warning) {
            console.warn(data.warning)
          }

          // Apply geometry simplification if enabled
          let processedData = data.data
          if (this.simplificationEnabled) {
            const startTime = performance.now()

            // Calculate current simplification tier and multiplier
            const currentTier = this.getCurrentSimplificationTier()
            let multiplier = this.getSimplificationMultiplierForTier(currentTier)

            // For large feature sets, be more aggressive with initial simplification
            if (data.data.features.length > 500) {
              multiplier = Math.max(multiplier, 2.0) // At least 2x simplification for large sets
              console.log(`Large feature set detected (${data.data.features.length} features), using aggressive simplification: ${multiplier}x`)
            }

            processedData = GeometrySimplification.simplifyFeatureCollection(data.data, roundedZoom, this.simplificationOptions, multiplier)
            const endTime = performance.now()

            // Log simplification statistics for debugging
            if (data.data.features.length > 0 && processedData.features.length > 0) {
              const stats = GeometrySimplification.getSimplificationStats(data.data.features[0].geometry, processedData.features[0].geometry)
              if (stats) {
                this.lastSimplificationStats = {
                  ...stats,
                  processingTime: endTime - startTime,
                  featureCount: data.data.features.length,
                  zoom: roundedZoom,
                  tier: currentTier,
                  multiplier: multiplier
                }
                console.log(`Geometry simplification (Tier ${currentTier}, ${multiplier}x): ${stats.reductionPercentage}% reduction, ${stats.compressionRatio}x compression, ${(endTime - startTime).toFixed(2)}ms for ${data.data.features.length} features at zoom ${roundedZoom}`)
              }
            }

            // Adjust simplification tolerance based on performance
            this.adjustSimplificationTolerance()
          }

          // Add new features to the vector source
          const features = new GeoJSON().readFeatures(processedData, {
            featureProjection: 'EPSG:3857',
            dataProjection: 'EPSG:4326'
          })

          // Manually preserve properties from the original GeoJSON data
          features.forEach((feature, index) => {
            const originalFeature = data.data.features[index]
            const processedFeature = processedData.features[index]

            if (originalFeature && originalFeature.properties) {
              // Set the properties explicitly
              feature.set('properties', originalFeature.properties)

              // Also set individual properties for easier access
              Object.keys(originalFeature.properties).forEach(key => {
                feature.set(key, originalFeature.properties[key])
              })
            }

            // Set the geojson_hash for efficient duplicate detection
            if (originalFeature && originalFeature.geojson_hash) {
              feature.set('geojson_hash', originalFeature.geojson_hash)
            }

            // Store original GeoJSON data for re-simplification
            if (this.simplificationEnabled && originalFeature) {
              const featureId = this.getFeatureId(feature)
              this.originalFeatureData.set(featureId, originalFeature)
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

          this.updateFeatureCount()
          this.updateLastUpdateTime()

          // Update current zoom for dynamic re-simplification
          this.currentZoom = roundedZoom

          // Reset immediate simplification flag since we have new data
          this.immediateSimplificationPerformed = false

          // Check if we need to re-simplify existing features due to feature count change
          this.checkForFeatureCountBasedReSimplification()

          // New features are already simplified during initial processing, no need for secondary re-simplification

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
        // Clean up original feature data
        this.originalFeatureData.delete(featureId)
      }

      console.log(`Removed ${featuresToRemove} oldest features to maintain limit of ${this.MAX_FEATURES}`)
      this.updateFeatureCount()
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
      this.originalFeatureData.clear()
      this.loadedBounds.clear()
      this.updateFeatureCount()
      console.log('Cleared all features from the map')
    },

    onSimplificationToggle() {
      // When simplification is toggled, clear the map and reload data
      // This ensures all features are re-processed with the new simplification setting
      console.log(`Geometry simplification ${this.simplificationEnabled ? 'enabled' : 'disabled'}`)
      this.clearAllFeatures()
      this.loadDataForCurrentView()
    },

    adjustSimplificationTolerance() {
      // Dynamically adjust simplification tolerance based on performance
      if (!this.lastSimplificationStats) return

      const {processingTime, featureCount, zoom} = this.lastSimplificationStats
      const {maxProcessingTime, maxFeaturesForFullDetail, minZoomForAggressiveSimplification} = this.performanceThresholds

      // If processing is taking too long or we have many features, increase tolerance
      if (processingTime > maxProcessingTime || featureCount > maxFeaturesForFullDetail) {
        this.simplificationOptions.baseTolerance = Math.min(
            this.simplificationOptions.baseTolerance * 1.5,
            0.01 // Max tolerance
        )
        console.log(`Increased simplification tolerance to ${this.simplificationOptions.baseTolerance} due to performance (${processingTime.toFixed(1)}ms, ${featureCount} features)`)
      }
      // If processing is very fast and we have few features, decrease tolerance for better quality
      else if (processingTime < 10 && featureCount < 100 && zoom >= minZoomForAggressiveSimplification) {
        this.simplificationOptions.baseTolerance = Math.max(
            this.simplificationOptions.baseTolerance * 0.8,
            0.0001 // Min tolerance
        )
        console.log(`Decreased simplification tolerance to ${this.simplificationOptions.baseTolerance} for better quality`)
      }
    },

    immediateReSimplifyCachedData(newZoom) {
      // Immediately re-simplify cached data without waiting for debounce or API calls
      if (!this.simplificationEnabled || this.originalFeatureData.size === 0 || !this.vectorSource) {
        return
      }

      // Check if zoom change is significant enough
      const {minImmediateZoomChangeThreshold} = this.simplificationSettings
      if (this.currentZoom !== null && Math.abs(newZoom - this.currentZoom) < minImmediateZoomChangeThreshold) {
        return
      }

      // Get all current features
      const currentFeatures = this.vectorSource.getFeatures()
      if (currentFeatures.length === 0) {
        return
      }

      // Calculate current simplification tier and multiplier
      const currentTier = this.getCurrentSimplificationTier()
      const multiplier = this.getSimplificationMultiplierForTier(currentTier)

      console.log(`Immediate re-simplification for zoom change: ${this.currentZoom} → ${newZoom} (Tier ${currentTier}, ${multiplier}x)`)

      const startTime = performance.now()
      let totalOriginalCoords = 0
      let totalSimplifiedCoords = 0
      let processedFeatures = 0

      // Process features synchronously for immediate response
      currentFeatures.forEach(feature => {
        const featureId = this.getFeatureId(feature)
        const originalGeoJSON = this.originalFeatureData.get(featureId)

        if (originalGeoJSON) {
          // Re-simplify the original geometry with current tier multiplier
          const simplifiedFeature = GeometrySimplification.simplifyFeature(originalGeoJSON, newZoom, this.simplificationOptions, multiplier)

          // Update the feature's geometry
          const newGeometry = new GeoJSON().readGeometry(simplifiedFeature.geometry, {
            featureProjection: 'EPSG:3857',
            dataProjection: 'EPSG:4326'
          })

          feature.setGeometry(newGeometry)

          // Track statistics
          const originalCoords = GeometrySimplification.countCoordinates(originalGeoJSON.geometry)
          const simplifiedCoords = GeometrySimplification.countCoordinates(simplifiedFeature.geometry)
          totalOriginalCoords += originalCoords
          totalSimplifiedCoords += simplifiedCoords
          processedFeatures++
        }
      })

      const endTime = performance.now()
      const reductionPercentage = totalOriginalCoords > 0 ?
          ((totalOriginalCoords - totalSimplifiedCoords) / totalOriginalCoords * 100).toFixed(2) : 0

      console.log(`Immediate re-simplified ${processedFeatures} features (Tier ${currentTier}): ${totalOriginalCoords} → ${totalSimplifiedCoords} coordinates (${reductionPercentage}% reduction) in ${(endTime - startTime).toFixed(2)}ms`)

      this.currentZoom = newZoom
      this.immediateSimplificationPerformed = true
    },


    debouncedImmediateReSimplify(zoom) {
      // Debounce immediate re-simplification to wait for zoom completion
      if (this.immediateSimplificationTimeout) {
        clearTimeout(this.immediateSimplificationTimeout)
      }

      this.immediateSimplificationTimeout = setTimeout(() => {
        this.immediateReSimplifyCachedData(zoom)
      }, 300) // 300ms debounce for immediate simplification
    },


    checkForFeatureCountBasedReSimplification() {
      // Check if the feature count has crossed a tier threshold and re-simplify if needed
      if (!this.simplificationEnabled || this.originalFeatureData.size === 0 || !this.vectorSource) {
        return
      }

      const currentTier = this.getCurrentSimplificationTier()
      const currentMultiplier = this.getSimplificationMultiplierForTier(currentTier)

      // Check if we have a previous tier stored
      if (this.lastSimplificationStats && this.lastSimplificationStats.tier) {
        const previousTier = this.lastSimplificationStats.tier
        const previousMultiplier = this.lastSimplificationStats.multiplier

        // If tier changed, re-simplify all features
        if (currentTier !== previousTier) {
          console.log(`Feature count tier changed: ${previousTier} → ${currentTier} (${previousMultiplier}x → ${currentMultiplier}x)`)
          this.reSimplifyExistingFeatures(this.currentZoom)
        }
      }
    },

    // Cache functionality removed
  },

  async mounted() {
    // Initialize featureTimestamps as empty object
    this.featureTimestamps = {}

    // Initialize feature count thresholds
    this.initializeFeatureCountThresholds()
    this.tierManager = new SimplificationTierManager(this.MAX_FEATURES)

    // Wait for map to be fully initialized before loading data
    await this.initializeMap()

    // Initial data load - now the map is ready
    this.loadDataForCurrentView()
  },

  beforeUnmount() {
    // Clean up
    if (this.loadTimeout) {
      clearTimeout(this.loadTimeout)
    }

    if (this.immediateSimplificationTimeout) {
      clearTimeout(this.immediateSimplificationTimeout)
    }

    // Cancel any pending API request
    if (this.currentAbortController) {
      this.currentAbortController.abort()
      console.log('Cancelled API request on component unmount')
    }

    // Clear feature timestamps and original data
    this.featureTimestamps = {}
    this.originalFeatureData.clear()
  }
}
</script>

<style scoped>
/* Hide OpenLayers attribution */
:deep(.ol-attribution) {
  display: none;
}
</style>
