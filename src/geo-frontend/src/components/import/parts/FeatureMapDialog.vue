<template>
  <!-- Modal Backdrop -->
  <div v-if="isOpen" class="fixed inset-0 z-50 overflow-y-auto" @mousedown="handleBackdropMouseDown">
    <div class="flex items-center justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
      <!-- Background overlay -->
      <div class="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity"></div>

      <!-- Modal panel -->
      <div class="inline-block align-bottom bg-white rounded-lg text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-6xl sm:w-full" @click.stop @mousedown.stop>
        <!-- Header -->
        <div class="bg-white px-6 py-4 border-b border-gray-200">
          <div class="flex items-center justify-between">
            <div>
              <h3 class="text-lg font-medium text-gray-900">Feature Map View</h3>
              <p class="text-sm text-gray-600 mt-1">{{ selectedFeatureName }}</p>
            </div>
            <button
              @click="closeDialog"
              class="text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150"
            >
              <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        <!-- Map Container -->
        <div class="bg-white p-6">
          <div class="relative">
            <!-- Map -->
            <div ref="mapContainer" class="w-full h-96 md:h-[600px] border border-gray-300 rounded-lg"></div>

            <!-- Loading Indicator -->
            <div v-show="isLoading" class="absolute top-4 right-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10">
              <div class="flex items-center space-x-2">
                <div class="animate-spin rounded-full h-4 w-4 border-b-2 border-blue-600"></div>
                <span class="text-sm text-gray-700">Loading map...</span>
              </div>
            </div>

            <!-- Feature Info -->
            <div class="absolute bottom-4 left-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10 text-xs">
              <div class="space-y-1">
                <div>Total Features: <span class="font-medium">{{ featureCount }}</span></div>
                <div>Selected: <span class="font-medium">{{ selectedFeatureName }}</span></div>
              </div>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="bg-gray-50 px-6 py-3 border-t border-gray-200">
          <div class="flex justify-end">
            <button
              @click="closeDialog"
              class="inline-flex items-center px-4 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            >
              Close
            </button>
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
import {Style, Fill, Stroke, Circle, Text} from 'ol/style'
import {GeoJSON} from 'ol/format'
import {fromLonLat} from 'ol/proj'

