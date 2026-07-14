<template>
  <Teleport to="body" :disabled="!isMobileOpen">
    <div
      data-app-mobile-overlay="sheet"
      :class="sidebarRootClass"
      :role="isMobileOpen ? 'dialog' : undefined"
      :aria-modal="isMobileOpen ? 'true' : undefined"
      :aria-labelledby="isMobileOpen ? 'map-controls-sidebar-title' : undefined"
    >
    <!-- Mobile Header (full-width so border runs edge to edge; match left sidebar) -->
    <div class="lg:hidden flex items-center justify-between px-4 py-3 border-b border-gray-200">
      <h2 id="map-controls-sidebar-title" class="text-lg font-semibold text-gray-900">Map Controls</h2>
      <button
          @click="$emit('close')"
          class="text-gray-500 hover:text-gray-700 p-2 sm:p-1 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 rounded-md hover:bg-gray-100"
      >
        <XMarkIcon class="w-6 h-6"/>
      </button>
    </div>

    <!-- Content area: padded on mobile (header is full-bleed); desktop uses root padding -->
    <div class="flex-1 flex flex-col min-h-0 overflow-auto p-4 lg:p-0">
    <!-- View Context Header (Tag/Collection name) -->
    <div v-if="viewContext" class="mb-4 pb-3 border-b border-gray-200 lg:mb-3 lg:pb-2 xl:mb-4 xl:pb-3">
      <div class="flex items-center gap-2 lg:gap-1.5 xl:gap-2">
        <TagIcon v-if="viewContext.type === 'tag'"
                 class="w-5 h-5 text-blue-500 flex-shrink-0 lg:w-4 lg:h-4 xl:w-5 xl:h-5"/>
        <FolderIcon v-else-if="viewContext.type === 'collection'"
                    class="w-5 h-5 text-blue-500 flex-shrink-0 lg:w-4 lg:h-4 xl:w-5 xl:h-5"/>
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-1 text-xs text-gray-500 uppercase tracking-wide lg:text-[10px] xl:text-xs">
            <ShareIcon v-if="viewContext.isPublicShare" class="w-3 h-3 lg:w-2.5 lg:h-2.5 xl:w-3 xl:h-3"/>
            <span>{{ viewContext.isPublicShare ? 'Shared ' : '' }}{{
                viewContext.type === 'tag' ? 'Tag' : 'Collection'
              }}</span>
          </div>
          <div class="text-sm font-semibold text-gray-900 truncate lg:text-xs xl:text-sm" :title="viewContext.name">
            {{ viewContext.name }}
          </div>
        </div>
      </div>
    </div>

    <h2 class="hidden lg:block text-lg font-semibold text-gray-900 mb-4 lg:text-base lg:mb-3 xl:text-lg xl:mb-4">
Map
      Controls
</h2>

    <!-- Layer Selection -->
    <div v-if="allowedOptions.mapLayer" class="mb-4 lg:mb-3 xl:mb-4">
      <label for="layer-select"
             class="block text-sm font-medium text-gray-700 mb-2 lg:text-xs lg:mb-1.5 xl:text-sm xl:mb-2">
        Map Layer
      </label>
      <select
          id="layer-select"
          :value="selectedLayer"
          @change="$emit('layer-change', ($event.target as HTMLSelectElement).value)"
          class="select-custom w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none lg:px-2 lg:py-1.5 lg:text-xs xl:px-3 xl:py-2 xl:text-sm"
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
                label="Enable Hillshade"
                @update:model-value="$emit('hillshade-change', $event)"
            />
          </div>
        </div>
      </div>
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
                Hide Labels for All Features on the Map
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

    <!-- Quick Point Link (only show if not in public share mode) -->
    <div v-if="!isPublicShareMode" class="mb-4 lg:mb-3 xl:mb-4 text-center">
      <button
          type="button"
          @click="$emit('quick-point')"
          class="text-xs text-blue-600 hover:text-blue-800 hover:underline inline-flex items-center font-medium focus:outline-none lg:text-[11px] xl:text-xs"
          title="Add a Quick Point by Pasting Coordinates"
      >
        <MapPinIcon class="w-3 h-3 mr-1 lg:w-2.5 lg:h-2.5 lg:mr-0.5 xl:w-3 xl:h-3 xl:mr-1"/>
        Quick Point
      </button>
    </div>

    <!-- Hidden Features Summary & Feature Stats -->
    <div
        class="mt-auto text-xs text-gray-600 mb-4 space-y-3 lg:text-[11px] lg:mb-3 lg:space-y-2 xl:text-xs xl:mb-4 xl:space-y-3">
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
    </div>

    <!-- Download Button (for public shares with downloads enabled) -->
    <div v-if="isPublicShareMode && allowDownloads" class="mt-auto">
      <BaseButton
          @click="handleDownload"
          class="w-full"
          variant="primary"
          color="blue"
          size="md"
          title="Download All Features as KMZ"
      >
        <ArrowDownTrayIcon class="w-5 h-5 mr-2"/>
        Download All
      </BaseButton>
    </div>
    </div>
    </div>
  </Teleport>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import {APIHOST} from '@/config.js'
