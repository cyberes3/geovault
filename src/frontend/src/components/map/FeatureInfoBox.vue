<template>
  <div v-if="feature" class="fixed bottom-0 left-0 right-0 w-full bg-white z-20 rounded-t-xl shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.1)] md:absolute md:bottom-16 md:right-0 md:left-auto md:max-w-md md:w-80 md:rounded-lg md:border-l md:border-b md:border-gray-200 md:shadow-xl lg:bottom-0 lg:right-4 lg:rounded-lg lg:border-r lg:border-b lg:border-l max-h-[60vh] md:max-h-[calc(100vh-12rem)] flex flex-col" :style="{ borderTopWidth: '4px', borderTopColor: getFeatureColor(), borderTopStyle: 'solid' }">
    <div class="p-3 md:p-4 overflow-y-auto flex-1 min-h-0">
      <!-- Header -->
      <div class="flex items-start justify-between mb-2 md:mb-4 relative">
        <div
          class="text-base md:text-lg font-bold pr-2 flex-1 min-w-0"
          :class="getFeatureName(feature) ? 'text-gray-900' : 'text-gray-500 italic'"
          @mouseenter="handleNameHover"
          @mouseleave="handleNameLeave"
          @touchstart="handleNameTouchStart"
          @touchend="handleNameTouchEnd"
          ref="nameContainer"
        >
          <div
            class="ticker-container overflow-hidden whitespace-nowrap"
          >
            <span
              ref="nameElement"
              class="ticker-content inline-block"
              :class="{ 'ticker-scrolling': shouldScroll }"
            >
              <span class="ticker-item">{{ displayName(feature) }}</span>
              <span v-if="shouldScroll" class="ticker-item">{{ displayName(feature) }}</span>
            </span>
          </div>
        </div>
        <!-- Custom Tooltip (moved outside to avoid overflow clipping) -->
        <div
          v-if="showTooltip && shouldScroll"
          class="custom-tooltip"
          :style="tooltipStyle"
        >
          {{ displayName(feature) }}
        </div>
        <button
          @click="$emit('close')"
          class="p-1 text-gray-400 hover:text-gray-600 transition-colors flex-shrink-0"
          title="Close"
        >
          <XMarkIcon class="w-6 h-6 md:w-5 md:h-5" style="stroke-width: 2.5" />
        </button>
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
          <span class="text-xs text-gray-700">{{ formatDate(getFeatureCreatedDate(feature)) }}</span>
        </div>
      </div>

      <!-- Description -->
      <div v-if="getFeatureDescription(feature)" class="mb-3 md:mb-4 max-h-20 md:max-h-none overflow-y-auto">
        <div class="text-xs md:text-sm text-gray-700 prose prose-sm max-w-none prose-headings:text-gray-900 prose-p:text-gray-700 prose-a:text-blue-500 prose-strong:text-gray-900 prose-ul:text-gray-700 prose-ol:text-gray-700" v-html="renderMarkdown(getFeatureDescription(feature))"></div>
      </div>

      <!-- User Tags -->
      <div v-if="getFeatureTags(feature).userTags.length > 0" class="mb-1 md:mb-1.5 flex flex-wrap gap-1.5 md:gap-2">
        <span
          v-for="tag in getFeatureTags(feature).userTags"
          :key="`user-${tag}`"
          class="inline-flex items-center px-1.5 py-0.5 md:px-2 md:py-1 rounded-md text-[10px] md:text-xs font-medium bg-blue-100 text-blue-700"
        >
          {{ tag }}
        </span>
      </div>

      <!-- System Tags (Fixed Size Box) -->
      <div v-if="getFeatureTags(feature).systemTags.length > 0" class="mb-1 md:mb-1.5">
