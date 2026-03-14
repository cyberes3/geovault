<template>
  <div
    class="map-sidebar-panel w-full sm:w-[28rem] h-full flex flex-col bg-white border-l border-gray-200 overflow-hidden pointer-events-auto"
    style="box-shadow: -4px 0 15px -3px rgba(0, 0, 0, 0.08), -2px 0 6px -2px rgba(0, 0, 0, 0.04)"
  >
    <div class="flex items-center justify-between px-4 sm:px-5 py-3 border-b border-gray-200 bg-white flex-shrink-0">
      <h2 class="text-lg font-semibold text-gray-900 truncate min-w-0">
        {{ title }}
      </h2>
      <button
        type="button"
        @click="onCloseClick"
        class="p-2 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 focus:outline-none flex-shrink-0 transition-none"
        title="Close"
      >
        <XMarkIcon class="h-5 w-5" />
      </button>
    </div>
    <div class="flex-1 min-h-0 overflow-y-auto custom-scrollbar">
      <slot />
    </div>
  </div>
</template>

<script>
import { XMarkIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'MapSidebarPanel',
  components: { XMarkIcon },
  props: {
    title: { type: String, default: '' },
    /** When true, the header X emits 'close-overlay' so the parent can pop the overlay (e.g. return to group view) instead of closing the whole sidebar. */
    closeEmitsOverlayFirst: { type: Boolean, default: false },
  },
  emits: ['close', 'close-overlay'],
  methods: {
    onCloseClick() {
      if (this.closeEmitsOverlayFirst) {
        this.$emit('close-overlay');
      } else {
        this.$emit('close');
      }
    },
  },
};
</script>
