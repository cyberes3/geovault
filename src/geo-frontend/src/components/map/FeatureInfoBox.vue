<template>
  <div v-if="feature" class="absolute bottom-4 right-4 bg-white rounded-lg shadow-lg border border-gray-200 z-10 max-w-sm">
    <div class="p-3">
      <div class="flex items-start justify-between mb-2">
        <h3 class="text-sm font-semibold text-gray-900">{{ getFeatureName(feature) }}</h3>
        <button
          @click="$emit('close')"
          class="ml-2 text-gray-400 hover:text-gray-600"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
          </svg>
        </button>
      </div>
      <div class="text-xs text-gray-600 space-y-1">
        <div>Type: {{ getFeatureGeometryType(feature) }}</div>
        <div v-if="getFeatureProperties(feature).length > 0" class="mt-2 pt-2 border-t border-gray-200">
          <div v-for="prop in getFeatureProperties(feature)" :key="prop.key" class="mb-1">
            <span class="font-medium">{{ prop.key }}:</span> {{ prop.value }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'FeatureInfoBox',
  props: {
    feature: {
      type: Object,
      default: null
    }
  },
  emits: ['close'],
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
    getFeatureProperties(feature) {
      const properties = feature.get('properties') || {}
      const props = []
      
      const excludeKeys = ['name', 'geojson_hash', '_geoJsonMapId']
      
      for (const [key, value] of Object.entries(properties)) {
        if (!excludeKeys.includes(key) && value !== null && value !== undefined && value !== '') {
          let displayValue = value
          if (typeof value === 'object') {
            displayValue = JSON.stringify(value)
          } else {
            displayValue = String(value)
          }
          props.push({ key, value: displayValue })
        }
      }
      
      return props
    }
  }
}
</script>

