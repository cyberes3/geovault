<template>
  <BaseModal
    :is-open="isOpen"
    title="Update Spatial Data"
    max-width="4xl"
    @close="handleCancel"
  >
    <div class="px-6 py-4">
      <!-- File Selection Section (shown before upload starts) -->
          <div v-if="!importQueueId && !processing" class="space-y-4">
            <!-- Help Text -->
            <div class="p-4 bg-blue-50 border border-blue-200 rounded-lg">
              <div class="flex items-start">
                <InformationCircleIcon class="h-5 w-5 text-blue-500 mr-3 flex-shrink-0 mt-0.5" />
                <div class="flex-1 space-y-2 text-sm text-blue-800">
                  <p>Upload a <strong>KMZ, KML, or GPX</strong> file to replace this feature's spatial geometry.</p>
                  <p>Only features with <strong>matching geometry types</strong> (Point, LineString, or Polygon) will be available for selection.</p>
                  <p>The feature's <strong>name, description, and other properties will remain unchanged</strong>.</p>
                </div>
              </div>
            </div>

            <!-- File Drop Zone (only shown when no file is selected) -->
            <div v-if="!selectedFile">
              <label class="block text-sm font-medium text-gray-700 mb-2">
                Select KMZ/KML/GPX File
              </label>
              <div class="mt-1 flex items-center space-x-4">
                <label class="flex-1 cursor-pointer">
                  <input
                    ref="fileInput"
                    type="file"
                    accept=".kmz,.kml,.gpx"
                    @change="handleFileSelect"
                    class="hidden"
                  />
                  <div
                    :class="dropzoneClasses"
                    class="flex items-center justify-center px-6 py-3 border-2 border-dashed rounded-lg transition-colors"
                    @drop="onDrop"
                    @dragover.prevent
                    @dragenter.prevent="dragEnter"
                    @dragleave="dragLeave"
                  >
                    <div class="text-center">
                      <DocumentIcon class="mx-auto h-12 w-12 text-gray-400" />
                      <p class="mt-2 text-sm text-gray-600">
                        <span class="font-medium text-blue-500 hover:text-blue-700">Click to browse</span> or drag and drop
                      </p>
                      <p class="mt-1 text-xs text-gray-500">KMZ, KML, or GPX files only (max 5MB)</p>
                    </div>
                  </div>
                </label>
              </div>
            </div>

            <!-- Selected File Display (shown when file is selected) -->
            <div v-if="selectedFile" class="p-4 bg-blue-50 border border-blue-200 rounded-lg">
              <div class="flex items-center justify-between">
                <div class="flex items-center space-x-3 flex-1 min-w-0">
                  <DocumentIcon class="h-5 w-5 text-blue-500 flex-shrink-0" />
                  <div class="flex-1 min-w-0">
                    <p class="text-sm font-medium text-gray-900 truncate">{{ selectedFile.name }}</p>
                    <p class="text-xs text-gray-500">{{ formatFileSize(selectedFile.size) }}</p>
                  </div>
                </div>
                <button
                  @click="clearFileSelection"
                  class="ml-3 text-gray-400 hover:text-gray-600 focus:outline-none"
                  title="Remove file"
                >
                  <XMarkIcon class="h-5 w-5" />
                </button>
              </div>
            </div>

            <!-- Error Message -->
            <div v-if="errorMessage" class="p-4 bg-red-50 border-2 border-red-300 rounded-md">
              <div class="flex items-start">
                <ExclamationCircleIcon class="h-6 w-6 text-red-500 mr-3 flex-shrink-0 mt-0.5" />
                <p class="text-base font-medium text-red-900 leading-relaxed">{{ errorMessage }}</p>
              </div>
            </div>
          </div>

          <!-- Processing Section -->
          <div v-else-if="processing" class="space-y-4">
            <div class="text-center py-6">
              <Loader size="lg" layout="centered" :message="processingMessage" />
              <div v-if="processingProgress !== null" class="mt-6 max-w-md mx-auto">
                <div class="w-full bg-gray-200 rounded-full h-3 overflow-hidden">
                  <div
                    :style="{ width: processingProgress + '%' }"
                    class="bg-blue-500 h-3 rounded-full transition-all duration-300"
                  >
                  </div>
                </div>
                <p class="text-xs text-gray-500 mt-2">{{ Math.round(processingProgress) }}% complete</p>
              </div>
              <p v-if="selectedFile" class="mt-4 text-xs text-gray-500">Processing: {{ selectedFile.name }}</p>
            </div>
          </div>

          <!-- Feature Selection Section -->
          <div v-else-if="features.length > 0" class="space-y-4">
            <!-- No matching features message -->
            <div v-if="sortedFeatures.length === 0" class="p-4 bg-yellow-50 border border-yellow-200 rounded-lg">
              <div class="flex">
                <ExclamationTriangleIcon class="h-5 w-5 text-yellow-400 mr-2 flex-shrink-0" />
                <div>
                  <p class="text-sm font-medium text-yellow-800">No matching geometry types found</p>
                  <p class="text-xs text-yellow-700 mt-1">
                    The uploaded file contains {{ features.length }} feature{{ features.length !== 1 ? 's' : '' }},
                    but none match the geometry type of the existing feature ({{ existingFeatureGeometryType }}).
                    Only features with the same geometry type can be used for replacement.
                  </p>
                </div>
              </div>
            </div>

            <!-- Features list -->
            <div v-else>
              <div class="mb-3">
                <div class="flex items-center justify-between mb-2">
                  <h4 class="text-sm font-medium text-gray-900">
                    Select a feature to apply its spatial data:
                    <span v-if="features.length !== sortedFeatures.length" class="text-xs font-normal text-gray-500 ml-2">
                      ({{ sortedFeatures.length }} of {{ features.length }} matching geometry type)
                    </span>
                  </h4>
                </div>
                <div class="flex items-center gap-3 p-3 bg-gray-50 border border-gray-200 rounded-md">
                  <div class="flex-1">
                    <label class="block text-sm font-medium text-gray-700 mb-1">
                      Regenerate automatic tags
                    </label>
                    <p class="text-xs text-gray-600">
                      When enabled, location and geometry-based tags will be automatically updated based on the new spatial data. Your custom tags will be preserved.
                    </p>
                  </div>
                  <div class="flex-shrink-0">
                    <ToggleButton
                      v-model="regenerateTags"
                      size="md"
                    />
                  </div>
                </div>
              </div>
              <div class="space-y-2 max-h-96 overflow-y-auto">
                <div
                  v-for="(feature, index) in sortedFeatures"
                  :key="index"
                  @click="selectedFeatureIndex = index"
                  :class="[
                    'p-4 border-2 rounded-lg cursor-pointer transition-colors',
                    selectedFeatureIndex === index
                      ? 'border-blue-500 bg-blue-50'
                      : 'border-gray-200 hover:border-gray-300'
                  ]"
                >
                  <div class="flex items-start gap-4">
                    <!-- Map Preview -->
                    <div class="flex-shrink-0 relative">
                      <div
                        :ref="el => setMapRef(el, index)"
                        :id="`feature-map-${index}`"
                        class="w-32 h-32 border border-gray-300 rounded-md overflow-hidden"
                        @click.stop
                      ></div>
                      <!-- Expand Map Button -->
                      <button
                        @click.stop="expandMap(index)"
                        class="absolute top-1 right-1 bg-white bg-opacity-90 hover:bg-opacity-100 rounded p-1 shadow-sm border border-gray-300 transition-all"
                        title="Expand map preview"
                      >
                        <ArrowsPointingOutIcon class="w-4 h-4 text-gray-700" />
                      </button>
                    </div>

                    <!-- Feature Info -->
                    <div class="flex-1 min-w-0">
                      <div class="flex items-start justify-between">
                        <div class="flex-1 min-w-0">
                          <h5 class="text-sm font-medium text-gray-900">
                            {{ feature.properties?.name || `Feature ${index + 1}` }}
                          </h5>
                          <p v-if="feature.properties?.description" class="text-xs text-gray-600 mt-1 line-clamp-2">
                            {{ feature.properties.description }}
                          </p>
                        </div>
                        <div v-if="selectedFeatureIndex === index" class="ml-4 flex-shrink-0">
                          <CheckIcon class="h-5 w-5 text-blue-500" />
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- Error Message -->
            <div v-if="errorMessage" class="p-4 bg-red-50 border-2 border-red-300 rounded-md">
              <div class="flex items-start">
                <ExclamationCircleIcon class="h-6 w-6 text-red-500 mr-3 flex-shrink-0 mt-0.5" />
                <p class="text-base font-medium text-red-900 leading-relaxed">{{ errorMessage }}</p>
              </div>
            </div>

            <!-- Success Message -->
            <div v-if="successMessage" class="p-3 bg-green-50 border border-green-200 rounded-md">
              <div class="flex">
                <CheckIcon class="h-5 w-5 text-green-400 mr-2" />
                <p class="text-sm text-green-800">{{ successMessage }}</p>
              </div>
            </div>
          </div>
        </div>

    <template #footer>
      <!-- Cancel Button (shown when not processing or when no features available) -->
      <BaseButton
        v-if="!importQueueId || (!processing && features.length === 0)"
        @click="handleCancel"
        variant="white"
        size="sm"
        title="Cancel"
      >
        Cancel
      </BaseButton>

      <!-- Close Button (shown after applied) -->
      <BaseButton
        v-if="applied"
        @click="handleClose"
        variant="primary"
        color="blue"
        size="sm"
        title="Close dialog"
      >
        Close
      </BaseButton>

      <!-- Upload Button (shown when file is selected but not yet uploaded) -->
      <BaseButton
        v-if="selectedFile && !importQueueId && !processing"
        @click="handleUpload"
        variant="primary"
        color="blue"
        size="sm"
        title="Upload and process file"
      >
        <ArrowUpTrayIcon class="h-4 w-4 mr-2" />
        Upload & Process
      </BaseButton>

      <!-- Apply Button (always shown when features are available, disabled when not ready) -->
      <BaseButton
        v-if="sortedFeatures.length > 0 && !applied"
        @click="handleApply"
        :disabled="applying || selectedFeatureIndex === null"
        variant="primary"
        color="blue"
        size="sm"
        title="Apply selected feature's spatial data"
      >
        <CheckIcon v-if="!applying" class="h-4 w-4 mr-2" />
        <Loader v-if="applying" size="sm" layout="inline" :showMessage="false" color="white" />
        {{ applying ? 'Applying...' : 'Apply Spatial Data' }}
      </BaseButton>
    </template>
  </BaseModal>

  <!-- Expanded Map Modal -->
  <BaseModal
    :is-open="expandedMapIndex !== null"
    :title="expandedMapIndex !== null ? (sortedFeatures[expandedMapIndex]?.properties?.name || `Feature ${expandedMapIndex + 1}`) + ' - Map Preview' : ''"
    max-width="6xl"
    :on-top="true"
    :full-screen-mobile="true"
    @close="closeExpandedMap"
  >
    <div class="flex-1 min-h-0 flex flex-col p-6 h-full">
      <div
        :ref="el => setExpandedMapRef(el)"
        id="expanded-feature-map"
        class="flex-1 min-h-0 w-full border border-gray-300 rounded-md overflow-hidden"
      ></div>
    </div>
  </BaseModal>
