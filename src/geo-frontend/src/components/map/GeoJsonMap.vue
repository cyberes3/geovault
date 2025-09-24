<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h1 class="text-2xl font-bold text-gray-900 mb-2">GeoJSON Map</h1>
      <p class="text-gray-600">Interactive map displaying your imported geospatial data with real-time updates.</p>
    </div>

    <!-- Map Container -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden">
      <div class="relative">
        <!-- Map -->
        <div ref="mapContainer" class="w-full h-96 md:h-[600px]"></div>

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
            <div>Features: <span class="font-medium">{{ featureCount }}</span></div>
            <div>Last update: <span class="font-medium">{{ lastUpdateTime }}</span></div>
          </div>
        </div>

        <!-- Map Controls removed - cache functionality eliminated -->
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
      // Configuration
      API_BASE_URL: '/api/data/geojson/'
    }
  },
  methods: {
    initializeMap() {
      // Create vector source and layer
      this.vectorSource = new VectorSource()

      this.vectorLayer = new VectorLayer({
        source: this.vectorSource,
        style: (feature) => this.getFeatureStyle(feature)
      })

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
          center: fromLonLat([-104.692626, 38.881215]),
          zoom: 10
        })
      })

      // Add event listeners
      this.map.getView().on('change:center', this.debouncedLoadData)
      this.map.getView().on('change:resolution', this.debouncedLoadData)

      // Event listeners removed - cache functionality eliminated
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
      // Create a key for the bounding box to track loaded areas
      const rounded = extent.map(coord => Math.round(coord * 1000) / 1000)
      const roundedZoom = Math.round(zoom) // Round to integer for consistency
      return `${rounded.join(',')}_${roundedZoom}`
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

          this.vectorSource.addFeatures(features)
          this.loadedBounds.add(bboxKey)

          this.updateFeatureCount()
          this.updateLastUpdateTime()

          console.log(`Loaded ${features.length} features for bbox: ${bboxString}`)
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

    // Cache functionality removed
  },

  async mounted() {
    this.initializeMap()

    // Initial data load
    this.loadDataForCurrentView()
  },

  beforeUnmount() {
    // Clean up
    if (this.loadTimeout) {
      clearTimeout(this.loadTimeout)
    }
  }
}
</script>

<style scoped>
/* Hide OpenLayers attribution */
:deep(.ol-attribution) {
  display: none;
}
</style>
