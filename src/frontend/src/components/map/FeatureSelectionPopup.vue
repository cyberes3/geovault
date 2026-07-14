<template>
  <div
      v-if="visible"
      ref="popupElement"
      :style="popupStyle"
      class="absolute bg-white rounded-lg shadow-xl border border-gray-200 z-20 w-max max-w-[200px]"
  >
    <div class="p-2">
      <!-- Feature List -->
      <div class="space-y-0.5 max-h-48 overflow-y-auto">
        <button
            v-for="(feature, index) in sortedFeatures"
            :key="getFeatureKey(feature, index)"
            class="w-full text-left flex items-center gap-2 py-2 px-2 sm:py-1 sm:px-1.5 rounded-md hover:bg-gray-50 transition-colors min-h-[44px] sm:min-h-0 text-xs text-gray-900 hover:text-blue-500"
            :style="{ borderLeft: `3px solid ${getGeometryTypeColor(feature)}` }"
            @click="$emit('select', feature)"
            title="Select This Feature"
        >
          <span class="font-medium truncate">
            {{ getFeatureName(feature) }}
          </span>
        </button>
      </div>
    </div>
    <!-- Arrow (positioned dynamically based on popup position) -->
    <div :class="arrowClass" :style="arrowStyle"></div>
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import { getGeometryTypeColor } from '@/utils/geometryColors.js'
import { formatGeometryTypeForDisplay } from '@/utils/geometryTypeFormatter.js'
import type { FeatureLike } from 'ol/Feature'
import type { MapPageFeature } from '@/composables/mapPageTypes'

/** This popup historically supported both OpenLayers `Feature`s and plain GeoJSON features; only the GeoJSON path is exercised today (see `MapPage.vue`), but the dual-mode branches are kept intact. */
type PopupFeature = MapPageFeature | FeatureLike

interface PopupPosition {
  x: number;
  y: number;
  containerWidth: number;
  containerHeight: number;
}

function isOlFeature(feature: PopupFeature): feature is FeatureLike {
  return typeof (feature as FeatureLike).getGeometry === 'function'
}