<!--        <div class="text-[10px] md:text-xs text-gray-500 mb-1">System Tags</div>-->
        <div class="border border-gray-200 rounded-md bg-gray-50 overflow-hidden">
          <div class="max-h-24 md:max-h-28 overflow-y-auto p-1">
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

    <!-- Action Buttons Bar (Bottom) -->
    <div class="border-t border-gray-200 bg-gray-50 px-2 md:px-3 py-1.5 md:py-2 flex items-center justify-center gap-1.5 md:gap-2 flex-none">
      <button
        v-if="showEditButton"
        @click="$emit('edit')"
        class="p-1.5 md:p-2 text-gray-500 bg-white border border-gray-300 rounded-md hover:bg-blue-50 hover:text-blue-600 hover:border-blue-300 transition-all duration-200 shadow-sm hover:shadow"
        title="Edit Feature"
      >
        <PencilSquareIcon class="w-6 h-6 md:w-5 md:h-5" />
      </button>
      <button
        v-if="isLineOrTrack"
        @click="$emit('show-profile')"
        class="p-1.5 md:p-2 text-gray-500 bg-white border border-gray-300 rounded-md hover:bg-blue-50 hover:text-blue-600 hover:border-blue-300 transition-all duration-200 shadow-sm hover:shadow"
        title="Show Elevation Profile"
      >
        <ChartBarIcon class="w-6 h-6 md:w-5 md:h-5" />
      </button>
      <button
        v-if="showDownloadButton"
        @click="$emit('download')"
        class="p-1.5 md:p-2 text-gray-500 bg-white border border-gray-300 rounded-md hover:bg-blue-50 hover:text-blue-600 hover:border-blue-300 transition-all duration-200 shadow-sm hover:shadow"
        title="Download KMZ"
      >
        <ArrowDownTrayIcon class="w-6 h-6 md:w-5 md:h-5" />
      </button>
      <button
        v-if="showShareButton"
        @click="$emit('share')"
        class="p-1.5 md:p-2 text-gray-500 bg-white border border-gray-300 rounded-md hover:bg-blue-50 hover:text-blue-600 hover:border-blue-300 transition-all duration-200 shadow-sm hover:shadow"
        title="Share Feature"
      >
        <ShareIcon class="w-6 h-6 md:w-5 md:h-5" />
      </button>
      <button
        @click="$emit('zoom')"
        class="p-1.5 md:p-2 text-gray-500 bg-white border border-gray-300 rounded-md hover:bg-blue-50 hover:text-blue-600 hover:border-blue-300 transition-all duration-200 shadow-sm hover:shadow"
        title="Zoom to Feature"
      >
        <MapPinIcon class="w-6 h-6 md:w-5 md:h-5" />
      </button>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import { marked } from 'marked'
import { length } from '@turf/length'
import { area } from '@turf/area'
import { feature as turfFeature } from '@turf/helpers'
import { ChartBarIcon, ArrowDownTrayIcon, PencilSquareIcon, MapPinIcon, XMarkIcon, CalendarDaysIcon, ShareIcon } from '@heroicons/vue/24/outline'
import { formatElevation, formatDistance, formatArea } from '@/utils/units'
import { formatDate } from '@/utils/dateUtils'
import MeasurementIcon from '@/components/icons/MeasurementIcon.vue'
import AreaIcon from '@/components/icons/AreaIcon.vue'
import { getGeometryTypeColor } from '@/utils/geometryColors.js'
import { sortTagsByPriority, sortUserTagsAlphabetically } from '@/utils/tagUtils.js'
import type { MapPageFeature } from '@/composables/mapPageTypes'

