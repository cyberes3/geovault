<template>
  <div v-if="feature" class="fixed bottom-0 left-0 right-0 w-full bg-white z-20 rounded-t-xl shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.1)] md:absolute md:bottom-4 md:right-4 md:left-auto md:max-w-md md:w-80 md:rounded-lg md:border-r md:border-b md:border-l md:border-gray-200 md:shadow-xl max-h-[60vh] flex flex-col" :style="{ borderTopWidth: '4px', borderTopColor: getFeatureColor(), borderTopStyle: 'solid' }">
    <div class="p-3 md:p-4 overflow-y-auto">
      <!-- Header -->
      <div class="flex items-start justify-between mb-2 md:mb-4">
        <h3 class="text-base md:text-lg font-bold text-gray-900 pr-2 truncate">{{ getFeatureName(feature) }}</h3>
        <div class="flex items-center space-x-1 md:space-x-2 flex-shrink-0">
          <button
            v-if="showEditButton"
            @click="$emit('edit')"
            class="p-1 text-gray-400 hover:text-blue-500 transition-colors"
            title="Edit feature"
          >
            <PencilSquareIcon class="w-5 h-5" />
          </button>
          <button
            v-if="isLineOrTrack"
            @click="$emit('show-profile')"
            class="p-1 text-gray-400 hover:text-blue-500 transition-colors"
            title="Show elevation profile"
          >
            <ChartBarIcon class="w-5 h-5" />
          </button>
          <button
              v-if="showDownloadButton"
              @click="$emit('download')"
              class="p-1 text-gray-400 hover:text-blue-500 transition-colors"
              title="Download KMZ"
          >
            <ArrowDownTrayIcon class="w-5 h-5" />
          </button>
          <button
            @click="$emit('zoom')"
            class="p-1 text-gray-400 hover:text-blue-500 transition-colors"
            title="Zoom to feature"
          >
            <MapPinIcon class="w-5 h-5" />
          </button>
          <button
            @click="$emit('close')"
            class="p-1 text-gray-400 hover:text-gray-600 transition-colors"
            title="Close"
          >
            <XMarkIcon class="w-5 h-5" />
          </button>
        </div>
      </div>

      <!-- Stats Row (Mobile) / Stacked (Desktop) -->
      <div class="flex flex-wrap items-center gap-2 text-sm text-gray-600 italic mb-2 md:mb-4">
        <!-- Elevation (for Point/MultiPoint features) -->
        <div v-if="getFeatureElevation(feature) !== null" class="flex items-center space-x-1 bg-gray-100 border border-gray-300 rounded px-1.5 py-0.5 md:px-2 md:py-1.5" title="Elevation">
          <MeasurementIcon class="w-3 h-3 md:w-4 md:h-4" />
          <span class="text-xs text-gray-700">{{ formatElevation(getFeatureElevation(feature)) }}</span>
        </div>

        <!-- Length (for LineString/MultiLineString features) -->
        <div v-if="featureLength !== null" class="flex items-center space-x-1 bg-gray-100 border border-gray-300 rounded px-1.5 py-0.5 md:px-2 md:py-1.5" title="Length">
          <MeasurementIcon :rotation="90" class="w-3 h-3 md:w-4 md:h-4" />
          <span class="text-xs text-gray-700">{{ formatDistance(featureLength) }}</span>
        </div>

        <!-- Area (for Polygon/MultiPolygon features) -->
        <div v-if="featureArea !== null" class="flex items-center space-x-1 bg-gray-100 border border-gray-300 rounded px-1.5 py-0.5 md:px-2 md:py-1.5" title="Area">
          <AreaIcon />
          <span class="text-xs text-gray-700">{{ formatArea(featureArea) }}</span>
        </div>

        <!-- Created Date -->
        <div v-if="getFeatureCreatedDate(feature) !== null" class="flex items-center space-x-1 bg-gray-100 border border-gray-300 rounded px-1.5 py-0.5 md:px-2 md:py-1.5" title="Created Date">
          <CalendarDaysIcon class="w-3 h-3 md:w-4 md:h-4" />
          <span class="text-xs text-gray-700">{{ formatCreatedDate(getFeatureCreatedDate(feature)) }}</span>
        </div>
      </div>

      <!-- Description -->
      <div v-if="getFeatureDescription(feature)" class="mb-3 md:mb-4 max-h-20 md:max-h-none overflow-y-auto">
        <div class="text-xs md:text-sm text-gray-700 prose prose-sm max-w-none prose-headings:text-gray-900 prose-p:text-gray-700 prose-a:text-blue-500 prose-strong:text-gray-900 prose-ul:text-gray-700 prose-ol:text-gray-700" v-html="renderMarkdown(getFeatureDescription(feature))"></div>
      </div>

      <!-- User Tags -->
      <div v-if="getFeatureTags(feature).userTags.length > 0" class="mb-2 md:mb-3 flex flex-wrap gap-1.5 md:gap-2">
        <span
          v-for="tag in getFeatureTags(feature).userTags"
          :key="`user-${tag}`"
          class="inline-flex items-center px-1.5 py-0.5 md:px-2 md:py-1 rounded-md text-[10px] md:text-xs font-medium bg-blue-100 text-blue-700"
        >
          {{ tag }}
        </span>
      </div>

      <!-- System Tags (Fixed Size Box) -->
      <div v-if="getFeatureTags(feature).systemTags.length > 0" class="mb-2 md:mb-3">
        <div class="text-[10px] md:text-xs text-gray-500 mb-1">System Tags</div>
        <div class="border border-gray-200 rounded-md bg-gray-50 overflow-hidden">
          <div class="h-20 md:h-24 overflow-y-auto p-2">
            <div class="flex flex-wrap gap-1.5 md:gap-2">
              <span
                v-for="tag in getFeatureTags(feature).systemTags"
                :key="`system-${tag}`"
                class="inline-flex items-center px-1.5 py-0.5 md:px-2 md:py-1 rounded-md text-[10px] md:text-xs font-medium bg-gray-200 text-gray-600"
              >
                {{ tag }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { marked } from 'marked'
import { GeoJSON } from 'ol/format'
import { getLength, getArea } from 'ol/sphere'
import { ChartBarIcon, ArrowDownTrayIcon, PencilSquareIcon, MapPinIcon, XMarkIcon, CalendarDaysIcon } from '@heroicons/vue/24/outline'
import { formatElevation, formatDistance, formatArea } from '@/utils/units'
import MeasurementIcon from '@/components/icons/MeasurementIcon.vue'
import AreaIcon from '@/components/icons/AreaIcon.vue'
import { getGeometryTypeColor } from '@/utils/geometryColors.js'

export default {
  name: 'FeatureInfoBox',
  components: {
    ChartBarIcon,
    ArrowDownTrayIcon,
    PencilSquareIcon,
    MapPinIcon,
    XMarkIcon,
    CalendarDaysIcon,
    MeasurementIcon,
    AreaIcon
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
    },
    getFeatureColor() {
      if (!this.feature) return '#d1d5db'
      const geometry = this.feature.getGeometry()
      if (!geometry) return '#d1d5db'
      const geometryType = geometry.getType()
      return getGeometryTypeColor(geometryType)
    },
    getFeatureCreatedDate(feature) {
      if (!feature) return null
      const properties = feature.get('properties') || {}
      return properties.created || null
    },
    formatCreatedDate(dateString) {
      if (!dateString) return ''
      try {
        const date = new Date(dateString)
        if (isNaN(date.getTime())) return ''
        // Format in local timezone with short format
        const dateStr = date.toLocaleDateString(undefined, {
          year: 'numeric',
          month: 'short',
          day: 'numeric'
        })
        const timeStr = date.toLocaleTimeString(undefined, {
          hour: '2-digit',
          minute: '2-digit'
        })
        return `${dateStr} ${timeStr}`
      } catch (error) {
        console.error('Error formatting created date:', error)
        return ''
      }
    }
  }
}
</script>

