<template>
  <div class="w-80 bg-white border-r border-gray-200 p-1.5 flex flex-col">
    <h2 class="text-xs font-semibold text-gray-900 mb-1 px-1">Features in View</h2>
    
    <div class="flex-1 overflow-y-auto select-none">
      <div v-if="!features || features.length === 0" class="text-xs text-gray-500 text-center py-3">
        No features
      </div>
      <div v-else class="space-y-0.5">
        <div
          v-for="feature in features"
          :key="getFeatureId(feature)"
          @click="handleFeatureClick(feature)"
          class="px-1.5 py-1 bg-gray-50 hover:bg-gray-100 transition-colors flex items-center cursor-pointer"
          :style="{ borderLeft: `3px solid ${getGeometryTypeColor(feature)}` }"
        >
          <div class="text-xs text-gray-900 truncate">
            {{ getFeatureName(feature) }}
          </div>
        </div>
      </div>
    </div>
    
    <div class="mt-1 text-xs text-gray-500 border-t border-gray-200 pt-1 px-1">
      {{ features ? features.length : 0 }}
    </div>
  </div>
</template>

<script>
export default {
  name: 'FeatureListSidebar',
  props: {
    features: {
      type: Array,
      required: false,
      default: () => []
    }
  },
  emits: ['feature-click'],
  methods: {
    getFeatureId(feature) {
      if (!feature._geoJsonMapId) {
        feature._geoJsonMapId = `feature_${Date.now()}_${Math.random()}`
      }
      return feature._geoJsonMapId
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
    getGeometryTypeColor(feature) {
      const geometryType = this.getFeatureGeometryType(feature)
      
      const colors = {
        'Point': '#93c5fd',
        'MultiPoint': '#93c5fd',
        'LineString': '#86efac',
        'MultiLineString': '#86efac',
        'Polygon': '#fbbf24',
        'MultiPolygon': '#fbbf24'
      }
      
      return colors[geometryType] || '#d1d5db'
    },
    handleFeatureClick(feature) {
      this.$emit('feature-click', feature)
    }
  }
}
</script>

