<template>
  <div 
    :class="[
      'bg-white flex flex-col',
      // Tablet landscape styles (compact) - show at lg (1024px)
      'lg:flex lg:static lg:w-56 lg:border-l lg:border-gray-200 lg:p-3 lg:h-full',
      // Desktop styles (full width) - show at xl (1280px) and above
      'xl:w-64 xl:p-4',
      // Mobile/Tablet portrait styles - up to lg (1024px)
      isMobileOpen ? 'fixed inset-0 z-50 w-full h-full p-4' : 'hidden'
    ]"
  >
    <!-- Mobile Header -->
    <div class="lg:hidden flex items-center justify-between mb-4 pb-3 border-b border-gray-200">
      <h2 class="text-lg font-semibold text-gray-900">Map Controls</h2>
      <button 
        @click="$emit('close')" 
        class="text-gray-500 hover:text-gray-700 p-2 sm:p-1 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 rounded-md hover:bg-gray-100"
      >
        <XMarkIcon class="w-6 h-6" />
      </button>
    </div>

    <!-- View Context Header (Tag/Collection name) -->
    <div v-if="viewContext" class="mb-4 pb-3 border-b border-gray-200 lg:mb-3 lg:pb-2 xl:mb-4 xl:pb-3">
      <div class="flex items-center gap-2 lg:gap-1.5 xl:gap-2">
        <TagIcon v-if="viewContext.type === 'tag'" class="w-5 h-5 text-blue-500 flex-shrink-0 lg:w-4 lg:h-4 xl:w-5 xl:h-5" />
        <FolderIcon v-else-if="viewContext.type === 'collection'" class="w-5 h-5 text-blue-500 flex-shrink-0 lg:w-4 lg:h-4 xl:w-5 xl:h-5" />
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-1 text-xs text-gray-500 uppercase tracking-wide lg:text-[10px] xl:text-xs">
            <ShareIcon v-if="viewContext.isPublicShare" class="w-3 h-3 lg:w-2.5 lg:h-2.5 xl:w-3 xl:h-3" />
            <span>{{ viewContext.isPublicShare ? 'Shared ' : '' }}{{ viewContext.type === 'tag' ? 'Tag' : 'Collection' }}</span>
          </div>
          <div class="text-sm font-semibold text-gray-900 truncate lg:text-xs xl:text-sm" :title="viewContext.name">
            {{ viewContext.name }}
          </div>
        </div>
      </div>
    </div>

    <h2 class="hidden lg:block text-lg font-semibold text-gray-900 mb-4 lg:text-base lg:mb-3 xl:text-lg xl:mb-4">Map Controls</h2>
    
    <!-- Layer Selection -->
    <div v-if="allowedOptions.mapLayer" class="mb-4 lg:mb-3 xl:mb-4">
      <label for="layer-select" class="block text-sm font-medium text-gray-700 mb-2 lg:text-xs lg:mb-1.5 xl:text-sm xl:mb-2">
        Map Layer
      </label>
      <select
        id="layer-select"
        :value="selectedLayer"
        @change="$emit('layer-change', $event.target.value)"
        class="select-custom w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 lg:px-2 lg:py-1.5 lg:text-xs xl:px-3 xl:py-2 xl:text-sm"
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

    <!-- Hide All Labels Toggle -->
    <div class="mb-4 lg:mb-3 xl:mb-4">
      <div class="flex items-start gap-3">
        <div class="flex-1 min-w-0">
          <div class="flex items-center justify-between gap-3">
            <div class="flex-1 min-w-0">
              <span class="block text-sm font-medium text-gray-700 lg:text-xs xl:text-sm">
                Hide all feature labels
              </span>
              <span class="block text-xs text-gray-500 lg:text-[11px] xl:text-xs mt-1">
                Hide labels for all features on the map
              </span>
            </div>
            <ToggleButton
              :model-value="!showAllLabels"
              label="Hide all feature labels"
              @update:model-value="$emit('labels-visibility-change', !$event)"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- Hillshade Toggle (only show if available) -->
    <div v-if="hillshadeAvailable" class="mb-4 lg:mb-3 xl:mb-4">
      <div class="flex items-start gap-3">
        <div class="flex-1 min-w-0">
          <div class="flex items-center justify-between gap-3">
            <div class="flex-1 min-w-0">
              <span class="block text-sm font-medium text-gray-700 lg:text-xs xl:text-sm">
                Hillshade
              </span>
              <span class="block text-xs text-gray-500 lg:text-[11px] xl:text-xs mt-1">
                Add shading to show terrain relief
              </span>
            </div>
            <ToggleButton
              :model-value="hillshadeEnabled"
              label="Enable hillshade"
              @update:model-value="$emit('hillshade-change', $event)"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- Hidden Features Summary & Feature Stats -->
    <div class="mt-auto text-xs text-gray-600 mb-4 space-y-3 lg:text-[11px] lg:mb-3 lg:space-y-2 xl:text-xs xl:mb-4 xl:space-y-3">
      <!-- Hidden features summary (account-level, main map only) -->
      <HiddenFeaturesWidget
        v-if="canManageHidden"
        :hidden-features="hiddenFeatures"
        :can-manage-hidden="canManageHidden"
        :is-mobile-open="isMobileOpen"
        :show-count="true"
        @unhide="$emit('unhide-feature', $event)"
        @unhide-all="$emit('unhide-all')"
      />

      <!-- Feature Stats -->
      <div v-if="allowedOptions.featureStats || allowedOptions.userLocation">
        <div class="space-y-1">
          <div v-if="allowedOptions.featureStats">
            Features: <span class="font-medium">{{ featureCount }}</span>
          </div>
          <div v-if="allowedOptions.userLocation && userLocation" class="text-gray-600">
            📍 {{ locationDisplayName }}
          </div>
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
import { XMarkIcon, ArrowDownTrayIcon, TagIcon, FolderIcon, ShareIcon } from '@heroicons/vue/24/outline'
import HiddenFeaturesWidget from './HiddenFeaturesWidget.vue'
import ToggleButton from '@/components/parts/ToggleButton.vue'

export default {
  name: 'MapControlsSidebar',
  components: {
    XMarkIcon,
    ArrowDownTrayIcon,
    TagIcon,
    FolderIcon,
    ShareIcon,
    HiddenFeaturesWidget,
    ToggleButton,
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
    },
    viewContext: {
      type: Object,
      default: null
    },
    hiddenFeatures: {
      type: Array,
      default: () => []
    },
    canManageHidden: {
      type: Boolean,
      default: false
    },
    showAllLabels: {
      type: Boolean,
      default: true
    },
    hillshadeAvailable: {
      type: Boolean,
      default: false
    },
    hillshadeEnabled: {
      type: Boolean,
      default: false
    }
  },
  emits: ['layer-change', 'close', 'unhide-feature', 'unhide-all', 'labels-visibility-change', 'hillshade-change'],
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

