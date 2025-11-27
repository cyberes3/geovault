<template>
  <div v-if="feature" class="absolute bottom-4 right-4 bg-white rounded-lg shadow-xl border border-gray-200 z-10 max-w-md w-80">
    <div class="p-4">
      <!-- Header -->
      <div class="flex items-start justify-between">
        <h3 class="text-lg font-bold text-gray-900 pr-2">{{ getFeatureName(feature) }}</h3>
        <div class="flex items-center space-x-2 flex-shrink-0">
          <button
            v-if="isLineOrTrack"
            @click="$emit('show-profile')"
            class="text-gray-400 hover:text-blue-500 transition-colors"
            title="Show elevation profile"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"></path>
            </svg>
          </button>
          <button
              v-if="showDownloadButton"
              @click="$emit('download')"
              class="text-gray-400 hover:text-blue-500 transition-colors"
              title="Download KMZ"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">t
              <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1M12 4v10m0 0l-4-4m4 4l4-4"
              ></path>
            </svg>
          </button>
          <button
            v-if="showEditButton"
            @click="$emit('edit')"
            class="text-gray-400 hover:text-blue-500 transition-colors"
            title="Edit feature"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"></path>
            </svg>
          </button>
          <button
            @click="$emit('zoom')"
            class="text-gray-400 hover:text-blue-500 transition-colors"
            title="Zoom to feature"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"></path>
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 11a3 3 0 11-6 0 3 3 0 016 0z"></path>
            </svg>
          </button>
          <button
            @click="$emit('close')"
            class="text-gray-400 hover:text-gray-600 transition-colors"
            title="Close"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
            </svg>
          </button>
        </div>
      </div>

      <!-- Feature Type -->
      <div class="mb-4 text-sm text-gray-600 italic">
        {{ getFeatureGeometryType(feature) }}
      </div>

      <!-- Elevation (for Point/MultiPoint features) -->
      <div v-if="getFeatureElevation(feature) !== null" class="mb-4 bg-gray-100 border border-gray-300 rounded px-2 py-1.5 flex items-center space-x-2">
        <svg class="w-4 h-4 flex-shrink-0 text-gray-600" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor">
          <!-- Top horizontal bar -->
          <line x1="8" y1="2" x2="16" y2="2" stroke-linecap="round" />
          <!-- Upward arrow -->
          <path stroke-linecap="round" stroke-linejoin="round" d="M12 10V4m-3 3 3-3 3 3" />
          <!-- Center vertical line -->
          <line x1="12" y1="10" x2="12" y2="14" stroke-linecap="round" />
          <!-- Downward arrow -->
          <path stroke-linecap="round" stroke-linejoin="round" d="M12 14v6m-3-3 3 3 3-3" />
          <!-- Bottom horizontal bar -->
          <line x1="8" y1="22" x2="16" y2="22" stroke-linecap="round" />
        </svg>
        <span class="text-xs font-semibold text-gray-900 uppercase tracking-wide">Elevation:</span>
        <span class="ml-1.5 text-sm text-gray-700">{{ formatElevation(getFeatureElevation(feature)) }}</span>
      </div>

      <!-- Description -->
      <div v-if="getFeatureDescription(feature)" class="mb-4">
        <div class="text-sm text-gray-700 prose prose-sm max-w-none prose-headings:text-gray-900 prose-p:text-gray-700 prose-a:text-blue-500 prose-strong:text-gray-900 prose-ul:text-gray-700 prose-ol:text-gray-700" v-html="renderMarkdown(getFeatureDescription(feature))"></div>
      </div>

      <!-- Tags -->
      <div v-if="getFeatureTags(feature).userTags.length > 0 || getFeatureTags(feature).systemTags.length > 0" class="space-y-2">
        <!-- User Tags (Blue) -->
        <div v-if="getFeatureTags(feature).userTags.length > 0" class="flex flex-wrap gap-2">
          <span
            v-for="tag in getFeatureTags(feature).userTags"
            :key="`user-${tag}`"
            class="inline-flex items-center px-2 py-1 rounded-md text-xs font-medium bg-blue-100 text-blue-700"
          >
            {{ tag }}
          </span>
        </div>
        <!-- System Tags (Grey) -->
        <div v-if="getFeatureTags(feature).systemTags.length > 0" class="flex flex-wrap gap-2">
          <span
            v-for="tag in getFeatureTags(feature).systemTags"
            :key="`system-${tag}`"
            class="inline-flex items-center px-2 py-1 rounded-md text-xs font-medium bg-gray-200 text-gray-600"
          >
            {{ tag }}
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { marked } from 'marked'
import { GeoJSON } from 'ol/format'