import BaseButton from '@/components/parts/BaseButton.vue'
import {ArrowDownTrayIcon, FolderIcon, MapPinIcon, ShareIcon, TagIcon, XMarkIcon} from '@heroicons/vue/24/outline'
import HiddenFeaturesWidget from './HiddenFeaturesWidget.vue'
import ToggleButton from '@/components/parts/ToggleButton.vue'
import type { TileSource } from '@/api/services/tilesApi'
import type { MapViewContext, UserLocation } from '@/composables/mapPageTypes'
import type { HiddenFeature } from '@/assets/js/store/modules/userSettings'

interface MapControlsAllowedOptions {
  mapLayer?: boolean;
  featureStats?: boolean;
  userLocation?: boolean;
  [key: string]: unknown;
}

export default defineComponent({
  name: 'MapControlsSidebar',
  components: {
    BaseButton,
    XMarkIcon,
    ArrowDownTrayIcon,
    TagIcon,
    FolderIcon,
    ShareIcon,
    MapPinIcon,
    HiddenFeaturesWidget,
    ToggleButton,
  },
  props: {
    selectedLayer: {
      type: String,
      required: true
    },
    tileSources: {
      type: Array as PropType<TileSource[]>,
      default: () => []
    },
    featureCount: {
      type: Number,
      default: 0
    },
    userLocation: {
      type: Object as PropType<UserLocation | null>,
      default: null
    },
    locationDisplayName: {
      type: String,
      default: ''
    },
    allowedOptions: {
      type: Object as PropType<MapControlsAllowedOptions>,
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
      type: Object as PropType<MapViewContext | null>,
      default: null
    },
    hiddenFeatures: {
      type: Array as PropType<HiddenFeature[]>,
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
  emits: ['layer-change', 'close', 'unhide-feature', 'unhide-all', 'labels-visibility-change', 'hillshade-change', 'quick-point'],
  computed: {
    sidebarRootClass(): string {
      if (this.isMobileOpen) {
        return [
          'bg-white',
          'flex',
          'flex-col',
          'fixed',
          'inset-0',
          'z-50',
          'w-full',
          'h-full',
          'lg:hidden'
        ].join(' ')
      }
      return [
        'bg-white',
        'flex',
        'flex-col',
        'hidden',
        'lg:flex',
        'lg:static',
        'lg:w-56',
        'lg:border-l',
        'lg:border-gray-200',
        'lg:p-3',
        'lg:h-full',
        'xl:w-64',
        'xl:p-4'
      ].join(' ')
    }
  },
  watch: {
    isMobileOpen(open: boolean) {
      if (open) {
        document.body.classList.add('overflow-hidden')
      } else {
        document.body.classList.remove('overflow-hidden')
      }
    }
  },
  beforeUnmount() {
    document.body.classList.remove('overflow-hidden')
  },
  methods: {
    handleDownload() {
      if (!this.shareId) {
        return
      }
      const url = `${APIHOST}/api/export-kmz?share=${encodeURIComponent(this.shareId)}`
      window.open(url, '_blank')
    }
  }
})
</script>