</template>

<script>
import {APIHOST} from '@/config.js'
import {PROCESSING_MESSAGES} from '@/assets/js/constants/processing-messages.js'
import {Map, View} from 'ol'
import {OSM} from 'ol/source'
import {Tile as TileLayer, Vector as VectorLayer} from 'ol/layer'
import {Vector as VectorSource} from 'ol/source'
import {Style, Fill, Stroke, Circle} from 'ol/style'
import {GeoJSON} from 'ol/format'
import {fromLonLat} from 'ol/proj'
import {getCenter} from 'ol/extent'
import {DragPan, MouseWheelZoom} from 'ol/interaction'
import {markRaw} from 'vue'
import BaseModal from '@/components/parts/BaseModal.vue'
import BaseButton from '@/components/parts/BaseButton.vue'
import Loader from '@/components/parts/Loader.vue'
import ToggleButton from '@/components/parts/ToggleButton.vue'
import { InformationCircleIcon, DocumentIcon, ExclamationCircleIcon, ExclamationTriangleIcon, CheckIcon, ArrowUpTrayIcon, ArrowsPointingOutIcon, XMarkIcon } from '@heroicons/vue/24/outline'

export default {
  name: 'ReplacementFeatureDialog',
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    featureId: {
      type: Number,
      required: true
    }
  },
  emits: ['close', 'applied'],
  components: {
    BaseModal,
    BaseButton,
    Loader,
    ToggleButton,
    XMarkIcon,
    InformationCircleIcon,
    DocumentIcon,
    ExclamationCircleIcon,
    ExclamationTriangleIcon,
    CheckIcon,
    ArrowUpTrayIcon,
    ArrowsPointingOutIcon
  },
  data() {
    return {
      importQueueId: null,
      jobId: null,
      processing: false,
      processingMessage: 'Processing file...',
      processingProgress: null,
      features: [],
      selectedFeatureIndex: null,
      errorMessage: '',
      successMessage: '',
      applying: false,
      applied: false,
      regenerateTags: false,
      ws: null,
      wsConnected: false,
      pollingInterval: null,
      selectedFile: null,
      existingFeatureGeometryType: null,
      featureMaps: {}, // Store map instances by index
      expandedMapIndex: null, // Index of currently expanded map
      expandedMap: null, // Expanded map instance
      isDragOver: false
    }
  },
  computed: {
    sortedFeatures() {
      // Filter features by geometry type matching the existing feature
      let filtered = this.features

      if (this.existingFeatureGeometryType) {
        filtered = this.features.filter(feature => {
          const featureType = feature.geometry?.type
          return this.geometryTypesMatch(this.existingFeatureGeometryType, featureType)
        })
      }

      // Sort features alphabetically by name
      return filtered.sort((a, b) => {
        const nameA = (a.properties?.name || '').toLowerCase()
        const nameB = (b.properties?.name || '').toLowerCase()
        return nameA.localeCompare(nameB)
      })
    },
    dropzoneClasses() {
      if (this.isDragOver) {
        return 'border-blue-600 bg-blue-50'
      } else {
        return 'border-gray-300 hover:border-blue-600 hover:bg-blue-50'
      }
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        this.$nextTick(() => {
          this.resetDialog()
          this.fetchExistingFeatureGeometryType()
        })
      } else {
        this.cleanup()
      }
    },
    expandedMapIndex(newVal) {
      if (newVal !== null) {
        // Wait for BaseModal to fully render before initializing map
        this.$nextTick(() => {
          // Additional delay to ensure BaseModal content is visible
          setTimeout(() => {
            this.initializeExpandedMap()
          }, 100)
        })
      } else {
        // Clean up map when modal closes
        if (this.expandedMap) {
          this.expandedMap.map.setTarget(null)
          this.expandedMap.map = null
          if (this.expandedMap.vectorSource) {
            this.expandedMap.vectorSource.clear()
          }
          this.expandedMap = null
        }
      }
    },
    sortedFeatures: {
      handler() {
        // Reinitialize maps when features change
        this.$nextTick(() => {
          this.sortedFeatures.forEach((feature, index) => {
            const container = document.getElementById(`feature-map-${index}`)
            if (container && !this.featureMaps[index]) {
              this.initializeFeatureMap(container, index)
            }
          })
        })
      },
      deep: true
    },
    $route() {
      // Close dialog when route changes
      if (this.isOpen) {
        this.handleClose()
      }
    }
  },
  methods: {
    /**
     * Get color value and convert to rgba with optional opacity
     * @param {string} color - Hex color value (e.g., '#163D8A')
     * @param {number} opacity - Optional opacity (0-1), defaults to 1
     * @returns {string} rgba color string
     */
    getColorWithOpacity(color, opacity = 1) {
      // Convert hex to rgb
      if (color.startsWith('#')) {
        const hex = color.replace('#', '')
        const r = parseInt(hex.substring(0, 2), 16)
        const g = parseInt(hex.substring(2, 4), 16)
        const b = parseInt(hex.substring(4, 6), 16)
        return opacity === 1 ? color : `rgba(${r}, ${g}, ${b}, ${opacity})`
      }

      // If already rgb/rgba, extract values and apply opacity
      const rgbMatch = color.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/)
      if (rgbMatch) {
        const r = parseInt(rgbMatch[1])
        const g = parseInt(rgbMatch[2])
        const b = parseInt(rgbMatch[3])
        return `rgba(${r}, ${g}, ${b}, ${opacity})`
      }

      // Fallback to blue-500
      return opacity === 1 ? '#163D8A' : `rgba(22, 61, 138, ${opacity})`
    },
    resetDialog() {
      // Clean up all maps
      this.cleanupMaps()

      this.importQueueId = null
      this.jobId = null
      this.processing = false
      this.processingMessage = 'Processing file...'
      this.processingProgress = null
      this.features = []
      this.selectedFeatureIndex = null
      this.errorMessage = ''
      this.successMessage = ''
      this.applying = false
      this.applied = false
      this.regenerateTags = false
      this.selectedFile = null
      this.existingFeatureGeometryType = null
      this.expandedMapIndex = null
      if (this.$refs.fileInput) {
        this.$refs.fileInput.value = ''
      }
    },
    cleanupMaps() {
      // Clean up all map instances
      Object.values(this.featureMaps).forEach(mapData => {
        if (mapData.map) {
          mapData.map.setTarget(null)
          mapData.map = null
        }
        if (mapData.vectorSource) {
          mapData.vectorSource.clear()
          mapData.vectorSource = null
        }
      })
      this.featureMaps = {}

      // Clean up expanded map
      if (this.expandedMap) {
        this.expandedMap.map.setTarget(null)
        this.expandedMap.map = null
        if (this.expandedMap.vectorSource) {
          this.expandedMap.vectorSource.clear()
        }
        this.expandedMap = null
      }
    },
    setMapRef(el, index) {
      if (el && !this.featureMaps[index]) {
        // Wait for next tick to ensure DOM is ready
        this.$nextTick(() => {
          this.initializeFeatureMap(el, index)
        })
      }
    },
    initializeFeatureMap(container, index) {
      if (!container || this.featureMaps[index]) return

      const feature = this.sortedFeatures[index]
      if (!feature || !feature.geometry) return

      try {
        // Create vector source
        const vectorSource = markRaw(new VectorSource())

        // Create vector layer with simple styling
        const vectorLayer = markRaw(new VectorLayer({
          source: vectorSource,
          style: (feature) => {
            const geometryType = feature.getGeometry().getType()
            if (geometryType === 'Point' || geometryType === 'MultiPoint') {
              return new Style({
                image: new Circle({
                  radius: 6,
                  fill: new Fill({ color: '#fbbf24' }), // Yellow
                  stroke: new Stroke({ color: '#000000', width: 2 }) // Black border
                })
              })
            } else if (geometryType === 'LineString' || geometryType === 'MultiLineString') {
              return new Style({
                stroke: new Stroke({ color: this.getColorWithOpacity('#163D8A'), width: 3 })
              })
            } else if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
              return new Style({
                fill: new Fill({ color: this.getColorWithOpacity('#163D8A', 0.3) }),
                stroke: new Stroke({ color: this.getColorWithOpacity('#163D8A'), width: 2 })
              })
            }
            return new Style({
              stroke: new Stroke({ color: this.getColorWithOpacity('#163D8A'), width: 2 }),
              fill: new Fill({ color: this.getColorWithOpacity('#163D8A', 0.3) })
            })
          }
        }))

        // Create tile layer
        const tileLayer = markRaw(new TileLayer({
          source: new OSM()
        }))

        // Convert feature to GeoJSON and add to map
        const geoJsonFeature = {
          type: 'Feature',
          geometry: feature.geometry,
          properties: feature.properties || {}
        }

        const format = new GeoJSON()
        const olFeature = format.readFeature(geoJsonFeature, {
          featureProjection: 'EPSG:3857',
          dataProjection: 'EPSG:4326'
        })

        vectorSource.addFeature(olFeature)

        // Calculate center and extent
        const extent = vectorSource.getExtent()
        const center = getCenter(extent)

        // Create a 50 mile extent (50 miles = 80,467 meters)
        // Buffer the center by 50 miles in each direction
        const bufferDistance = 50 * 1609.34 // 50 miles in meters
        const bufferedExtent = [
          center[0] - bufferDistance, // minX
          center[1] - bufferDistance, // minY
          center[0] + bufferDistance, // maxX
          center[1] + bufferDistance  // maxY
        ]

        // Create map with pan and zoom interactions
        const map = markRaw(new Map({
          target: container,
          layers: [tileLayer, vectorLayer],
          view: new View({
            center: center,
            maxZoom: 18
          }),
          controls: [],
          interactions: [
            new DragPan(),
            new MouseWheelZoom()
          ]
        }))

        // Store map instance
        this.featureMaps[index] = {
          map,
          vectorSource,
          vectorLayer,
          tileLayer
        }

        // Fit to 50 mile extent
        map.getView().fit(bufferedExtent, {
          padding: [10, 10, 10, 10],
          duration: 0
        })
      } catch (error) {
        console.error(`Error initializing map for feature ${index}:`, error)
      }
    },
    calculateZoomForExtent(extent) {
      // Simple zoom calculation based on extent size
      const width = extent[2] - extent[0]
      const height = extent[3] - extent[1]
      const maxDim = Math.max(width, height)

      // Approximate zoom level based on extent size
      if (maxDim > 20000000) return 3
      if (maxDim > 10000000) return 4
      if (maxDim > 5000000) return 5
      if (maxDim > 2000000) return 6
      if (maxDim > 1000000) return 7
      if (maxDim > 500000) return 8
      if (maxDim > 200000) return 9
      if (maxDim > 100000) return 10
      if (maxDim > 50000) return 11
      if (maxDim > 20000) return 12
      if (maxDim > 10000) return 13
      if (maxDim > 5000) return 14
      if (maxDim > 2000) return 15
      return 16
    },
    expandMap(index) {
      this.expandedMapIndex = index
      // Map initialization will be handled by the watcher
    },
    closeExpandedMap() {
      if (this.expandedMap) {
        this.expandedMap.map.setTarget(null)
        this.expandedMap.map = null
        if (this.expandedMap.vectorSource) {
          this.expandedMap.vectorSource.clear()
        }
        this.expandedMap = null
      }
      this.expandedMapIndex = null
    },
    setExpandedMapRef(el) {
      // Ref callback - map initialization is handled by watcher
      // This is kept for potential future use
    },
    initializeExpandedMap() {
      if (this.expandedMapIndex === null || this.expandedMap) return

      const container = document.getElementById('expanded-feature-map')
      if (!container) return

      const feature = this.sortedFeatures[this.expandedMapIndex]
      if (!feature || !feature.geometry) return

      try {
        // Create vector source
        const vectorSource = markRaw(new VectorSource())

        // Create vector layer with same styling as small maps
        const vectorLayer = markRaw(new VectorLayer({
          source: vectorSource,
          style: (feature) => {
            const geometryType = feature.getGeometry().getType()
            if (geometryType === 'Point' || geometryType === 'MultiPoint') {
              return new Style({
                image: new Circle({
                  radius: 8, // Slightly larger for expanded view
                  fill: new Fill({ color: '#fbbf24' }), // Yellow
                  stroke: new Stroke({ color: '#000000', width: 2 }) // Black border
                })
              })
            } else if (geometryType === 'LineString' || geometryType === 'MultiLineString') {
              return new Style({
                stroke: new Stroke({ color: this.getColorWithOpacity('#163D8A'), width: 3 })
              })
            } else if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
              return new Style({
                fill: new Fill({ color: this.getColorWithOpacity('#163D8A', 0.3) }),
                stroke: new Stroke({ color: this.getColorWithOpacity('#163D8A'), width: 2 })
              })
            }
            return new Style({
              stroke: new Stroke({ color: this.getColorWithOpacity('#163D8A'), width: 2 }),
              fill: new Fill({ color: this.getColorWithOpacity('#163D8A', 0.3) })
            })
          }
        }))

        // Create tile layer
        const tileLayer = markRaw(new TileLayer({
          source: new OSM()
        }))

        // Convert feature to GeoJSON and add to map
        const geoJsonFeature = {
          type: 'Feature',
          geometry: feature.geometry,
          properties: feature.properties || {}
        }

        const format = new GeoJSON()
        const olFeature = format.readFeature(geoJsonFeature, {
          featureProjection: 'EPSG:3857',
          dataProjection: 'EPSG:4326'
        })

        vectorSource.addFeature(olFeature)

        // Calculate center and extent
        const extent = vectorSource.getExtent()
        const center = getCenter(extent)

        // Create a 50 mile extent (50 miles = 80,467 meters)
        const bufferDistance = 50 * 1609.34 // 50 miles in meters
        const bufferedExtent = [
          center[0] - bufferDistance,
          center[1] - bufferDistance,
          center[0] + bufferDistance,
          center[1] + bufferDistance
        ]

        // Create map with full interactions
        const map = markRaw(new Map({
          target: container,
          layers: [tileLayer, vectorLayer],
          view: new View({
            center: center,
            maxZoom: 18
          }),
          controls: [],
          interactions: [
            new DragPan(),
            new MouseWheelZoom()
          ]
        }))

        // Store expanded map instance
        this.expandedMap = {
          map,
          vectorSource,
          vectorLayer,
          tileLayer
        }

        // Fit to 50 mile extent
        map.getView().fit(bufferedExtent, {
          padding: [20, 20, 20, 20],
          duration: 0
        })
      } catch (error) {
        console.error(`Error initializing expanded map:`, error)
      }
    },
    async fetchExistingFeatureGeometryType() {
      try {
        const response = await fetch(`${APIHOST}/api/feature/${this.featureId}/`, {
          credentials: 'include'
        })
        const data = await response.json()

        if (response.ok && data.feature && data.feature.geojson) {
          const geojson = data.feature.geojson
          if (geojson.geometry && geojson.geometry.type) {
            this.existingFeatureGeometryType = geojson.geometry.type
          }
        }
      } catch (error) {
        console.error('Error fetching existing feature geometry type:', error)
        // Continue without filtering if we can't fetch the geometry type
      }
    },
    geometryTypesMatch(existingType, replacementType) {
      if (!existingType || !replacementType) return false

      // Normalize geometry types to base types
      const normalizeType = (type) => {
        if (type === 'Point' || type === 'MultiPoint') return 'Point'
        if (type === 'LineString' || type === 'MultiLineString') return 'LineString'
        if (type === 'Polygon' || type === 'MultiPolygon') return 'Polygon'
        return type
      }

      return normalizeType(existingType) === normalizeType(replacementType)
    },
    cleanup() {
      if (this.ws) {
        this.ws.close()
        this.ws = null
      }
      if (this.pollingInterval) {
        clearInterval(this.pollingInterval)
        this.pollingInterval = null
      }
      this.cleanupMaps()
    },
    handleFileSelect(event) {
      const file = event.target.files[0]
      if (!file) {
        this.selectedFile = null
        return
      }

      // Prevent selecting a new file if one is already being processed
      if (this.processing || this.importQueueId) {
        this.errorMessage = 'Please wait for the current upload to complete'
        if (this.$refs.fileInput) {
          this.$refs.fileInput.value = ''
        }
        return
      }

      // Validate file type
      const fileName = file.name.toLowerCase()
      if (!fileName.endsWith('.kmz') && !fileName.endsWith('.kml') && !fileName.endsWith('.gpx')) {
        this.errorMessage = 'Please select a KMZ, KML, or GPX file'
        this.selectedFile = null
        if (this.$refs.fileInput) {
          this.$refs.fileInput.value = ''
        }
        return
      }

      // Clear any previous errors
      this.errorMessage = ''
      this.selectedFile = file
    },
    clearFileSelection() {
      this.selectedFile = null
      this.errorMessage = ''
      if (this.$refs.fileInput) {
        this.$refs.fileInput.value = ''
      }
    },
    onDrop(e) {
      e.preventDefault()
      e.stopPropagation()
      this.isDragOver = false

      const droppedFiles = Array.from(e.dataTransfer.files)
      if (droppedFiles.length === 0) {
        return
      }

      // Only use the first file (this component handles single file upload)
      const file = droppedFiles[0]

      // Use the same validation logic as handleFileSelect
      if (!file) {
        this.selectedFile = null
        return
      }

      // Prevent selecting a new file if one is already being processed
      if (this.processing || this.importQueueId) {
        this.errorMessage = 'Please wait for the current upload to complete'
        return
      }

      // Validate file type
      const fileName = file.name.toLowerCase()
      if (!fileName.endsWith('.kmz') && !fileName.endsWith('.kml') && !fileName.endsWith('.gpx')) {
        this.errorMessage = 'Please select a KMZ, KML, or GPX file'
        this.selectedFile = null
        return
      }

      // Clear any previous errors
      this.errorMessage = ''
      this.selectedFile = file
    },
    dragEnter(e) {
      e.preventDefault()
      e.stopPropagation()
      this.isDragOver = true
    },
    dragLeave(e) {
      e.preventDefault()
      e.stopPropagation()
      // Only set isDragOver to false if we're leaving the dropzone entirely
      // Check if the related target is outside the dropzone
      const dropzone = e.currentTarget
      if (!dropzone.contains(e.relatedTarget)) {
        this.isDragOver = false
      }
    },
    handleUpload() {
      if (!this.selectedFile) {
        this.errorMessage = 'Please select a file first'
        return
      }
      this.uploadFile(this.selectedFile)
    },
    async uploadFile(file) {
      this.errorMessage = ''
      this.processing = true
      this.processingMessage = 'Uploading file...'
      this.processingProgress = 0

      try {
        const formData = new FormData()
        formData.append('file', file)
        formData.append('replacement', this.featureId.toString())

        const response = await fetch(`${APIHOST}/api/item/import/upload`, {
          method: 'POST',
          headers: {
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include',
          body: formData
        })

        const data = await response.json()

        if (!response.ok) {
          this.errorMessage = data.msg || 'Failed to upload file'
          this.processing = false
          return
        }

        this.jobId = data.job_id
        this.processingMessage = 'Processing file...'
        this.processingProgress = 10

        // Start polling for processing status
        this.startPolling()
      } catch (error) {
        console.error('Error uploading file:', error)
        this.errorMessage = `Error: ${error.message}`
        this.processing = false
      }
    },
    startPolling() {
      // Poll for job status
      this.pollingInterval = setInterval(async () => {
        if (!this.jobId) return

        try {
          const response = await fetch(`${APIHOST}/api/item/import/status/${this.jobId}`, {
            credentials: 'include'
          })
          const data = await response.json()

          if (response.ok && data.job_status) {
            this.processingProgress = data.job_status.progress || 0
            this.processingMessage = data.job_status.message || 'Processing...'

            if (data.job_status.status === 'completed') {
              // Get the import table item ID from the job status
              this.importQueueId = data.job_status.import_queue_id
              clearInterval(this.pollingInterval)
              this.pollingInterval = null
              await this.fetchFeatures()
            } else if (data.job_status.status === 'failed') {
              this.errorMessage = data.job_status.error_message || PROCESSING_MESSAGES.PROCESSING_FAILED_DEFAULT
              this.processing = false
              clearInterval(this.pollingInterval)
              this.pollingInterval = null
            }
          }
        } catch (error) {
          console.error('Error polling status:', error)
        }
      }, 1000)
    },
    async fetchFeatures() {
      if (!this.importQueueId) return

      try {
        const response = await fetch(`${APIHOST}/api/item/import/get/features/${this.importQueueId}`, {
          credentials: 'include'
        })
        const data = await response.json()

        if (response.ok && data.geofeatures) {
          this.features = data.geofeatures
          this.processing = false
        } else {
          this.errorMessage = data.error || 'Failed to load features'
          this.processing = false
        }
      } catch (error) {
        console.error('Error fetching features:', error)
        this.errorMessage = 'Failed to load features'
        this.processing = false
      }
    },
    async handleApply() {
      if (this.selectedFeatureIndex === null || !this.importQueueId) return

      // Get the selected feature from sorted list and find its index in the original features array
      const selectedFeature = this.sortedFeatures[this.selectedFeatureIndex]
      const originalIndex = this.features.findIndex(f => f === selectedFeature)

      if (originalIndex === -1) {
        this.errorMessage = 'Selected feature not found'
        return
      }

      this.applying = true
      this.errorMessage = ''

      try {
        const response = await fetch(`${APIHOST}/api/feature/${this.featureId}/apply-replacement/`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include',
          body: JSON.stringify({
            import_queue_id: this.importQueueId,
            feature_index: originalIndex,
            regenerate_tags: this.regenerateTags
          })
        })

        const data = await response.json()

        if (!response.ok) {
          this.errorMessage = data.error || 'Failed to apply replacement geometry'
          this.applying = false
          return
        }

        this.successMessage = 'Spatial data updated successfully!'
        this.applied = true
        this.applying = false
        this.$emit('applied')
      } catch (error) {
        console.error('Error applying replacement:', error)
        this.errorMessage = `Error: ${error.message}`
        this.applying = false
      }
    },
    handleCancel() {
      // Close dialog immediately
      this.handleClose()

      // Delete the import table item in the background (fire-and-forget)
      if (this.importQueueId) {
        fetch(`${APIHOST}/api/item/import/delete/${this.importQueueId}`, {
          method: 'DELETE',
          headers: {
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include'
        }).catch(error => {
          console.error('Error deleting import table item:', error)
        })
      }
    },
    handleClose() {
      this.cleanup()
      this.$emit('close')
    },
    getCsrfToken() {
      const name = 'csrftoken'
      let cookieValue = null
      if (document.cookie && document.cookie !== '') {
        const cookies = document.cookie.split(';')
        for (let i = 0; i < cookies.length; i++) {
          const cookie = cookies[i].trim()
          if (cookie.substring(0, name.length + 1) === (name + '=')) {
            cookieValue = decodeURIComponent(cookie.substring(name.length + 1))
            break
          }
        }
      }
      return cookieValue || ''
    },
    formatFileSize(bytes) {
      if (bytes === 0) return '0 Bytes'
      const k = 1024
      const sizes = ['Bytes', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(k))
      return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i]
    }
  },
  beforeUnmount() {
    this.cleanup()
  }
}
</script>

