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
            <div v-if="userLocation" class="text-gray-600">
              📍 {{ getLocationDisplayName() }}
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
      // Configuration
      API_BASE_URL: '/api/data/geojson/',
      LOCATION_API_URL: '/api/data/location/user/',
      MAX_FEATURES: 1000, // Maximum number of features to keep on the map
      featureTimestamps: new Map() // Track when features were added
    }
  },
  methods: {
    async initializeMap() {
      // Get user location first
      await this.getUserLocation()

      // Create vector source and layer
      this.vectorSource = new VectorSource()

      this.vectorLayer = new VectorLayer({
        source: this.vectorSource,
        style: (feature) => this.getFeatureStyle(feature)
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
      // Use user location if available, otherwise default to Colorado state extent
      if (this.userLocation && this.userLocation.longitude && this.userLocation.latitude) {
        return this.getStateExtentConfig(this.userLocation)
      }
      // Default to Colorado state extent (geolocation failure fallback)
      return this.getStateExtentConfig({
        state: 'Colorado',
        state_code: 'CO',
        country: 'United States',
        country_code: 'US',
        latitude: 39.0, // Center of Colorado
        longitude: -105.5 // Center of Colorado
      })
    },

    getStateExtentConfig(location) {
      // Calculate appropriate zoom level based on location type and country
      const zoomLevel = this.calculateZoomLevel(location)
      
      return {
        center: [location.longitude, location.latitude],
        zoom: zoomLevel
      }
    },

    calculateZoomLevel(location) {
      // Base zoom levels for different administrative levels
      const baseZooms = {
        'city': 10,      // City level - close up
        'state': 6,      // State/province level - shows entire state
        'country': 4     // Country level - shows entire country
      }

      // If we have city data, we're likely in a state/province
      if (location.city) {
        return baseZooms.state
      }
      
      // If we only have country data, show the country
      if (location.country && !location.state) {
        return baseZooms.country
      }
      
      // Default to state level if we have state data
      if (location.state) {
        return baseZooms.state
      }
      
      // Fallback to moderate zoom
      return 6
    },

    getLocationDisplayName() {
      // Create a display name for the user's location
      if (!this.userLocation) return 'Unknown Location'
      
      const parts = []
      if (this.userLocation.city) parts.push(this.userLocation.city)
      if (this.userLocation.state) parts.push(this.userLocation.state)
      if (this.userLocation.country) parts.push(this.userLocation.country)
      
      return parts.length > 0 ? parts.join(', ') : this.userLocation.country || 'Unknown Location'
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
      // Create a grid-based key for the bounding box to track loaded areas
      // This prevents overlapping areas from being treated as separate
      const [minX, minY, maxX, maxY] = extent
      const roundedZoom = Math.round(zoom)

      // Create a grid system - divide the world into grid cells
      // Use a larger grid size to reduce precision issues and overlap
      const gridSize = Math.pow(2, 15 - roundedZoom) // Adjust grid size based on zoom
      const gridMinX = Math.floor(minX / gridSize) * gridSize
      const gridMinY = Math.floor(minY / gridSize) * gridSize
      const gridMaxX = Math.ceil(maxX / gridSize) * gridSize
      const gridMaxY = Math.ceil(maxY / gridSize) * gridSize

      return `${gridMinX},${gridMinY},${gridMaxX},${gridMaxY}_${roundedZoom}`
    },

    getBoundingBoxString(extent) {
      // Convert Web Mercator extent to geographic coordinates (EPSG:4326)
      const [minX, minY, maxX, maxY] = extent

      // Use OpenLayers' built-in coordinate transformation
      const minLonLat = toLonLat([minX, minY])
      const maxLonLat = toLonLat([maxX, maxY])

      return `${minLonLat[0]},${minLonLat[1]},${maxLonLat[0]},${maxLonLat[1]}`
    },

    async loadDataForCurrentView() {
      if (this.isLoading) return

      const view = this.map.getView()
      const extent = view.calculateExtent()
      const zoom = view.getZoom()

      // Check if we already loaded data for this area
      const bboxKey = this.getBoundingBoxKey(extent, zoom)
      if (this.loadedBounds.has(bboxKey)) {
        return
      }

      this.isLoading = true

      try {
        const bboxString = this.getBoundingBoxString(extent)
        const roundedZoom = Math.round(zoom) // Round to integer for API compatibility
        const url = `${this.API_BASE_URL}?bbox=${bboxString}&zoom=${roundedZoom}`

        const response = await fetch(url)
        const data = await response.json()

        if (data.success && data.data.features) {
          // Show warning if features were limited by configuration
          if (data.warning) {
            console.warn(data.warning)
          }

          // Add new features to the vector source
          const features = new GeoJSON().readFeatures(data.data, {
            featureProjection: 'EPSG:3857',
            dataProjection: 'EPSG:4326'
          })

          // Manually preserve properties from the original GeoJSON data
          features.forEach((feature, index) => {
            const originalFeature = data.data.features[index]
            if (originalFeature && originalFeature.properties) {
              // Set the properties explicitly
              feature.set('properties', originalFeature.properties)

              // Also set individual properties for easier access
              Object.keys(originalFeature.properties).forEach(key => {
                feature.set(key, originalFeature.properties[key])
              })
            }

            const props = feature.getProperties()
          })

          // Filter out features that already exist in the vector source
          const existingFeatures = this.vectorSource.getFeatures()
          const newFeatures = features.filter(newFeature => {
            const newGeometry = newFeature.getGeometry()
            if (!newGeometry) return true // Keep features without geometry

            // Check if a feature with the same geometry already exists
            return !existingFeatures.some(existingFeature => {
              const existingGeometry = existingFeature.getGeometry()
              if (!existingGeometry) return false

              // Compare geometries using their extent and type
              const newExtent = newGeometry.getExtent()
              const existingExtent = existingGeometry.getExtent()

              // Check if extents are the same (within tolerance)
              const tolerance = 0.001
              const extentsMatch = Math.abs(newExtent[0] - existingExtent[0]) < tolerance &&
                  Math.abs(newExtent[1] - existingExtent[1]) < tolerance &&
                  Math.abs(newExtent[2] - existingExtent[2]) < tolerance &&
                  Math.abs(newExtent[3] - existingExtent[3]) < tolerance

              // Also check geometry type
              const typesMatch = newGeometry.getType() === existingGeometry.getType()

              return extentsMatch && typesMatch
            })
          })

          if (newFeatures.length > 0) {
            // Add timestamps to new features before adding them to the map
            newFeatures.forEach(feature => {
              this.addFeatureTimestamp(feature)
            })

            this.vectorSource.addFeatures(newFeatures)
            console.log(`Added ${newFeatures.length} new features (filtered ${features.length - newFeatures.length} duplicates)`)

            // Enforce feature limit after adding new features
            this.enforceFeatureLimit()
          } else {
            console.log(`No new features to add (all ${features.length} were duplicates)`)
          }

          this.loadedBounds.add(bboxKey)

          this.updateFeatureCount()
          this.updateLastUpdateTime()

          console.log(`Loaded ${features.length} features for bbox: ${bboxString} (zoom: ${roundedZoom})`)
          if (data.total_features_in_bbox && data.total_features_in_bbox > features.length) {
            console.log(`Note: ${data.total_features_in_bbox - features.length} additional features not shown due to limit (${data.max_features_limit})`)
          }
        } else {
          console.error('Error loading data:', data.error)
        }
      } catch (error) {
        console.error('Error fetching data:', error)
      } finally {
        this.isLoading = false
      }
    },

    debouncedLoadData() {
      clearTimeout(this.loadTimeout)
      this.loadTimeout = setTimeout(this.loadDataForCurrentView, 500)
    },

    updateFeatureCount() {
      this.featureCount = this.vectorSource.getFeatures().length
    },

    updateLastUpdateTime() {
      this.lastUpdateTime = new Date().toLocaleTimeString()
    },

    enforceFeatureLimit() {
      const features = this.vectorSource.getFeatures()
      if (features.length <= this.MAX_FEATURES) {
        return
      }

      // Sort features by timestamp (oldest first)
      const featuresWithTimestamps = features.map(feature => ({
        feature,
        timestamp: this.featureTimestamps.get(feature) || 0
      })).sort((a, b) => a.timestamp - b.timestamp)

      // Calculate how many features to remove
      const featuresToRemove = features.length - this.MAX_FEATURES

      // Remove oldest features
      for (let i = 0; i < featuresToRemove; i++) {
        const {feature} = featuresWithTimestamps[i]
        this.vectorSource.removeFeature(feature)
        this.featureTimestamps.delete(feature)
      }

      console.log(`Removed ${featuresToRemove} oldest features to maintain limit of ${this.MAX_FEATURES}`)
      this.updateFeatureCount()
    },

    addFeatureTimestamp(feature) {
      this.featureTimestamps.set(feature, Date.now())
    },

    clearAllFeatures() {
      // Clear all features and their timestamps
      this.vectorSource.clear()
      this.featureTimestamps.clear()
      this.loadedBounds.clear()
      this.updateFeatureCount()
      console.log('Cleared all features from the map')
    },

    // Cache functionality removed
  },

  async mounted() {
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
    // Clear feature timestamps
    this.featureTimestamps.clear()
  }
}
</script>

<style scoped>
/* Hide OpenLayers attribution */
:deep(.ol-attribution) {
  display: none;
}
</style>
