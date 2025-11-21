<template>
  <!-- Modal Backdrop -->
  <div v-if="isOpen" class="fixed inset-0 z-50 overflow-y-auto" @mousedown="handleBackdropMouseDown">
    <div class="flex items-center justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
      <!-- Background overlay -->
      <div class="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity"></div>

      <!-- Modal panel -->
      <div class="inline-block align-bottom bg-white rounded-lg text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-4xl sm:w-full" @click.stop @mousedown.stop>
        <!-- Header -->
        <div class="bg-white px-6 py-4 border-b border-gray-200">
          <div class="flex items-center justify-between">
            <h3 class="text-lg font-medium text-gray-900">Update Spatial Data</h3>
            <button
              @click="handleCancel"
              class="text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150"
              title="Close dialog"
            >
              <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        <!-- Content -->
        <div class="bg-white px-6 py-4">
          <!-- File Selection Section (shown before upload starts) -->
          <div v-if="!importQueueId && !processing" class="space-y-4">
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
                  <div class="flex items-center justify-center px-6 py-3 border-2 border-dashed border-gray-300 rounded-lg hover:border-blue-400 hover:bg-blue-50 transition-colors">
                    <div class="text-center">
                      <svg class="mx-auto h-12 w-12 text-gray-400" stroke="currentColor" fill="none" viewBox="0 0 48 48">
                        <path d="M28 8H12a4 4 0 00-4 4v20m32-12v8m0 0v8a4 4 0 01-4 4H12a4 4 0 01-4-4v-4m32-4l-3.172-3.172a4 4 0 00-5.656 0L28 28M8 32l9.172-9.172a4 4 0 015.656 0L28 28m0 0l4 4m4-4h-12m-2-5h9.172M17 13h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                      <p class="mt-2 text-sm text-gray-600">
                        <span class="font-medium text-blue-600 hover:text-blue-500">Click to browse</span> or drag and drop
                      </p>
                      <p class="mt-1 text-xs text-gray-500">KMZ, KML, or GPX files only</p>
                    </div>
                  </div>
                </label>
              </div>
            </div>

            <!-- Selected File Display (shown when file is selected) -->
            <div v-if="selectedFile" class="p-4 bg-blue-50 border border-blue-200 rounded-lg">
              <div class="flex items-center justify-between">
                <div class="flex items-center space-x-3 flex-1 min-w-0">
                  <svg class="h-5 w-5 text-blue-600 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                  </svg>
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
                  <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
            </div>

            <!-- Error Message -->
            <div v-if="errorMessage" class="p-3 bg-red-50 border border-red-200 rounded-md">
              <div class="flex">
                <svg class="h-5 w-5 text-red-400 mr-2" fill="currentColor" viewBox="0 0 20 20">
                  <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd" />
                </svg>
                <p class="text-sm text-red-800">{{ errorMessage }}</p>
              </div>
            </div>
          </div>

          <!-- Processing Section -->
          <div v-else-if="processing" class="space-y-4">
            <div class="text-center py-6">
              <div class="inline-block animate-spin rounded-full h-12 w-12 border-4 border-blue-200 border-t-blue-600"></div>
              <p class="mt-4 text-sm font-medium text-gray-900">{{ processingMessage }}</p>
              <div v-if="processingProgress !== null" class="mt-6 max-w-md mx-auto">
                <div class="w-full bg-gray-200 rounded-full h-3 overflow-hidden">
                  <div 
                    :style="{ width: processingProgress + '%' }" 
                    class="bg-blue-600 h-3 rounded-full transition-all duration-300 flex items-center justify-end pr-1"
                  >
                    <span v-if="processingProgress > 10" class="text-xs text-white font-medium">{{ Math.round(processingProgress) }}%</span>
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
                <svg class="h-5 w-5 text-yellow-400 mr-2 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                  <path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd" />
                </svg>
                <div>
                  <p class="text-sm font-medium text-yellow-800">No matching geometry types found</p>
                  <p class="text-xs text-yellow-700 mt-1">
                    The uploaded file contains {{ features.length }} feature{{ features.length !== 1 ? 's' : '' }}, 
                    but none match the geometry type of the existing feature ({{ existingFeatureGeometryType }}).
                    Only features with the same geometry type (Point/LineString/Polygon) can be used for replacement.
                  </p>
                </div>
              </div>
            </div>
            
            <!-- Features list -->
            <div v-else>
              <h4 class="text-sm font-medium text-gray-900 mb-3">
                Select a feature to apply its spatial data:
                <span v-if="features.length !== sortedFeatures.length" class="text-xs font-normal text-gray-500 ml-2">
                  ({{ sortedFeatures.length }} of {{ features.length }} matching geometry type)
                </span>
              </h4>
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
                        <svg class="w-4 h-4 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4" />
                        </svg>
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
                          <svg class="h-5 w-5 text-blue-600" fill="currentColor" viewBox="0 0 20 20">
                            <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
                          </svg>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- Error Message -->
            <div v-if="errorMessage" class="p-3 bg-red-50 border border-red-200 rounded-md">
              <p class="text-sm text-red-800">{{ errorMessage }}</p>
            </div>

            <!-- Success Message -->
            <div v-if="successMessage" class="p-3 bg-green-50 border border-green-200 rounded-md">
              <div class="flex">
                <svg class="h-5 w-5 text-green-400 mr-2" fill="currentColor" viewBox="0 0 20 20">
                  <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
                </svg>
                <p class="text-sm text-green-800">{{ successMessage }}</p>
              </div>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="bg-gray-50 px-6 py-4 border-t border-gray-200 flex justify-end space-x-3">
          <!-- Upload Button (shown when file is selected but not yet uploaded) -->
          <button
            v-if="selectedFile && !importQueueId && !processing"
            @click="handleUpload"
            class="px-4 py-2 text-sm font-medium text-white bg-blue-600 border border-transparent rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 flex items-center"
            title="Upload and process file"
          >
            <svg class="h-4 w-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
            </svg>
            Upload & Process
          </button>
          
          <!-- Cancel Button -->
          <button
            v-if="!importQueueId || (!processing && features.length === 0)"
            @click="handleCancel"
            class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            title="Cancel"
          >
            Cancel
          </button>
          
          <!-- Apply Button (always shown when features are available, disabled when not ready) -->
          <button
            v-if="sortedFeatures.length > 0 && !applied"
            @click="handleApply"
            :disabled="applying || selectedFeatureIndex === null"
            class="px-4 py-2 text-sm font-medium text-white bg-blue-600 border border-transparent rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-400 disabled:hover:bg-gray-400 flex items-center"
            title="Apply selected feature's spatial data"
          >
            <svg v-if="!applying" class="h-4 w-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
            </svg>
            <span v-if="applying" class="inline-block animate-spin rounded-full h-4 w-4 border-b-2 border-white mr-2"></span>
            {{ applying ? 'Applying...' : 'Apply Spatial Data' }}
          </button>
          
          <!-- Regenerate Tags Button -->
          <button
            v-if="applied"
            @click="handleRegenerateTags"
            :disabled="regeneratingTags"
            class="px-4 py-2 text-sm font-medium text-white bg-green-600 border border-transparent rounded-md hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 disabled:opacity-50 disabled:cursor-not-allowed flex items-center"
            title="Regenerate tags for this feature"
          >
            <svg v-if="!regeneratingTags" class="h-4 w-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            <span v-if="regeneratingTags" class="inline-block animate-spin rounded-full h-4 w-4 border-b-2 border-white mr-2"></span>
            {{ regeneratingTags ? 'Regenerating...' : 'Regenerate Tags' }}
          </button>
          
          <!-- Close Button -->
          <button
            v-if="applied"
            @click="handleClose"
            class="px-4 py-2 text-sm font-medium text-white bg-blue-600 border border-transparent rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            title="Close dialog"
          >
            Close
          </button>
        </div>
      </div>
    </div>

    <!-- Expanded Map Modal -->
    <div v-if="expandedMapIndex !== null" class="fixed inset-0 z-50 bg-black bg-opacity-75 flex items-center justify-center" @click="closeExpandedMap">
      <div class="bg-white rounded-lg shadow-xl w-full h-full md:w-4/5 md:h-4/5 m-4 relative" @click.stop>
        <!-- Header -->
        <div class="bg-white px-6 py-4 border-b border-gray-200 flex items-center justify-between">
          <h3 class="text-lg font-medium text-gray-900">
            {{ sortedFeatures[expandedMapIndex]?.properties?.name || `Feature ${expandedMapIndex + 1}` }} - Map Preview
          </h3>
          <button
            @click="closeExpandedMap"
            class="text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150"
            title="Close expanded map"
          >
            <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
        
        <!-- Expanded Map Container -->
        <div class="p-6 h-[calc(100%-73px)]">
          <div 
            :ref="el => setExpandedMapRef(el)"
            id="expanded-feature-map"
            class="w-full h-full border border-gray-300 rounded-md overflow-hidden"
          ></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {APIHOST} from '@/config.js'
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
      regeneratingTags: false,
      ws: null,
      wsConnected: false,
      pollingInterval: null,
      selectedFile: null,
      existingFeatureGeometryType: null,
      featureMaps: {}, // Store map instances by index
      expandedMapIndex: null, // Index of currently expanded map
      expandedMap: null // Expanded map instance
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
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        this.resetDialog()
        this.fetchExistingFeatureGeometryType()
      } else {
        this.cleanup()
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
    }
  },
  methods: {
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
      this.regeneratingTags = false
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
                stroke: new Stroke({ color: '#3b82f6', width: 3 })
              })
            } else if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
              return new Style({
                fill: new Fill({ color: 'rgba(59, 130, 246, 0.3)' }),
                stroke: new Stroke({ color: '#3b82f6', width: 2 })
              })
            }
            return new Style({
              stroke: new Stroke({ color: '#3b82f6', width: 2 }),
              fill: new Fill({ color: 'rgba(59, 130, 246, 0.3)' })
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
      this.$nextTick(() => {
        this.initializeExpandedMap()
      })
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
      if (el && this.expandedMapIndex !== null && !this.expandedMap) {
        this.$nextTick(() => {
          this.initializeExpandedMap()
        })
      }
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
                stroke: new Stroke({ color: '#3b82f6', width: 3 })
              })
            } else if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
              return new Style({
                fill: new Fill({ color: 'rgba(59, 130, 246, 0.3)' }),
                stroke: new Stroke({ color: '#3b82f6', width: 2 })
              })
            }
            return new Style({
              stroke: new Stroke({ color: '#3b82f6', width: 2 }),
              fill: new Fill({ color: 'rgba(59, 130, 246, 0.3)' })
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
        const response = await fetch(`${APIHOST}/api/data/feature/${this.featureId}/`, {
          credentials: 'include'
        })
        const data = await response.json()
        
        if (data.success && data.feature && data.feature.geojson) {
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

        const response = await fetch(`${APIHOST}/api/data/item/import/upload`, {
          method: 'POST',
          headers: {
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include',
          body: formData
        })

        const data = await response.json()

        if (!response.ok || !data.success) {
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
          const response = await fetch(`${APIHOST}/api/data/item/import/status/${this.jobId}`, {
            credentials: 'include'
          })
          const data = await response.json()

          if (data.success && data.job_status) {
            this.processingProgress = data.job_status.progress || 0
            this.processingMessage = data.job_status.message || 'Processing...'

            if (data.job_status.status === 'completed') {
              // Get the import queue ID from the job status
              if (data.job_status.import_queue_id) {
                this.importQueueId = data.job_status.import_queue_id
                clearInterval(this.pollingInterval)
                this.pollingInterval = null
                await this.fetchFeatures()
              } else {
                // Fallback: try to find it via polling
                await this.fetchImportQueueData()
                clearInterval(this.pollingInterval)
                this.pollingInterval = null
              }
            } else if (data.job_status.status === 'failed') {
              this.errorMessage = data.job_status.error_message || 'Processing failed'
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
    async fetchImportQueueData() {
      // Fallback: Poll the job status to get import_queue_id
      // This should rarely be needed as the job result should contain it
      let attempts = 0
      const maxAttempts = 10
      
      const pollForQueueItem = setInterval(async () => {
        attempts++
        if (attempts > maxAttempts) {
          clearInterval(pollForQueueItem)
          this.errorMessage = 'Timeout waiting for import queue item'
          this.processing = false
          return
        }

        try {
          if (!this.jobId) {
            clearInterval(pollForQueueItem)
            return
          }

          const response = await fetch(`${APIHOST}/api/data/item/import/status/${this.jobId}`, {
            credentials: 'include'
          })
          const data = await response.json()

          if (data.success && data.job_status && data.job_status.import_queue_id) {
            this.importQueueId = data.job_status.import_queue_id
            clearInterval(pollForQueueItem)
            await this.fetchFeatures()
          }
        } catch (error) {
          console.error('Error polling for import queue:', error)
        }
      }, 1000)
    },
    async fetchFeatures() {
      if (!this.importQueueId) return

      try {
        const response = await fetch(`${APIHOST}/api/data/item/import/get/features/${this.importQueueId}`, {
          credentials: 'include'
        })
        const data = await response.json()

        if (data.success && data.geofeatures) {
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
        const response = await fetch(`${APIHOST}/api/data/feature/${this.featureId}/apply-replacement/`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include',
          body: JSON.stringify({
            import_queue_id: this.importQueueId,
            feature_index: originalIndex
          })
        })

        const data = await response.json()

        if (!response.ok || !data.success) {
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
    async handleRegenerateTags() {
      this.regeneratingTags = true
      this.errorMessage = ''

      try {
        const response = await fetch(`${APIHOST}/api/data/feature/${this.featureId}/regenerate-tags/`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include'
        })

        const data = await response.json()

        if (!response.ok || !data.success) {
          this.errorMessage = data.error || 'Failed to regenerate tags'
          this.regeneratingTags = false
          return
        }

        this.successMessage = 'Tags regenerated successfully!'
        this.regeneratingTags = false
      } catch (error) {
        console.error('Error regenerating tags:', error)
        this.errorMessage = `Error: ${error.message}`
        this.regeneratingTags = false
      }
    },
    async handleCancel() {
      // Delete the ImportQueue row if it exists
      if (this.importQueueId) {
        try {
          await fetch(`${APIHOST}/api/data/item/import/delete/${this.importQueueId}`, {
            method: 'DELETE',
            headers: {
              'X-CSRFToken': this.getCsrfToken()
            },
            credentials: 'include'
          })
        } catch (error) {
          console.error('Error deleting import queue item:', error)
        }
      }
      this.handleClose()
    },
    handleClose() {
      this.cleanup()
      this.$emit('close')
    },
    handleBackdropMouseDown(event) {
      if (event.target === event.currentTarget) {
        this.handleCancel()
      }
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

