<template>
  <BaseModal
    :is-open="isOpen"
    :title="`Feature Map View${selectedFeatureName ? ' - ' + selectedFeatureName : ''}`"
    max-width="6xl"
    @close="closeDialog"
  >
    <!-- Map Container -->
    <div class="flex-1 bg-white min-h-0 flex flex-col overflow-hidden relative h-full">
      <!-- Map -->
      <div ref="mapContainer" class="flex-1 w-full border-0"></div>

      <!-- Loading Indicator -->
      <div v-show="isLoading" class="absolute top-4 right-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10">
        <Loader size="sm" layout="inline" message="Loading map..." />
      </div>

      <!-- Feature Info -->
      <div class="absolute bottom-4 left-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10 text-xs">
        <div class="space-y-1">
          <div>Total Features: <span class="font-medium">{{ featureCount }}</span></div>
          <div>Selected: <span class="font-medium">{{ selectedFeatureName }}</span></div>
        </div>
      </div>
    </div>

    <template #footer>
      <button
        @click="closeDialog"
        class="inline-flex items-center px-4 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
        title="Close Dialog"
      >
        Close
      </button>
    </template>
  </BaseModal>
</template>

<script>
import {Map, View} from 'ol'
import {Vector as VectorLayer} from 'ol/layer'
import {openLayersBasemap} from '@/utils/map/openlayers/index.js'
import {Vector as VectorSource} from 'ol/source'
import {Style, Fill, Stroke, Circle, Text} from 'ol/style'
import {GeoJSON} from 'ol/format'
import {fromLonLat} from 'ol/proj'
import {getCenter} from 'ol/extent'
import BaseModal from '@/components/parts/BaseModal.vue'
import Loader from '@/components/parts/Loader.vue'

