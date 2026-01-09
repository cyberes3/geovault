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
            title="Select this feature"
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

<script>
import { getGeometryTypeColor } from '@/utils/geometryColors.js'
import { formatGeometryTypeForDisplay } from '@/utils/geometryTypeFormatter.js'

export default {
  name: 'FeatureSelectionPopup',
  props: {
    features: {
      type: Array,
      required: true,
      default: () => []
    },
    position: {
      type: Object,
      required: true,
      default: () => ({x: 0, y: 0, containerWidth: 0, containerHeight: 0})
    },
    visible: {
      type: Boolean,
      default: false
    }
  },
  emits: ['select', 'close'],
  computed: {
    sortedFeatures() {
      // Sort features by geometry type: Points -> Lines -> Polygons
      // Within each group, preserve the original order
      const getGeometryTypeSortOrder = (feature) => {
        // Support both OpenLayers Features and plain GeoJSON
        let geometry
        if (typeof feature.getGeometry === 'function') {
          // OpenLayers Feature
          geometry = feature.getGeometry()
          if (!geometry) return 999
          const geomType = geometry.getType()
          
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
        } else {
          // Plain GeoJSON
          geometry = feature.geometry
          if (!geometry || !geometry.type) return 999
          
          const geomType = geometry.type
          
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
      }
      
      return [...this.features].sort((a, b) => {
        return getGeometryTypeSortOrder(a) - getGeometryTypeSortOrder(b)
      })
    },
    popupDimensions() {
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
    arrowPosition() {
      // Determine if arrow should be at top or bottom
      const y = this.position.y
      const containerHeight = this.position.containerHeight
      const { height } = this.popupDimensions
      
      // Check if popup is positioned above or below
      const popupTop = y - height - 10
      return popupTop < 0 ? 'top' : 'bottom'
    },
    popupStyle() {
      // Position the popup at the tap/click location
      // Coordinates are relative to the map container
      let x = this.position.x
      let y = this.position.y
      const containerWidth = this.position.containerWidth
      const containerHeight = this.position.containerHeight
      const { width, height, halfWidth } = this.popupDimensions

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
    arrowClass() {
      const baseClass = 'absolute left-1/2 transform -translate-x-1/2 w-4 h-4 bg-white border-b border-r border-gray-200 z-10'
      if (this.arrowPosition === 'top') {
        // Arrow at top pointing up - rotate 225deg (45 + 180) to point up
        return `${baseClass} rotate-[225deg] -top-2`
      } else {
        // Arrow at bottom pointing down - rotate 45deg to point down
        return `${baseClass} rotate-45 -bottom-2`
      }
    },
    arrowStyle() {
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
    getFeatureKey(feature, index) {
      // Generate a unique key for each feature
      // Support both OpenLayers Features and plain GeoJSON
      let properties
      if (typeof feature.get === 'function') {
        // OpenLayers Feature
        properties = feature.get('properties') || {}
      } else {
        // Plain GeoJSON
        properties = feature.properties || {}
      }
      
      // Use feature ID if available, otherwise use geometry + index
      if (properties.database_id) {
        return `feature_${properties.database_id}`
      }
      
      // Fallback: use geometry type and index
      let geometry, geomType
      if (typeof feature.getGeometry === 'function') {
        // OpenLayers Feature
        geometry = feature.getGeometry()
        geomType = geometry ? geometry.getType() : 'unknown'
      } else {
        // Plain GeoJSON
        geometry = feature.geometry
        geomType = geometry ? geometry.type : 'unknown'
      }
      return `feature_${geomType}_${index}`
    },
    getFeatureName(feature) {
      // Support both OpenLayers Features and plain GeoJSON
      let properties
      if (typeof feature.get === 'function') {
        // OpenLayers Feature
        properties = feature.get('properties') || {}
      } else {
        // Plain GeoJSON
        properties = feature.properties || {}
      }
      return properties.name || ''
    },
    getFeatureGeometryType(feature) {
      // Support both OpenLayers Features and plain GeoJSON
      let geometry, geomType
      if (typeof feature.getGeometry === 'function') {
        // OpenLayers Feature
        geometry = feature.getGeometry()
        if (!geometry) return 'Unknown'
        geomType = geometry.getType()
      } else {
        // Plain GeoJSON
        geometry = feature.geometry
        if (!geometry || !geometry.type) return 'Unknown'
        geomType = geometry.type
      }
      
      // Use shared formatter utility for user-friendly names
      return formatGeometryTypeForDisplay(geomType)
    },
    getGeometryTypeColor(feature) {
      // Support both OpenLayers Features and plain GeoJSON
      let geometry, geometryType
      if (typeof feature.getGeometry === 'function') {
        // OpenLayers Feature
        geometry = feature.getGeometry()
        if (!geometry) return '#d1d5db'
        geometryType = geometry.getType()
      } else {
        // Plain GeoJSON
        geometry = feature.geometry
        if (!geometry || !geometry.type) return '#d1d5db'
        geometryType = geometry.type
      }
      return getGeometryTypeColor(geometryType)
    }
  }
}
</script>

