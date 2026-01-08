<template>
  <BaseModal
    :is-open="isOpen"
    title="Map Preview"
    max-width="6xl"
    @close="closeDialog"
  >
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
  </BaseModal>
</template>

<script>
import {Map, View} from 'ol'
import {OSM} from 'ol/source'
import {Tile as TileLayer, Vector as VectorLayer} from 'ol/layer'
import {Vector as VectorSource} from 'ol/source'
import {Style, Fill, Stroke, Circle, Text} from 'ol/style'
import {GeoJSON} from 'ol/format'
import {fromLonLat} from 'ol/proj'
import BaseModal from '@/components/parts/BaseModal.vue'
import Loader from '@/components/parts/Loader.vue'

export default {
  name: 'MapPreviewDialog',
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
      featureCount: 0
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        this.$nextTick(() => {
          this.initializeMap()
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
    $route() {
      // Close dialog when route changes
      if (this.isOpen) {
        this.closeDialog()
      }
    }
  },
  methods: {
    initializeMap() {
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

      // Create map
      this.map = new Map({
        target: this.$refs.mapContainer,
        layers: [
          new TileLayer({
            source: new OSM()
          }),
          this.vectorLayer,
          this.labelLayer
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
            radius: 5,
            fill: new Fill({
              color: fillColor
            })
          })
        })
      } else if (geometryType === 'LineString') {
        // Lines use stroke and stroke-width
        const strokeColor = hexToColor(properties.stroke, '#ff0000')
        return new Style({
          stroke: new Stroke({
            color: strokeColor,
            width: properties['stroke-width'] || 3
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

    getLabelStyle(feature) {
      const properties = feature.get('properties') || {}
      const geometryType = feature.getGeometry().getType()
      const name = properties.name || ''

      // Only return label style if feature has a name
      if (!name) {
        return null
      }

      if (geometryType === 'Point') {
        return new Style({
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
        return new Style({
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
        return new Style({
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

      // Default label style
      return new Style({
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
