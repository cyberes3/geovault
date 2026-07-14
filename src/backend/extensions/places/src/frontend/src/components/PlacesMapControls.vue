<template>
  <div
      class="absolute z-10 bottom-4 left-4 flex flex-col bg-white border border-gray-200 rounded shadow-md overflow-hidden"
  >
    <button
        type="button"
        class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
        title="Choose Basemap"
        @click="$emit('open-layer-picker')"
    >
      <Square3Stack3DIcon class="w-5 h-5"/>
    </button>
    <button
        type="button"
        class="p-2 border-t border-gray-200 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
        title="Go to Home Extent"
        @click="$emit('reset-viewport')"
    >
      <HomeIcon class="w-5 h-5"/>
    </button>
  </div>
</template>

<script setup lang="ts">
import { HomeIcon, Square3Stack3DIcon } from '@heroicons/vue/24/outline';

defineEmits<{
  'open-layer-picker': [];
  'reset-viewport': [];
}>();
</script>
