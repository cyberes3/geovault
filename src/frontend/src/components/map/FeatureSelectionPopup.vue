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
            v-for="(feature, index) in features"
            :key="getFeatureKey(feature, index)"
            class="py-1 px-1.5 rounded-md hover:bg-gray-50 transition-colors"
        >
          <button
              class="w-full text-left flex items-center gap-2 text-xs text-gray-900 hover:text-blue-500 transition-colors"
              @click="$emit('select', feature)"
              title="Select this feature"
          >
            <span :class="getGeometryTypeClass(feature)"
                  class="inline-flex items-center px-1.5 py-0.5 rounded-full text-[10px] font-medium flex-shrink-0">
              {{ getFeatureGeometryType(feature) }}
            </span>
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
        'Point': 'bg-blue-100 text-blue-700',
        'MultiPoint': 'bg-blue-100 text-blue-700',
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