export default {
  name: 'FeatureMapDialog',
  components: {
    BaseModal,
    Loader
  },
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    features: {
      type: Array,
      default: () => []
    },
    selectedFeatureIndex: {
      type: Number,
      default: 0
    },
    filename: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      map: null,
      vectorSource: null,
      vectorLayer: null,
      labelLayer: null,
      isLoading: false,
      featureCount: 0,
      selectedFeature: null
    }
  },
  computed: {
    selectedFeatureName() {
      if (this.selectedFeature) {
        // Try different ways to get the name
        const properties = this.selectedFeature.get('properties') || this.selectedFeature.properties || {}
        return properties.name || ''
      }
      return ''
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        this.$nextTick(async () => {
          this.isLoading = true
          await this.initializeMap()
          this.loadFeatures()
        })
      } else {
        this.cleanup()
      }
    },
    features: {
      handler() {
        if (this.isOpen && this.map) {
          this.loadFeatures()
        }
      },
      deep: true
    },
    selectedFeatureIndex() {
      if (this.isOpen && this.map && this.features.length > 0) {
        // Update selected feature and refresh highlighting
        const features = this.vectorSource.getFeatures()
        if (this.selectedFeatureIndex >= 0 && this.selectedFeatureIndex < features.length) {
          this.selectedFeature = features[this.selectedFeatureIndex]
          this.vectorLayer.changed() // Force style update
          this.labelLayer.changed() // Force label style update
          // Zoom to the selected feature
          this.zoomToSelectedFeature()
        }
      }
    },
    $route() {
      // Close dialog when route changes
      if (this.isOpen) {
        this.closeDialog()
      }
    }
  },
  methods: {
    async initializeMap() {
      if (this.map) {
        this.cleanup()
      }

      // Create vector source and layers
      this.vectorSource = new VectorSource()

      // Layer for features (geometry only, no decluttering)
      this.vectorLayer = new VectorLayer({
        source: this.vectorSource,
        style: (feature) => this.getFeatureStyle(feature) // No text labels
      })

      // Layer for labels only (with decluttering)
      this.labelLayer = new VectorLayer({
        source: this.vectorSource,
        style: (feature) => this.getLabelStyle(feature),
        declutter: true
      })

      const basemapLayer = await openLayersBasemap.createTileLayer()

      // Create map
      this.map = new Map({
        target: this.$refs.mapContainer,
        layers: [
          basemapLayer,
          this.vectorLayer,
          this.labelLayer
        ],
        view: new View({
          center: fromLonLat([-104.692626, 38.881215]), // Default to Denver, CO
          zoom: 10
        })
      })
    },

    getFeatureStyle(feature) {
      const properties = feature.get('properties') || feature.properties || {}
      const geometryType = feature.getGeometry().getType()
      const isSelected = feature === this.selectedFeature

      // Helper function to convert hex color to CSS color string
      const hexToColor = (hexColor, defaultColor = '#ff0000') => {
        if (!hexColor || typeof hexColor !== 'string') return defaultColor
        return hexColor
      }

      // Highlight colors for selected feature
      const highlightColor = '#ffff00' // Bright yellow
      const highlightStrokeColor = '#000000' // Black border for contrast

      if (geometryType === 'Point') {
        return new Style({
          image: new Circle({
            radius: isSelected ? 8 : 5,
            fill: new Fill({
              color: isSelected ? highlightColor : hexToColor(properties['marker-color'], '#ff0000')
            }),
            stroke: new Stroke({
              color: isSelected ? highlightStrokeColor : 'transparent',
              width: isSelected ? 2 : 0
            })
          })
        })
      } else if (geometryType === 'LineString') {
        if (isSelected) {
          return [
            new Style({
              stroke: new Stroke({
                color: highlightStrokeColor,
                width: 10
              })
            }),
            new Style({
              stroke: new Stroke({
                color: highlightColor,
                width: 6
              })
            })
          ]
        } else {
          const strokeColor = hexToColor(properties.stroke, '#ff0000')
          const strokeWidth = properties['stroke-width'] || 3
          return new Style({
            stroke: new Stroke({
              color: strokeColor,
              width: strokeWidth
            })
          })
        }
      } else if (geometryType === 'MultiPoint') {
        const fillColor = isSelected ? highlightColor : hexToColor(properties['marker-color'], '#ff0000')
        const strokeColor = isSelected ? highlightStrokeColor : '#000000'
        const strokeWidth = isSelected ? 3 : 2
        return new Style({
          image: new Circle({
            radius: isSelected ? 12 : 8,
            fill: new Fill({
              color: fillColor
            }),
            stroke: new Stroke({
              color: strokeColor,
              width: strokeWidth
            })
          })
        })
      } else if (geometryType === 'MultiLineString') {
        if (isSelected) {
          return [
            new Style({
              stroke: new Stroke({
                color: highlightStrokeColor,
                width: 10
              })
            }),
            new Style({
              stroke: new Stroke({
                color: highlightColor,
                width: 6
              })
            })
          ]
        } else {
          const strokeColor = hexToColor(properties.stroke, '#ff0000')
          const strokeWidth = properties['stroke-width'] || 3
          return [
            new Style({
              stroke: new Stroke({
                color: '#000000',
                width: strokeWidth + 2
              })
            }),
            new Style({
              stroke: new Stroke({
                color: strokeColor,
                width: strokeWidth
              })
            })
          ]
        }
      } else if (geometryType === 'MultiPolygon') {
        const strokeColor = isSelected ? highlightStrokeColor : hexToColor(properties.stroke, '#ff0000')
        let fillColor = isSelected ? highlightColor : hexToColor(properties.fill, '#ff0000')
        const strokeWidth = isSelected ? 4 : (properties['stroke-width'] || 2)

        if (!isSelected && properties['fill-opacity'] !== undefined) {
          const hex = fillColor.replace('#', '')
          const r = parseInt(hex.substr(0, 2), 16)
          const g = parseInt(hex.substr(2, 2), 16)
          const b = parseInt(hex.substr(4, 2), 16)
          fillColor = `rgba(${r}, ${g}, ${b}, ${properties['fill-opacity']})`
        }

        return new Style({
          stroke: new Stroke({
            color: strokeColor,
            width: strokeWidth
          }),
          fill: new Fill({
            color: fillColor
          })
        })
      } else if (geometryType === 'Polygon') {
        const strokeColor = isSelected ? highlightStrokeColor : hexToColor(properties.stroke, '#ff0000')
        let fillColor = isSelected ? highlightColor : hexToColor(properties.fill, '#ff0000')
        const strokeWidth = isSelected ? 4 : (properties['stroke-width'] || 2)

        if (!isSelected && properties['fill-opacity'] !== undefined) {
          const hex = fillColor.replace('#', '')
          const r = parseInt(hex.substr(0, 2), 16)
          const g = parseInt(hex.substr(2, 2), 16)
          const b = parseInt(hex.substr(4, 2), 16)
          fillColor = `rgba(${r}, ${g}, ${b}, ${properties['fill-opacity']})`
        }

        return new Style({
          stroke: new Stroke({
            color: strokeColor,
            width: strokeWidth
          }),
          fill: new Fill({
            color: fillColor
          })
        })
      }

      // Default style for unknown geometry types
      return new Style({
        stroke: new Stroke({
          color: isSelected ? highlightColor : '#ff0000',
          width: isSelected ? 4 : 2
        }),
        fill: new Fill({
          color: isSelected ? highlightColor : 'rgba(255, 0, 0, 0.3)'
        })
      })
    },

    getLabelStyle(feature) {
      const properties = feature.get('properties') || feature.properties || {}
      const geometryType = feature.getGeometry().getType()
      const name = properties.name || 'Unnamed Feature'
      const isSelected = feature === this.selectedFeature

      // Only return label style if feature has a name
      if (!name || name === 'Unnamed Feature') {
        return null
      }

      if (geometryType === 'Point' || geometryType === 'MultiPoint') {
        return new Style({
          text: new Text({
            text: name,
            font: isSelected ? 'bold 14px Arial' : '12px Arial',
            fill: new Fill({
              color: '#000000'
            }),
            stroke: new Stroke({
              color: '#ffffff',
              width: isSelected ? 4 : 3
            }),
            offsetY: isSelected ? -20 : -15
          })
        })
      } else if (geometryType === 'LineString' || geometryType === 'MultiLineString') {
        return new Style({
          text: new Text({
            text: name,
            font: isSelected ? 'bold 14px Arial' : '12px Arial',
            fill: new Fill({
              color: '#000000'
            }),
            stroke: new Stroke({
              color: '#ffffff',
              width: isSelected ? 4 : 3
            }),
            offsetY: isSelected ? -15 : -10
          })
        })
      } else if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
        return new Style({
          text: new Text({
            text: name,
            font: isSelected ? 'bold 14px Arial' : '12px Arial',
            fill: new Fill({
              color: '#000000'
            }),
            stroke: new Stroke({
              color: '#ffffff',
              width: isSelected ? 4 : 3
            }),
            offsetY: isSelected ? -15 : -10
          })
        })
      }

      // Default label style
      return new Style({
        text: new Text({
          text: name,
          font: isSelected ? 'bold 14px Arial' : '12px Arial',
          fill: new Fill({
            color: '#000000'
          }),
          stroke: new Stroke({
            color: '#ffffff',
            width: isSelected ? 4 : 3
          }),
          offsetY: isSelected ? -15 : -10
        })
      })
    },

    loadFeatures() {
      if (!this.map || !this.features || this.features.length === 0) {
        this.featureCount = 0
        this.isLoading = false
        return
      }

      this.isLoading = true

      try {
        // Clear existing features
        this.vectorSource.clear()

        // Convert features to GeoJSON format
        const geoJsonFeatures = this.features.map(feature => {
          return {
            type: 'Feature',
            geometry: feature.geometry,
            properties: feature.properties || {}
          }
        })

        const geoJsonData = {
          type: 'FeatureCollection',
          features: geoJsonFeatures
        }

        // Add features to the map
        const features = new GeoJSON().readFeatures(geoJsonData, {
          featureProjection: 'EPSG:3857',
          dataProjection: 'EPSG:4326'
        })

        // Preserve properties from the original data
        features.forEach((feature, index) => {
          const originalFeature = geoJsonFeatures[index]
          if (originalFeature && originalFeature.properties) {
            feature.set('properties', originalFeature.properties)
            Object.keys(originalFeature.properties).forEach(key => {
              feature.set(key, originalFeature.properties[key])
            })
          }
        })

        this.vectorSource.addFeatures(features)
        this.featureCount = features.length

        // Set the selected feature
        if (this.selectedFeatureIndex >= 0 && this.selectedFeatureIndex < features.length) {
          this.selectedFeature = features[this.selectedFeatureIndex]
          // Zoom to the selected feature
          this.zoomToSelectedFeature()
        } else {
          // Fit map to show all features if no feature is selected
          this.fitMapToAllFeatures()
        }

      } catch (error) {
        console.error('Error loading features for feature map view:', error)
      } finally {
        this.isLoading = false
      }
    },

    zoomToSelectedFeature() {
      if (!this.map || !this.selectedFeature) return

      try {
        const geometry = this.selectedFeature.getGeometry()
        if (!geometry) return

        // Get the extent of the selected feature
        const extent = geometry.getExtent()
        if (!extent || extent.length !== 4) return

        // Check if this is a point feature (very small extent)
        const width = extent[2] - extent[0]
        const height = extent[3] - extent[1]
        const isPoint = width < 100 && height < 100 // Less than ~100 meters

        let fitExtent = extent

        // For point features, add a buffer to ensure visibility
        if (isPoint) {
          const center = getCenter(extent)
          // Add a 1km buffer (1000 meters) around the point
          const bufferDistance = 1000
          fitExtent = [
            center[0] - bufferDistance,
            center[1] - bufferDistance,
            center[0] + bufferDistance,
            center[1] + bufferDistance
          ]
        }

        // Fit the map to show the selected feature with padding
        this.map.getView().fit(fitExtent, {
          padding: [50, 50, 50, 50],
          maxZoom: 15,
          duration: 500
        })
      } catch (error) {
        console.error('Error zooming to selected feature:', error)
        // Fallback to fitting all features if zoom fails
        this.fitMapToAllFeatures()
      }
    },

    fitMapToAllFeatures() {
      if (!this.map || !this.vectorSource) return

      const features = this.vectorSource.getFeatures()
      if (features.length === 0) return

      // Get the extent of all features
      const extent = this.vectorSource.getExtent()
      
      // Fit the map to show all features with padding
      this.map.getView().fit(extent, {
        padding: [50, 50, 50, 50],
        maxZoom: 15
      })
    },

    closeDialog() {
      this.$emit('close')
    },

    cleanup() {
      if (this.map) {
        this.map.setTarget(null)
        this.map = null
      }
      this.vectorSource = null
      this.vectorLayer = null
      this.labelLayer = null
      this.selectedFeature = null
    }
  },

  beforeUnmount() {
    this.cleanup()
  }
}
</script>

<style scoped>
/* Hide OpenLayers attribution */
:deep(.ol-attribution) {
  display: none;
}
</style>
