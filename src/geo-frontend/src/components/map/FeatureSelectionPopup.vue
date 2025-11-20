<template>
  <div
      v-if="visible"
      :style="popupStyle"
      class="absolute bg-white rounded-lg shadow-xl border border-gray-200 z-20 max-w-sm w-72"
  >
    <div class="p-4">
      <!-- Header with close button -->
      <div class="flex items-start justify-end mb-2">
        <button
            class="text-gray-400 hover:text-gray-600 transition-colors"
            title="Close"
            @click="$emit('close')"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path d="M6 18L18 6M6 6l12 12" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
          </svg>
        </button>
      </div>

      <!-- Feature List -->
      <div class="space-y-1 max-h-64 overflow-y-auto">
        <div
            v-for="(feature, index) in features"
            :key="getFeatureKey(feature, index)"
            class="py-1.5 px-2 rounded-md hover:bg-gray-50 transition-colors"
        >
          <button
              class="w-full text-left flex items-center gap-2 text-sm text-gray-900 hover:text-blue-600 transition-colors"
              @click="$emit('select', feature)"
          >
            <span :class="getGeometryTypeClass(feature)"
                  class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium flex-shrink-0">
              {{ getFeatureGeometryType(feature) }}
            </span>
            <span class="font-medium truncate">
              {{ getFeatureName(feature) }}
            </span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
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
    popupStyle() {
      // Position popup offset from click point to avoid covering it
      const offsetX = 10
      const offsetY = 10

      // Use container dimensions from props, fallback to window dimensions
      const containerWidth = this.position.containerWidth || window.innerWidth
      const containerHeight = this.position.containerHeight || window.innerHeight

      // Calculate position
      let x = this.position.x + offsetX
      let y = this.position.y + offsetY

      // Ensure popup stays within viewport
      const popupWidth = 288 // w-72 = 18rem = 288px
      const popupHeight = 200 // Approximate max height

      if (x + popupWidth > containerWidth) {
        x = this.position.x - popupWidth - offsetX // Position to the left of click
      }
      if (y + popupHeight > containerHeight) {
        y = this.position.y - popupHeight - offsetY // Position above click
      }

      // Ensure popup doesn't go off the left or top edge
      if (x < 0) x = offsetX
      if (y < 0) y = offsetY

      return {
        left: `${x}px`,
        top: `${y}px`
      }
    }
  },
  methods: {
    getFeatureKey(feature, index) {
      // Generate a unique key for each feature
      const properties = feature.get('properties') || {}
      // Use feature ID if available, otherwise use geometry + index
      if (properties._id) {
        return `feature_${properties._id}`
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
      return geometry.getType()
    },
    getGeometryTypeClass(feature) {
      const geometryType = this.getFeatureGeometryType(feature)

      const classes = {
        'Point': 'bg-blue-100 text-blue-800',
        'MultiPoint': 'bg-blue-100 text-blue-800',
        'LineString': 'bg-green-100 text-green-800',
        'MultiLineString': 'bg-green-100 text-green-800',
        'Polygon': 'bg-yellow-100 text-yellow-800',
        'MultiPolygon': 'bg-yellow-100 text-yellow-800'
      }

      return classes[geometryType] || 'bg-gray-100 text-gray-800'
    }
  }
}
</script>