export default defineComponent({
  name: 'FeatureInfoBox',
  components: {
    ChartBarIcon,
    ArrowDownTrayIcon,
    PencilSquareIcon,
    MapPinIcon,
    XMarkIcon,
    CalendarDaysIcon,
    ShareIcon,
    MeasurementIcon,
    AreaIcon
  },
  props: {
    feature: {
      type: Object as PropType<MapPageFeature | null>,
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
    showShareButton: {
      type: Boolean,
      default: true
    },
    shareId: {
      type: String,
      default: null
    }
  },
  emits: ['close', 'edit', 'zoom', 'show-profile', 'download', 'share'],
  data() {
    return {
      shouldScroll: false,
      showTooltip: false,
      tooltipStyle: {} as Record<string, string>,
      touchTimeout: null as ReturnType<typeof setTimeout> | null
    }
  },
  computed: {
    isLineOrTrack(): boolean {
      if (!this.feature) return false
      // Pure GeoJSON features only
      const geomType = this.feature.geometry.type
      return geomType === 'LineString' || geomType === 'MultiLineString'
    },
    isPointOrMultiPoint(): boolean {
      if (!this.feature) return false
      // Pure GeoJSON features only
      const geomType = this.feature.geometry.type
      return geomType === 'Point' || geomType === 'MultiPoint'
    },
    isPolygon(): boolean {
      if (!this.feature) return false
      // Pure GeoJSON features only
      const geomType = this.feature.geometry.type
      return geomType === 'Polygon' || geomType === 'MultiPolygon'
    },
    featureLength(): number | null {
      if (!this.isLineOrTrack || !this.feature) return null
      try {
        // Use Turf.js to calculate length in meters
        return length(turfFeature(this.feature.geometry), { units: 'meters' })
      } catch (error) {
        console.error('Error calculating feature length:', error)
        return null
      }
    },
    featureArea(): number | null {
      if (!this.isPolygon || !this.feature) return null
      try {
        // Use Turf.js to calculate area in square meters
        return area(this.feature.geometry)
      } catch (error) {
        console.error('Error calculating feature area:', error)
        return null
      }
    }
  },
  mounted() {
    this.checkNameOverflow()
  },
  updated() {
    this.checkNameOverflow()
  },
  beforeUnmount() {
    if (this.touchTimeout) {
      clearTimeout(this.touchTimeout)
    }
  },
  methods: {
    checkNameOverflow() {
      void this.$nextTick(() => {
        const nameElement = this.$refs.nameElement as HTMLElement | undefined
        const nameContainer = this.$refs.nameContainer as HTMLElement | undefined
        if (nameElement && nameContainer) {
          // Get the width of one instance of the text
          const nameItem = nameElement.querySelector<HTMLElement>('.ticker-item')
          if (nameItem) {
            const nameWidth = nameItem.offsetWidth
            const containerWidth = nameContainer.offsetWidth
            this.shouldScroll = nameWidth > containerWidth

            // Set CSS variable for animation duration based on text length
            if (this.shouldScroll) {
              const duration = Math.max(8, nameWidth / 30) // ~30px per second (slower)
              nameElement.style.setProperty('--ticker-duration', `${duration}s`)
            }
          }
        }
      })
    },
    handleNameHover() {
      if (this.shouldScroll) {
        this.showTooltip = true
        this.updateTooltipPosition()
      }
    },
    handleNameLeave() {
      this.showTooltip = false
    },
    handleNameTouchStart() {
      if (this.shouldScroll) {
        this.showTooltip = true
        // Hide tooltip after 3 seconds on mobile
        this.touchTimeout = setTimeout(() => {
          this.showTooltip = false
        }, 3000)
      }
    },
    handleNameTouchEnd() {
      // Don't hide immediately to give user time to read
    },
    updateTooltipPosition() {
      const container = this.$refs.nameContainer as HTMLElement | undefined
      if (container) {
        const rect = container.getBoundingClientRect()
        // Position relative to the name container
        this.tooltipStyle = {
          left: `${rect.left + rect.width / 2}px`,
          top: `${rect.top - 8}px`,
          transform: 'translate(-50%, -100%)',
          position: 'fixed'
        }
      }
    },
    getFeatureName(feature: MapPageFeature): string {
      // Pure GeoJSON features only
      const properties = feature.properties
      return (properties.name as string | undefined) ?? ''
    },
    displayName(feature: MapPageFeature): string {
      // Return the feature name or 'Untitled Feature' for display
      const name = this.getFeatureName(feature)
      return name || 'Untitled Feature'
    },
    getFeatureGeometryType(feature: MapPageFeature): string {
      // Pure GeoJSON features only
      return feature.geometry.type
    },
    getFeatureDescription(feature: MapPageFeature): string | null {
      // Pure GeoJSON features only
      const properties = feature.properties
      return (properties.description as string | undefined) ?? null
    },
    getFeatureTags(feature: MapPageFeature): { userTags: string[]; systemTags: string[] } {
      // Pure GeoJSON features only
      const properties = feature.properties
      const userTags: string[] = Array.isArray(properties.tags)
        ? (properties.tags as unknown[]).filter((tag): tag is string => typeof tag === 'string' && tag.trim() !== '')
        : []
      const systemTags: string[] = Array.isArray(properties.system_tags)
        ? (properties.system_tags as unknown[]).filter((tag): tag is string => typeof tag === 'string' && tag.trim() !== '')
        : []
      // Sort system tags by priority, user tags alphabetically
      return {
        userTags: sortUserTagsAlphabetically(userTags),
        systemTags: sortTagsByPriority(systemTags)
      }
    },
    renderMarkdown(markdown: string | null): string {
      if (!markdown) return ''
      return marked.parse(markdown, { async: false })
    },
    getFeatureElevation(feature: MapPageFeature | null): number | null {
      if (!feature) return null
      
      // First, check if elevation is stored as _elevation property (preserved from coordinates)
      const properties = feature.properties
      if (properties._elevation != null) {
        return properties._elevation as number // Elevation is in meters
      }
      
      // Second, check if elevation is stored as a regular property (from tags)
      if (properties.elevation != null) {
        const elevation = parseFloat(properties.elevation as string)
        if (!isNaN(elevation)) {
          return elevation // Elevation is in meters
        }
      }
      
      // Otherwise, check geometry coordinates (fallback, may not work with MapLibre v5)
      const geometry = feature.geometry
      const geomType = geometry.type

      // Only process Point and MultiPoint features
      if (geomType !== 'Point' && geomType !== 'MultiPoint') {
        return null
      }

      try {
        const coords = geometry.coordinates as number[] | number[][]

        if (geomType === 'Point') {
          // Point: coordinates is [lon, lat] or [lon, lat, elevation]
          if (Array.isArray(coords) && coords.length >= 3) {
            return (coords as number[])[2] // Elevation is in meters
          }
        } else {
          // MultiPoint: coordinates is [[lon, lat], ...] or [[lon, lat, elevation], ...]
          if (Array.isArray(coords) && coords.length > 0) {
            const firstPoint = (coords as number[][])[0]
            if (Array.isArray(firstPoint) && firstPoint.length >= 3) {
              return firstPoint[2] // Elevation is in meters
            }
          }
        }
      } catch (error) {
        console.error('Error extracting elevation from feature:', error)
      }

      return null
    },
    formatElevation(elevationMeters: number | null): string {
      return formatElevation(elevationMeters)
    },
    formatDistance(distanceMeters: number | null): string {
      return formatDistance(distanceMeters)
    },
    formatArea(areaSqMeters: number | null): string {
      return formatArea(areaSqMeters)
    },
    getFeatureColor(): string {
      if (!this.feature) return '#d1d5db'
      // Pure GeoJSON features only
      return getGeometryTypeColor(this.feature.geometry.type)
    },
    getFeatureCreatedDate(feature: MapPageFeature | null): string | null {
      if (!feature) return null
      // Pure GeoJSON features only
      return (feature.properties.created as string | undefined) ?? null
    },
    formatDate
  }
})
</script>

<style scoped>
.ticker-container {
  position: relative;
}

.ticker-content {
  display: inline-block;
  white-space: nowrap;
}

.ticker-item {
  display: inline-block;
  padding-right: 2rem;
}

.ticker-scrolling {
  animation: ticker-scroll var(--ticker-duration, 8s) linear infinite;
}

@keyframes ticker-scroll {
  0% {
    transform: translateX(0);
  }
  100% {
    transform: translateX(-50%);
  }
}

/* Pause animation on hover */
.ticker-container:hover .ticker-scrolling {
  animation-play-state: paused;
}

/* Custom Tooltip */
.custom-tooltip {
  position: fixed;
  z-index: 9999;
  background-color: rgba(0, 0, 0, 0.9);
  color: white;
  padding: 0.375rem 0.5rem;
  border-radius: 0.25rem;
  font-size: 0.8125rem;
  font-weight: 500;
  line-height: 1.2;
  white-space: normal;
  word-wrap: break-word;
  max-width: 300px;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
  pointer-events: none;
}

/* Tooltip arrow */
.custom-tooltip::after {
  content: '';
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  border: 5px solid transparent;
  border-top-color: rgba(0, 0, 0, 0.9);
}

/* Mobile adjustments */
@media (max-width: 768px) {
  .custom-tooltip {
    max-width: calc(100vw - 2rem);
  }
}
</style>
