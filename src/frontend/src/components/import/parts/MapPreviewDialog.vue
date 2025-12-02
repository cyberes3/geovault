<template>
  <div
    v-if="isOpen"
    class="fixed inset-0 z-50"
    role="dialog"
    aria-modal="true"
    @mousedown="handleBackdropMouseDown"
  >
    <!-- Backdrop -->
    <div class="absolute inset-0 bg-black/50"></div>

    <!-- Modal panel -->
    <div class="absolute inset-0 flex items-stretch justify-stretch sm:items-center sm:justify-center">
      <div
        class="bg-white flex flex-col w-full h-full sm:h-[90vh] sm:max-w-6xl sm:rounded-lg shadow-xl overflow-hidden"
        @mousedown.stop
        @click.stop
      >
        <!-- Header -->
        <div class="flex items-center justify-between px-6 lg:px-8 py-4 border-b border-gray-200 bg-gray-50 sm:rounded-t-lg">
          <h3 class="text-lg font-medium text-gray-900">Map Preview</h3>
          <button
            @click="closeDialog"
            class="text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150"
            title="Close dialog"
          >
            <XMarkIcon class="h-6 w-6" />
          </button>
        </div>

        <!-- Map Container -->
        <div class="flex-1 bg-white min-h-0 flex flex-col overflow-hidden relative">
          <!-- Map -->
          <div ref="mapContainer" class="flex-1 w-full border-0"></div>

          <!-- Loading Indicator -->
          <div v-show="isLoading" class="absolute top-4 right-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10">
            <Loader size="sm" layout="inline" message="Loading preview..." />
          </div>

          <!-- Feature Info -->
          <div class="absolute bottom-4 left-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10 text-xs">
            <div class="space-y-1">
              <div>Features: <span class="font-medium">{{ featureCount }}</span></div>
              <div v-if="filename">File: <span class="font-medium">{{ filename }}</span></div>
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
import {Style, Fill, Stroke, Circle, Text} from 'ol/style'
import {GeoJSON} from 'ol/format'
import {fromLonLat} from 'ol/proj'
import Loader from '@/components/parts/Loader.vue'
import { XMarkIcon } from '@heroicons/vue/24/outline'

export default {
  name: 'MapPreviewDialog',
  components: {
    Loader,
    XMarkIcon
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
      featureCount: 0
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        // Prevent background scroll
        document.body.classList.add('overflow-hidden')
        // Move modal to body to avoid layout offsets
        this.$nextTick(() => {
          if (this.$el && this.$el.parentNode !== document.body) {
            document.body.appendChild(this.$el)
          }
          this.initializeMap()
          this.loadFeatures()
        })
        // Add escape key listener when dialog opens
        document.addEventListener('keydown', this.handleEscapeKey)
      } else {
        // Restore background scroll
        document.body.classList.remove('overflow-hidden')
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
          center: fromLonLat([-104.692626, 38.881215]),
          zoom: 10
        })
      })

    },

    getFeatureStyle(feature) {
      const properties = feature.get('properties') || {}
      const geometryType = feature.getGeometry().getType()
      const name = properties.name || 'Unnamed Feature'

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
            radius: 8,
            fill: new Fill({
              color: fillColor
            })
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
            offsetY: -15
          })
        })
      } else if (geometryType === 'LineString') {
        // Lines use stroke and stroke-width
        const strokeColor = hexToColor(properties.stroke, '#ff0000')
        return new Style({
          stroke: new Stroke({
            color: strokeColor,
            width: properties['stroke-width'] || 3
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

      // Default style for unknown geometry types
      return new Style({
        stroke: new Stroke({
          color: '#ff0000',
          width: 2
        }),
        fill: new Fill({
          color: 'rgba(255, 0, 0, 0.3)'
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
          // Convert the feature to a standard GeoJSON format
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

        // Fit map to show all features
        if (features.length > 0) {
          const extent = this.vectorSource.getExtent()
          this.map.getView().fit(extent, {
            padding: [50, 50, 50, 50],
            maxZoom: 15
          })
        }

      } catch (error) {
        console.error('Error loading features for preview:', error)
      } finally {
        this.isLoading = false
      }
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
    }
  },

  beforeUnmount() {
    // Restore background scroll
    document.body.classList.remove('overflow-hidden')
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