export default {
  name: 'FeatureInfoBox',
  props: {
    feature: {
      type: Object,
      default: null
    },
    showDownloadButton: {
      type: Boolean,
      default: true
    },
    showEditButton: {
      type: Boolean,
      default: true
    },
    shareId: {
      type: String,
      default: null
    }
  },
  emits: ['close', 'edit', 'zoom', 'show-profile', 'download'],
  computed: {
    isLineOrTrack() {
      if (!this.feature) return false
      const geometry = this.feature.getGeometry()
      if (!geometry) return false
      const geomType = geometry.getType()
      return geomType === 'LineString' || geomType === 'MultiLineString'
    },
    isPointOrMultiPoint() {
      if (!this.feature) return false
      const geometry = this.feature.getGeometry()
      if (!geometry) return false
      const geomType = geometry.getType()
      return geomType === 'Point' || geomType === 'MultiPoint'
    }
  },
  methods: {
    getFeatureName(feature) {
      const properties = feature.get('properties') || {}
      return properties.name || 'Unnamed Feature'
    },
    getFeatureGeometryType(feature) {
      const geometry = feature.getGeometry()
      if (!geometry) return 'Unknown'
      return geometry.getType()
    },
    getFeatureDescription(feature) {
      const properties = feature.get('properties') || {}
      return properties.description || null
    },
    getFeatureTags(feature) {
      const properties = feature.get('properties') || {}
      const userTags = Array.isArray(properties.tags)
        ? properties.tags.filter(tag => tag && tag.trim() !== '')
        : []
      const systemTags = Array.isArray(properties.system_tags)
        ? properties.system_tags.filter(tag => tag && tag.trim() !== '')
        : []
      return { userTags, systemTags }
    },
    renderMarkdown(markdown) {
      if (!markdown) return ''
      return marked.parse(markdown)
    },
    getFeatureElevation(feature) {
      if (!feature) return null
      const geometry = feature.getGeometry()
      if (!geometry) return null

      const geomType = geometry.getType()

      // Only process Point and MultiPoint features
      if (geomType !== 'Point' && geomType !== 'MultiPoint') {
        return null
      }

      try {
        // Convert OpenLayers geometry to GeoJSON format
        const format = new GeoJSON()
        const geometryJson = format.writeGeometryObject(geometry, {
          featureProjection: 'EPSG:3857',
          dataProjection: 'EPSG:4326'
        })

        const coords = geometryJson.coordinates

        if (geomType === 'Point') {
          // Point: coordinates is [lon, lat] or [lon, lat, elevation]
          if (Array.isArray(coords) && coords.length >= 3) {
            const elevation = coords[2]
            if (elevation != null && elevation !== 0) {
              return elevation // Elevation is in meters
            }
          }
        } else if (geomType === 'MultiPoint') {
          // MultiPoint: coordinates is [[lon, lat], ...] or [[lon, lat, elevation], ...]
          // For MultiPoint, we'll use the first point's elevation
          if (Array.isArray(coords) && coords.length > 0) {
            const firstPoint = coords[0]
            if (Array.isArray(firstPoint) && firstPoint.length >= 3) {
              const elevation = firstPoint[2]
              if (elevation != null && elevation !== 0) {
                return elevation // Elevation is in meters
              }
            }
          }
        }
      } catch (error) {
        console.error('Error extracting elevation from feature:', error)
      }

      return null
    },
    formatElevation(elevationMeters) {
      if (elevationMeters == null) return 'N/A'
      // Convert meters to feet (1 meter = 3.28084 feet)
      const elevationFeet = elevationMeters * 3.28084
      // Format to 1 decimal place
      return `${elevationFeet.toFixed(1)} ft`
    }
  }
}
</script>