export default {
  name: 'FeatureMapDialog',
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
        return properties.name || 'Unnamed Feature'
      }
      return 'Unknown Feature'
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        this.$nextTick(() => {
          this.initializeMap()
          this.loadFeatures()
        })
        // Add escape key listener when dialog opens
        document.addEventListener('keydown', this.handleEscapeKey)
      } else {
        this.cleanup()
        // Remove escape key listener when dialog closes
        document.removeEventListener('keydown', this.handleEscapeKey)
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
        }
      }
    }
  },
  methods: {
    initializeMap() {
      if (this.map) {
        this.cleanup()
      }

      // Create vector source and layer
      this.vectorSource = new VectorSource()

      this.vectorLayer = new VectorLayer({
        source: this.vectorSource,
        style: (feature) => this.getFeatureStyle(feature),
        declutter: true  // Declutter overlapping text labels (icons will still overlap)
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
          center: fromLonLat([-104.692626, 38.881215]), // Default to Denver, CO
          zoom: 10
        })
      })
    },

    getFeatureStyle(feature) {
      const properties = feature.get('properties') || feature.properties || {}
      const geometryType = feature.getGeometry().getType()
      const name = properties.name || 'Unnamed Feature'
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
        const fillColor = isSelected ? highlightColor : hexToColor(properties['marker-color'], '#ff0000')
        const strokeColor = isSelected ? highlightStrokeColor : 'transparent'
        const strokeWidth = isSelected ? 3 : 0

        return new Style({
          image: new Circle({
            radius: isSelected ? 12 : 8, // Larger radius for selected
            fill: new Fill({
              color: fillColor
            }),
            stroke: new Stroke({
              color: strokeColor,
              width: strokeWidth
            })
          }),
          text: new Text({
            text: name,
            font: isSelected ? 'bold 14px Arial' : '12px Arial',
            fill: new Fill({
              color: isSelected ? '#000000' : '#000000'
            }),
            stroke: new Stroke({
              color: '#ffffff',
              width: isSelected ? 4 : 3
            }),
            offsetY: isSelected ? -20 : -15
          })
        })
      } else if (geometryType === 'LineString') {
        if (isSelected) {
          // For selected LineStrings, create a multi-layer effect with black outline and yellow center
          return [
            // Black outline (wider)
            new Style({
              stroke: new Stroke({
                color: highlightStrokeColor,
                width: 10
              })
            }),
            // Yellow center (narrower)
            new Style({
              stroke: new Stroke({
                color: highlightColor,
                width: 6
              })
            }),
            // Text label
            new Style({
              text: new Text({
                text: name,
                font: 'bold 14px Arial',
                fill: new Fill({
                  color: '#000000'
                }),
                stroke: new Stroke({
                  color: '#ffffff',
                  width: 4
                }),
                offsetY: -15
              })
            })
          ]
        } else {
          // Normal styling for non-selected LineStrings
          const strokeColor = hexToColor(properties.stroke, '#ff0000')
          const strokeWidth = properties['stroke-width'] || 3

          return new Style({
            stroke: new Stroke({
              color: strokeColor,
              width: strokeWidth
            }),
            text: new Text({
              text: name,
              font: '12px Arial',
              fill: new Fill({
                color: '#000000'
              }),
              stroke: new Stroke({
                color: '#ffffff',
                width: 3
              }),
              offsetY: -10
            })
          })
        }
      } else if (geometryType === 'MultiPoint') {
        const fillColor = isSelected ? highlightColor : hexToColor(properties['marker-color'], '#ff0000')
        const strokeColor = isSelected ? highlightStrokeColor : '#000000'
        const strokeWidth = isSelected ? 3 : 2

        return new Style({
          image: new Circle({
            radius: isSelected ? 12 : 8, // Larger radius for selected
            fill: new Fill({
              color: fillColor
            }),
            stroke: new Stroke({
              color: strokeColor,
              width: strokeWidth
            })
          }),
          text: new Text({
            text: name,
            font: isSelected ? 'bold 14px Arial' : '12px Arial',
            fill: new Fill({
              color: isSelected ? '#000000' : '#000000'
            }),
            stroke: new Stroke({
              color: '#ffffff',
              width: isSelected ? 4 : 3
            }),
            offsetY: isSelected ? -20 : -15
          })
        })
      } else if (geometryType === 'MultiLineString') {
        if (isSelected) {
          // For selected MultiLineStrings, create a multi-layer effect with black outline and yellow center
          return [
            // Black outline (wider)
            new Style({
              stroke: new Stroke({
                color: highlightStrokeColor,
                width: 10
              })
            }),
            // Yellow center (narrower)
            new Style({
              stroke: new Stroke({
                color: highlightColor,
                width: 6
              })
            }),
            // Text label
            new Style({
              text: new Text({
                text: name,
                font: 'bold 14px Arial',
                fill: new Fill({
                  color: '#000000'
                }),
                stroke: new Stroke({
                  color: '#ffffff',
                  width: 4
                }),
                offsetY: -15
              })
            })
          ]
        } else {
          // Normal styling for non-selected MultiLineStrings with black border
          const strokeColor = hexToColor(properties.stroke, '#ff0000')
          const strokeWidth = properties['stroke-width'] || 3

          return [
            // Black border (wider)
            new Style({
              stroke: new Stroke({
                color: '#000000',
                width: strokeWidth + 2
              })
            }),
            // Colored center (narrower)
            new Style({
              stroke: new Stroke({
                color: strokeColor,
                width: strokeWidth
              })
            }),
            // Text label
            new Style({
              text: new Text({
                text: name,
                font: '12px Arial',
                fill: new Fill({
                  color: '#000000'
                }),
                stroke: new Stroke({
                  color: '#ffffff',
                  width: 3
                }),
                offsetY: -10
              })
            })
          ]
        }
      } else if (geometryType === 'MultiPolygon') {
        const strokeColor = isSelected ? highlightStrokeColor : hexToColor(properties.stroke, '#ff0000')
        let fillColor = isSelected ? highlightColor : hexToColor(properties.fill, '#ff0000')
        const strokeWidth = isSelected ? 4 : (properties['stroke-width'] || 2)

        // Apply fill-opacity if specified (but not for selected)
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
          }),
          text: new Text({
            text: name,
            font: isSelected ? 'bold 14px Arial' : '12px Arial',
            fill: new Fill({
              color: isSelected ? '#000000' : '#000000'
            }),
            stroke: new Stroke({
              color: '#ffffff',
              width: isSelected ? 4 : 3
            }),
            offsetY: isSelected ? -15 : -10
          })
        })
      } else if (geometryType === 'Polygon') {
        const strokeColor = isSelected ? highlightStrokeColor : hexToColor(properties.stroke, '#ff0000')
        let fillColor = isSelected ? highlightColor : hexToColor(properties.fill, '#ff0000')
        const strokeWidth = isSelected ? 4 : (properties['stroke-width'] || 2)

        // Apply fill-opacity if specified (but not for selected)
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
          }),
          text: new Text({
            text: name,
            font: isSelected ? 'bold 14px Arial' : '12px Arial',
            fill: new Fill({
              color: isSelected ? '#000000' : '#000000'
            }),
            stroke: new Stroke({
              color: '#ffffff',
              width: isSelected ? 4 : 3
            }),
            offsetY: isSelected ? -15 : -10
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
        }),
        text: new Text({
          text: name,
          font: isSelected ? 'bold 14px Arial' : '12px Arial',
          fill: new Fill({
            color: isSelected ? '#000000' : '#000000'
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
        }

        // Fit map to show all features
        this.fitMapToAllFeatures()

        console.log(`Loaded ${features.length} features for feature map view`)
      } catch (error) {
        console.error('Error loading features for feature map view:', error)
      } finally {
        this.isLoading = false
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

    handleBackdropMouseDown(event) {
      // Only close if the mousedown is on the backdrop itself, not on children
      if (event.target === event.currentTarget) {
        this.closeDialog()
      }
    },
    handleEscapeKey(event) {
      if (event.key === 'Escape') {
        this.closeDialog()
      }
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
      this.selectedFeature = null
    }
  },

  beforeUnmount() {
    this.cleanup()
    // Remove escape key listener if component is unmounted while dialog is open
    document.removeEventListener('keydown', this.handleEscapeKey)
  }
}
</script>

<style scoped>
/* Hide OpenLayers attribution */
:deep(.ol-attribution) {
  display: none;
}
</style>
