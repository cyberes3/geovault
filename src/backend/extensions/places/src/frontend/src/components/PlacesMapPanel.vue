<template>
  <div class="w-full sm:w-1/2 min-w-0 min-h-[250px] flex-shrink-0 relative bg-gray-100 rounded-none sm:rounded-lg border border-gray-200 border-t-0 border-b-0 sm:border-t sm:border-b overflow-hidden h-[250px] sm:h-full sm:min-h-[280px]">
    <div ref="mapContainer" class="absolute inset-0 z-0 h-full w-full min-h-0 touch-pan-y"></div>
    <PlacesMapControls
        @open-layer-picker="$emit('open-layer-picker')"
        @reset-viewport="$emit('reset-viewport')"
    />
  </div>
</template>

<script setup>
import { ref } from 'vue';
import PlacesMapControls from '@/components/PlacesMapControls.vue';

defineEmits(['open-layer-picker', 'reset-viewport']);

const mapContainer = ref(null);
defineExpose({ mapContainer });
</script>
