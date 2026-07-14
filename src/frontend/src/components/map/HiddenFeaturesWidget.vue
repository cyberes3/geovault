<template>
  <div>
    <!-- Count header (optional) -->
    <div v-if="showCount" class="flex items-center justify-between mb-1">
      <span class="font-medium text-gray-800">
        Hidden Features
      </span>
      <span class="text-gray-500">
        {{ hiddenFeatures.length }}
      </span>
    </div>

    <!-- Empty state with helpful text -->
    <div v-if="hiddenFeatures.length === 0" class="text-gray-400 text-[11px] italic">
      <template v-if="isMobileOpen !== undefined">
        <!-- For map sidebar: use isMobileOpen prop -->
        {{ isMobileOpen ? 'Tap the eye icon to hide features' : 'Right-click features to hide them' }}
      </template>
      <template v-else-if="isMobile !== undefined">
        <!-- For settings page: use isMobile prop -->
        On the map page, {{ isMobile ? 'tap the eye icon' : 'right-click features' }} to hide them.
      </template>
      <template v-else>
        <!-- Fallback -->
        Right-click features to hide them
      </template>
    </div>

    <!-- Summary list (first 3 items) -->
    <ul v-else class="space-y-1">
      <li
        v-for="item in hiddenFeatures.slice(0, 3)"
        :key="item.id"
        class="flex items-center justify-between py-0.5"
        :style="{ borderLeft: `3px solid ${getGeometryTypeColor(item.geometry_type)}`, paddingLeft: '6px' }"
      >
        <span class="truncate mr-2">
          {{ item.name || 'Unnamed feature' }}
        </span>
        <button
          type="button"
          class="text-[11px] text-blue-600 hover:text-blue-800"
          :title="'Un-hide this feature'"
          @click="$emit('unhide', item.id)"
        >
          Show
        </button>
      </li>
      <li v-if="hiddenFeatures.length > 3" class="text-gray-400 text-[11px] pl-2">
        + {{ hiddenFeatures.length - 3 }} more
      </li>
    </ul>

    <!-- Manage button -->
    <button
      type="button"
      class="mt-2 inline-flex items-center px-2 py-1 text-[11px] font-medium rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
      :class="hiddenFeatures.length > 0 ? 'text-blue-600 hover:text-blue-800 hover:bg-blue-50' : 'text-gray-400'"
      :disabled="hiddenFeatures.length === 0"
      @click="showModal = true"
    >
      Manage hidden features
    </button>

    <!-- Modal for full management -->
    <HiddenFeaturesModal
      :visible="showModal"
      :items="hiddenFeatures"
      @close="showModal = false"
      @unhide="handleUnhide"
      @unhide-all="handleUnhideAll"
    />
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import HiddenFeaturesModal from './HiddenFeaturesModal.vue'
import { getGeometryTypeColor } from '@/utils/geometryColors.js'
import type { HiddenFeature } from '@/assets/js/store/modules/userSettings'

export default defineComponent({
  name: 'HiddenFeaturesWidget',
  components: {
    HiddenFeaturesModal,
  },
  props: {
    hiddenFeatures: {
      type: Array as PropType<HiddenFeature[]>,
      required: true,
    },
    canManageHidden: {
      type: Boolean,
      default: true,
    },
    isMobileOpen: {
      type: Boolean,
      default: undefined,
    },
    isMobile: {
      type: Boolean,
      default: undefined,
    },
    showCount: {
      type: Boolean,
      default: true,
    },
  },
  emits: ['unhide', 'unhide-all'],
  data() {
    return {
      showModal: false,
    }
  },
  watch: {
    // Close modal when all features are unhidden
    hiddenFeatures(newVal: HiddenFeature[]) {
      if (newVal.length === 0 && this.showModal) {
        this.showModal = false
      }
    }
  },
  methods: {
    getGeometryTypeColor,
    handleUnhide(featureId: string) {
      this.$emit('unhide', featureId)
    },
    handleUnhideAll() {
      this.$emit('unhide-all')
      // Note: Parent will handle closing the modal after the async operation completes
    },
  },
})
</script>

