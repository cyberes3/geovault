<template>
  <div
      v-if="visible"
      :style="popupStyle"
      class="absolute bg-white rounded-lg shadow-xl border border-gray-200 z-20 w-max max-w-[200px]"
  >
    <div class="p-2">
      <!-- Feature List -->
      <div class="space-y-0.5 max-h-48 overflow-y-auto">
        <div
            v-for="(feature, index) in sortedFeatures"
            :key="getFeatureKey(feature, index)"
            class="py-1 px-1.5 rounded-md hover:bg-gray-50 transition-colors"
            :style="{ borderLeft: `3px solid ${getGeometryTypeColor(feature)}` }"
        >
          <button
              class="w-full text-left flex items-center gap-2 text-xs text-gray-900 hover:text-blue-500 transition-colors"
              @click="$emit('select', feature)"
              title="Select this feature"
          >
            <span class="font-medium truncate">
              {{ getFeatureName(feature) }}
            </span>
          </button>
        </div>
      </div>
    </div>
    <!-- Bottom Arrow -->
    <div class="absolute -bottom-2 left-1/2 transform -translate-x-1/2 w-4 h-4 bg-white border-b border-r border-gray-200 rotate-45 z-10"></div>
  </div>
</template>

<script>
import { getGeometryTypeColor } from '@/utils/geometryColors.js'

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
        const geometry = feature.getGeometry()
        if (!geometry) return 999 // Unknown types go last
        
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
        // Unknown types last
        return 999
      }
      
      return [...this.features].sort((a, b) => {
        return getGeometryTypeSortOrder(a) - getGeometryTypeSortOrder(b)
      })
    },
    popupStyle() {
      // Center on click, but position ABOVE the point so the arrow points down to it
      let x = this.position.x
      let y = this.position.y

      // Offset Y by a bit more to account for the arrow (approx 10px)
      return {
        left: `${x}px`,
        top: `${y - 10}px`,
        transform: 'translate(-50%, -100%)'
      }
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
      const properties = feature.get('properties') || {}
      // Use feature ID if available, otherwise use geometry + index
      if (properties.database_id) {
        return `feature_${properties.database_id}`
      }
      // Fallback: use geometry type and index
      const geometry = feature.getGeometry()
      const geomType = geometry ? geometry.getType() : 'unknown'
      return `feature_${geomType}_${index}`
    },
    getFeatureName(feature) {
      const properties = feature.get('properties') || {}
      return properties.name || 'Unnamed Feature'
    },
    getFeatureGeometryType(feature) {
      const geometry = feature.getGeometry()
      if (!geometry) return 'Unknown'
      const geomType = geometry.getType()
      
      // Return user-friendly names
      if (geomType === 'LineString' || geomType === 'MultiLineString') {
        return 'Line'
      }
      
      return geomType
    },
    getGeometryTypeColor(feature) {
      const geometry = feature.getGeometry()
      if (!geometry) return '#d1d5db'
      const geometryType = geometry.getType()
      return getGeometryTypeColor(geometryType)
    }
  }
}
</script>

