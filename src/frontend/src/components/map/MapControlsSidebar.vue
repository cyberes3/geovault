<template>
  <div 
    :class="[
      'bg-white flex flex-col',
      // Desktop styles
      'md:flex md:static md:w-64 md:border-l md:border-gray-200 md:p-4 md:h-full',
      // Mobile styles
      isMobileOpen ? 'fixed inset-0 z-50 w-full h-full p-4' : 'hidden'
    ]"
  >
    <!-- Mobile Header -->
    <div class="md:hidden flex items-center justify-between mb-4 pb-3 border-b border-gray-200">
      <h2 class="text-lg font-semibold text-gray-900">Map Controls</h2>
      <button 
        @click="$emit('close')" 
        class="text-gray-500 hover:text-gray-700 p-1 rounded-md hover:bg-gray-100"
      >
        <XMarkIcon class="w-6 h-6" />
      </button>
    </div>

    <h2 class="hidden md:block text-lg font-semibold text-gray-900 mb-4">Map Controls</h2>
    
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
    <div v-if="allowedOptions.featureStats || allowedOptions.userLocation" class="mt-auto text-xs text-gray-600 mb-4">
      <div class="space-y-1">
        <div v-if="allowedOptions.featureStats">
          Features: <span class="font-medium">{{ featureCount }}</span> / <span class="font-medium">{{ maxFeatures }}</span>
        </div>
        <div v-if="allowedOptions.userLocation && userLocation" class="text-gray-600">
          📍 {{ locationDisplayName }}
        </div>
      </div>
    </div>

    <!-- Download Button (for public shares with downloads enabled) -->
    <div v-if="isPublicShareMode && allowDownloads" class="mt-auto">
      <button
        @click="handleDownload"
        class="w-full inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors"
        title="Download all features as KMZ"
      >
        <ArrowDownTrayIcon class="w-5 h-5 mr-2" />
        Download All
      </button>
    </div>
  </div>
</template>

<script>
import {APIHOST} from '@/config.js'
import { XMarkIcon, ArrowDownTrayIcon } from '@heroicons/vue/24/outline'

export default {
  name: 'MapControlsSidebar',
  components: {
    XMarkIcon,
    ArrowDownTrayIcon
  },
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
    },
    isPublicShareMode: {
      type: Boolean,
      default: false
    },
    shareId: {
      type: String,
      default: null
    },
    allowDownloads: {
      type: Boolean,
      default: false
    },
    isMobileOpen: {
      type: Boolean,
      default: false
    }
  },
  emits: ['layer-change', 'close'],
  methods: {
    handleDownload() {
      if (!this.shareId) {
        return
      }
      const url = `${APIHOST}/api/export-kmz?share=${encodeURIComponent(this.shareId)}`
      window.open(url, '_blank')
    }
  }
}
</script>

