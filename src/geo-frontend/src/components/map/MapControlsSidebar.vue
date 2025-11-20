<template>
  <div class="w-64 bg-white border-l border-gray-200 p-4 flex flex-col">
    <h2 class="text-lg font-semibold text-gray-900 mb-4">Map Controls</h2>
    
    <!-- Layer Selection -->
    <div v-if="allowedOptions.mapLayer" class="mb-4">
      <label for="layer-select" class="block text-sm font-medium text-gray-700 mb-2">
        Map Layer
      </label>
      <select
        id="layer-select"
        :value="selectedLayer"
        @change="$emit('layer-change', $event.target.value)"
        class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
      >
        <option
          v-for="source in tileSources"
          :key="source.id"
          :value="source.id"
        >
          {{ source.name }}
        </option>
      </select>
    </div>

    <!-- Feature Stats -->
    <div v-if="allowedOptions.featureStats || allowedOptions.userLocation" class="mt-auto text-xs text-gray-600">
      <div class="space-y-1">
        <div v-if="allowedOptions.featureStats">
          Features: <span class="font-medium">{{ featureCount }}</span> / <span class="font-medium">{{ maxFeatures }}</span>
        </div>
        <div v-if="allowedOptions.userLocation && userLocation" class="text-gray-600">
          📍 {{ locationDisplayName }}
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'MapControlsSidebar',
  props: {
    selectedLayer: {
      type: String,
      required: true
    },
    tileSources: {
      type: Array,
      required: true,
      default: () => []
    },
    featureCount: {
      type: Number,
      required: true,
      default: 0
    },
    maxFeatures: {
      type: Number,
      required: true,
      default: 5000
    },
    userLocation: {
      type: Object,
      default: null
    },
    locationDisplayName: {
      type: String,
      default: ''
    },
    allowedOptions: {
      type: Object,
      default: () => ({
        mapLayer: true,
        featureStats: true,
        userLocation: true
      })
    }
  },
  emits: ['layer-change']
}
</script>

