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
            <ChartBarIcon class="w-5 h-5" />
          </button>
          <button
              v-if="showDownloadButton"
              @click="$emit('download')"
              class="text-gray-400 hover:text-blue-500 transition-colors"
              title="Download KMZ"
          >
            <ArrowDownTrayIcon class="w-5 h-5" />
          </button>
          <button
            v-if="showEditButton"
            @click="$emit('edit')"
            class="text-gray-400 hover:text-blue-500 transition-colors"
            title="Edit feature"
          >
            <PencilIcon class="w-5 h-5" />
          </button>
          <button
            @click="$emit('zoom')"
            class="text-gray-400 hover:text-blue-500 transition-colors"
            title="Zoom to feature"
          >
            <MapPinIcon class="w-5 h-5" />
          </button>
          <button
            @click="$emit('close')"
            class="text-gray-400 hover:text-gray-600 transition-colors"
            title="Close"
          >
            <XMarkIcon class="w-5 h-5" />
          </button>
        </div>
      </div>

      <!-- Feature Type -->
      <div class="mb-4 text-sm text-gray-600 italic">
        {{ getFeatureGeometryType(feature) }}
      </div>

      <!-- Elevation (for Point/MultiPoint features) -->
      <div v-if="getFeatureElevation(feature) !== null" class="mb-4 bg-gray-100 border border-gray-300 rounded px-2 py-1.5 flex items-center space-x-2">
        <MeasurementIcon />
        <span class="text-xs font-semibold text-gray-900 uppercase tracking-wide">Elevation:</span>
        <span class="ml-1.5 text-sm text-gray-700">{{ formatElevation(getFeatureElevation(feature)) }}</span>
      </div>

      <!-- Length (for LineString/MultiLineString features) -->
      <div v-if="featureLength !== null" class="mb-4 bg-gray-100 border border-gray-300 rounded px-2 py-1.5 flex items-center space-x-2">
        <MeasurementIcon :rotation="90" />
        <span class="text-xs font-semibold text-gray-900 uppercase tracking-wide">Length:</span>
        <span class="ml-1.5 text-sm text-gray-700">{{ formatDistance(featureLength) }}</span>
      </div>

      <!-- Area (for Polygon/MultiPolygon features) -->
      <div v-if="featureArea !== null" class="mb-4 bg-gray-100 border border-gray-300 rounded px-2 py-1.5 flex items-center space-x-2">
        <!-- Area Icon (Custom Polygon) -->
        <svg class="w-4 h-4 flex-shrink-0 text-gray-600" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" d="M12 2l8 4v12l-6 4-3-5-7 2V6l8-4z" />
        </svg>
        <span class="text-xs font-semibold text-gray-900 uppercase tracking-wide">Area:</span>
        <span class="ml-1.5 text-sm text-gray-700">{{ formatArea(featureArea) }}</span>
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
import { getLength, getArea } from 'ol/sphere'
import { ChartBarIcon, ArrowDownTrayIcon, PencilIcon, MapPinIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import { formatElevation, formatDistance, formatArea } from '@/utils/units'
import MeasurementIcon from '@/components/icons/MeasurementIcon.vue'

export default {
  name: 'FeatureInfoBox',
  components: {
    ChartBarIcon,
    ArrowDownTrayIcon,
    PencilIcon,
    MapPinIcon,
    XMarkIcon,
    MeasurementIcon
  },
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
    },
    isPolygon() {
      if (!this.feature) return false
      const geometry = this.feature.getGeometry()
      if (!geometry) return false
      const geomType = geometry.getType()
      return geomType === 'Polygon' || geomType === 'MultiPolygon'
    },
    featureLength() {
      if (!this.isLineOrTrack) return null
      const geometry = this.feature.getGeometry()
      return getLength(geometry, { projection: 'EPSG:3857' })
    },
    featureArea() {
      if (!this.isPolygon) return null
      const geometry = this.feature.getGeometry()
      return getArea(geometry, { projection: 'EPSG:3857' })
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
      return formatElevation(elevationMeters)
    },
    formatDistance(distanceMeters) {
      return formatDistance(distanceMeters)
    },
    formatArea(areaSqMeters) {
      return formatArea(areaSqMeters)
    }
  }
}
</script>