export default defineComponent({
  name: 'FeatureSelectionPopup',
  props: {
    features: {
      type: Array as PropType<PopupFeature[]>,
      default: () => []
    },
    position: {
      type: Object as PropType<PopupPosition>,
      default: () => ({x: 0, y: 0, containerWidth: 0, containerHeight: 0})
    },
    visible: {
      type: Boolean,
      default: false
    }
  },
  emits: ['select', 'close'],
  computed: {
    sortedFeatures(): PopupFeature[] {
      // Sort features by geometry type: Points -> Lines -> Polygons
      // Within each group, preserve the original order
      const getGeometryTypeSortOrder = (feature: PopupFeature): number => {
        let geomType: string | undefined
        if (isOlFeature(feature)) {
          // OpenLayers Feature
          const geometry = feature.getGeometry()
          if (!geometry) return 999
          geomType = geometry.getType()
        } else {
          // Plain GeoJSON
          geomType = feature.geometry.type
        }

        // Points first (order 1)
        if (geomType === 'Point' || geomType === 'MultiPoint') {
          return 1
        }
        // Lines second (order 2)
        if (geomType === 'LineString' || geomType === 'MultiLineString') {
          return 2
        }
        // Polygons third (order 3)
        if (geomType === 'Polygon' || geomType === 'MultiPolygon') {
          return 3
        }
        return 999
      }
      
      return [...this.features].sort((a, b) => {
        return getGeometryTypeSortOrder(a) - getGeometryTypeSortOrder(b)
      })
    },
    popupDimensions(): { width: number; height: number; halfWidth: number } {
      // Estimate popup dimensions
      // Max width is 200px (max-w-[200px])
      // Each feature button is approximately 44px tall (min-h-[44px])
      // Plus padding (p-2 = 8px top and bottom = 16px total)
      // Plus arrow space (approx 10px)
      const estimatedWidth = 200 // max-w-[200px]
      const featureHeight = 44 // min-h-[44px] for each feature
      const padding = 16 // p-2 = 8px top + 8px bottom
      const arrowSpace = 10 // space for arrow
      const maxVisibleFeatures = Math.min(this.sortedFeatures.length, 4) // max-h-48 allows ~4 features
      const estimatedHeight = (featureHeight * maxVisibleFeatures) + padding + arrowSpace
      
      return {
        width: estimatedWidth,
        height: estimatedHeight,
        halfWidth: estimatedWidth / 2
      }
    },
    arrowPosition(): 'top' | 'bottom' {
      // Determine if arrow should be at top or bottom
      const y = this.position.y
      const { height } = this.popupDimensions
      
      // Check if popup is positioned above or below
      const popupTop = y - height - 10
      return popupTop < 0 ? 'top' : 'bottom'
    },
    popupStyle(): Record<string, string> {
      // Position the popup at the tap/click location
      // Coordinates are relative to the map container
      const x = this.position.x
      const y = this.position.y
      const containerWidth = this.position.containerWidth
      const containerHeight = this.position.containerHeight
      const { height, halfWidth } = this.popupDimensions

      // Calculate popup bounds if positioned above (default)
      const popupLeft = x - halfWidth
      const popupRight = x + halfWidth
      const popupTop = y - height - 10 // 10px offset for arrow
      const popupBottom = y - 10

      // Check if we need to adjust horizontal position
      let left = x
      let transformX = '-50%'
      
      if (popupLeft < 0) {
        // Popup would overflow left, align to left edge with padding
        left = halfWidth + 10 // 10px padding from edge
        transformX = '0'
      } else if (popupRight > containerWidth) {
        // Popup would overflow right, align to right edge with padding
        left = containerWidth - halfWidth - 10 // 10px padding from edge
        transformX = '0'
      }

      // Check if we need to flip vertically (show below instead of above)
      let top = y - 10
      let transformY = '-100%'
      
      if (popupTop < 0) {
        // Not enough space above, show below instead
        top = y + 10 // 10px offset for arrow
        transformY = '0'
      } else if (popupBottom > containerHeight) {
        // Popup would overflow bottom, adjust to fit
        top = containerHeight - height - 10
        transformY = '0'
      }

      return {
        left: `${left}px`,
        top: `${top}px`,
        transform: `translate(${transformX}, ${transformY})`
      }
    },
    arrowClass(): string {
      const baseClass = 'absolute left-1/2 transform -translate-x-1/2 w-4 h-4 bg-white border-b border-r border-gray-200 z-10'
      if (this.arrowPosition === 'top') {
        // Arrow at top pointing up - rotate 225deg (45 + 180) to point up
        return `${baseClass} rotate-[225deg] -top-2`
      } else {
        // Arrow at bottom pointing down - rotate 45deg to point down
        return `${baseClass} rotate-45 -bottom-2`
      }
    },
    arrowStyle(): Record<string, string> {
      // Arrow doesn't need special styling, class handles it
      return {}
    }
  },
  watch: {
    $route() {
      // Close popup when route changes
      if (this.visible) {
        this.$emit('close')
      }
    }
  },
  methods: {
    getFeatureKey(feature: PopupFeature, index: number): string {
      // Generate a unique key for each feature
      let properties: Record<string, unknown>
      if (isOlFeature(feature)) {
        // OpenLayers Feature
        properties = (feature.get('properties') as Record<string, unknown> | undefined) ?? {}
      } else {
        // Plain GeoJSON
        properties = feature.properties
      }
      
      // Use feature ID if available, otherwise use geometry + index
      const databaseId = properties.database_id as string | number | undefined
      if (databaseId) {
        return `feature_${databaseId}`
      }
      
      // Fallback: use geometry type and index
      let geomType: string
      if (isOlFeature(feature)) {
        // OpenLayers Feature
        const geometry = feature.getGeometry()
        geomType = geometry ? geometry.getType() : 'unknown'
      } else {
        // Plain GeoJSON
        geomType = feature.geometry.type
      }
      return `feature_${geomType}_${index}`
    },
    getFeatureName(feature: PopupFeature): string {
      // Support both OpenLayers Features and plain GeoJSON
      let properties: Record<string, unknown>
      if (isOlFeature(feature)) {
        // OpenLayers Feature
        properties = (feature.get('properties') as Record<string, unknown> | undefined) ?? {}
      } else {
        // Plain GeoJSON
        properties = feature.properties
      }
      return (properties.name as string | undefined) ?? ''
    },
    getFeatureGeometryType(feature: PopupFeature): string {
      // Support both OpenLayers Features and plain GeoJSON
      let geomType: string
      if (isOlFeature(feature)) {
        // OpenLayers Feature
        const geometry = feature.getGeometry()
        if (!geometry) return 'Unknown'
        geomType = geometry.getType()
      } else {
        // Plain GeoJSON
        geomType = feature.geometry.type
      }
      
      // Use shared formatter utility for user-friendly names
      return formatGeometryTypeForDisplay(geomType)
    },
    getGeometryTypeColor(feature: PopupFeature): string {
      // Support both OpenLayers Features and plain GeoJSON
      let geometryType: string
      if (isOlFeature(feature)) {
        // OpenLayers Feature
        const geometry = feature.getGeometry()
        if (!geometry) return '#d1d5db'
        geometryType = geometry.getType()
      } else {
        // Plain GeoJSON
        geometryType = feature.geometry.type
      }
      return getGeometryTypeColor(geometryType)
    }
  }
})
</script>

